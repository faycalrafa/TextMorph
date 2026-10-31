package com.textmorph.app

import com.textmorph.app.agent.artifacts.ArtifactAssemblyStore
import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.artifacts.SectionStatus
import com.textmorph.app.agent.concurrency.ImageAssetRegistry
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.agent.tools.registry.ToolFilter
import com.textmorph.app.agent.tools.web.ImageSearchResult
import com.textmorph.app.agent.tools.web.ImageSearchTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageSearchAndAliasingUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        ImageAssetRegistry.clear()
    }

    @Test
    fun testImageAssetRegistryAliasingAndResolution() {
        val url1 = "https://upload.wikimedia.org/wikipedia/commons/1/1a/Headphones_Front.jpg"
        val url2 = "https://upload.wikimedia.org/wikipedia/commons/2/2b/Headphones_Side.jpg"

        val alias1 = ImageAssetRegistry.register(url1, "Sony WH-1000XM5 Front View")
        val alias2 = ImageAssetRegistry.register(url2, "Sony WH-1000XM5 Side Profile")

        assertEquals("IMG_1", alias1)
        assertEquals("IMG_2", alias2)

        // Test deduplication
        val alias1Again = ImageAssetRegistry.register(url1, "Sony WH-1000XM5 Front View")
        assertEquals("IMG_1", alias1Again)

        // Test resolution
        assertEquals(url1, ImageAssetRegistry.resolve("IMG_1"))
        assertEquals(url2, ImageAssetRegistry.resolve("IMG_2"))
        assertNull(ImageAssetRegistry.resolve("IMG_999"))

        // Test Record retrieval
        val record = ImageAssetRegistry.getRecord("IMG_1")
        assertNotNull(record)
        assertEquals("Sony WH-1000XM5 Front View", record!!.title)
        assertEquals(url1, record.originalUrl)
    }

    @Test
    fun testRegisterAndFormatStub() {
        val candidates = listOf(
            ImageSearchResult(
                title = "Sony WH-1000XM5 Front View",
                imageUrl = "https://upload.wikimedia.org/sony_front.jpg",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:sony_front.jpg"
            ),
            ImageSearchResult(
                title = "Sony WH-1000XM5 Side Profile",
                imageUrl = "https://upload.wikimedia.org/sony_side.jpg",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:sony_side.jpg"
            )
        )

        val stub = ImageAssetRegistry.registerAndFormatStub(candidates)
        assertTrue(stub.contains("[Image Search Results]:"))
        assertTrue(stub.contains("- IMG_1: \"Sony WH-1000XM5 Front View\" (URL safely stored in registry)"))
        assertTrue(stub.contains("- IMG_2: \"Sony WH-1000XM5 Side Profile\" (URL safely stored in registry)"))
        assertTrue(stub.contains("![Caption](IMG_1)"))
    }

    @Test
    fun testMarkdownAliasReplacement() {
        val url = "https://images.example.com/products/camera.jpg"
        val alias = ImageAssetRegistry.register(url, "Vintage 35mm Camera")
        assertEquals("IMG_1", alias)

        val inputMarkdown = """
            # Camera Review
            Here is the classic camera model:
            ![Vintage 35mm Camera](IMG_1)

            Also available at (IMG_1).
        """.trimIndent()

        val resolved = ImageAssetRegistry.resolveMarkdownAliases(inputMarkdown)
        assertTrue(resolved.contains("![Vintage 35mm Camera]($url)"))
        assertTrue(resolved.contains("($url)"))
        assertFalse(resolved.contains("IMG_1"))
    }

    @Test
    fun testImageSearchToolFiltering() {
        // Reject SVGs
        assertFalse(ImageSearchTool.isValidImageCandidate("https://example.com/logo.svg", "image/svg+xml", 500, 500))
        assertFalse(ImageSearchTool.isValidImageCandidate("https://example.com/icon.svg?v=2", "image/svg", 500, 500))

        // Reject tracking beacons and spacers
        assertFalse(ImageSearchTool.isValidImageCandidate("https://example.com/pixel.gif", "image/gif", 1, 1))
        assertFalse(ImageSearchTool.isValidImageCandidate("https://example.com/spacer.png", "image/png", 100, 100))
        assertFalse(ImageSearchTool.isValidImageCandidate("https://example.com/analytics_beacon.jpg", "image/jpeg", 800, 600))

        // Reject tiny icons
        assertFalse(ImageSearchTool.isValidImageCandidate("https://example.com/favicon.png", "image/png", 16, 16))
        assertFalse(ImageSearchTool.isValidImageCandidate("https://example.com/small_badge.png", "image/png", 48, 48))

        // Accept valid photographs
        assertTrue(ImageSearchTool.isValidImageCandidate("https://upload.wikimedia.org/photo.jpg", "image/jpeg", 1920, 1080))
        assertTrue(ImageSearchTool.isValidImageCandidate("https://images.unsplash.com/photo.webp", "image/webp", 800, 600))
    }

    @Test
    fun testToolFilterPhaseIsolation() {
        // Present in PHASE_RESEARCH
        val researchTools = ToolFilter.getToolsForPhase(AgentPhase.PHASE_RESEARCH)
        val hasSearchImages = researchTools.any { it.id == AgentAction.ACTION_SEARCH_IMAGES }
        assertTrue("search_images must be available during PHASE_RESEARCH", hasSearchImages)

        // Absent in PHASE_CHAT (casual chat fast exit)
        val chatTools = ToolFilter.getToolsForPhase(AgentPhase.PHASE_CHAT)
        assertTrue("PHASE_CHAT must inject 0 tools", chatTools.isEmpty())

        // Absent in PHASE_AUTOMATION
        val autoTools = ToolFilter.getToolsForPhase(AgentPhase.PHASE_AUTOMATION)
        assertFalse(autoTools.any { it.id == AgentAction.ACTION_SEARCH_IMAGES })
    }

    @Test
    fun testArtifactAssemblyStoreImageResolution() = runBlocking {
        val cacheDir = tempFolder.newFolder("artifacts_cache")
        val store = ArtifactAssemblyStore(cacheDir, "artifact-session-456")

        val realUrl = "https://upload.wikimedia.org/mars_perseverance.jpg"
        val alias = ImageAssetRegistry.register(realUrl, "Perseverance Rover on Mars")
        assertEquals("IMG_1", alias)

        val sections = listOf(
            PlanSection(index = 1, title = "Mission Overview", queryOrGoal = "Mars exploration background"),
            PlanSection(index = 2, title = "Visual Telemetry", queryOrGoal = "Rover hardware visuals")
        )

        store.initSession("Mars Perseverance Report", sections)

        store.writeSection(1, "The Perseverance rover was launched in July 2020 and landed in Jezero crater.")
        store.updateStatus(1, SectionStatus.DONE)

        // Section 2 references IMG_1
        store.writeSection(2, "Hardware photo of the masthead camera:\n\n![Perseverance Masthead]($alias)\n\nHigh fidelity imagery confirmed surface operations.")
        store.updateStatus(2, SectionStatus.DONE)

        assertTrue(store.areAllSectionsCompleted())

        val compiled = store.compileFinalArtifact()
        assertTrue("Compiled artifact must resolve IMG_1 to real URL", compiled.contains("![Perseverance Masthead]($realUrl)"))
        assertFalse("Compiled artifact must not contain unresolved short alias", compiled.contains("IMG_1"))
    }
}
