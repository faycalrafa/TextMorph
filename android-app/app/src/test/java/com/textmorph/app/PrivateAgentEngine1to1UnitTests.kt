package com.textmorph.app

import com.textmorph.app.agent.memory.ActionStep
import com.textmorph.app.agent.memory.SavedSkill
import com.textmorph.app.agent.memory.SkillMemoryService
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.navigation.NavigationShortcutHelper
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.recovery.RecoveryEngine
import com.textmorph.app.agent.ui.AgentVisualCues
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PrivateAgentEngine1to1UnitTests {

    private lateinit var tempDir: File
    private lateinit var skillMemoryService: SkillMemoryService
    private val recoveryEngine = RecoveryEngine()

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "textmorph_test_skills_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val memFile = File(tempDir, "skills_memory.jsonl")
        if (memFile.exists()) memFile.delete()
        skillMemoryService = SkillMemoryService(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ─── 1. Floating Companion & Visual Cues Emojis ───────────────────

    @Test
    fun testFloatingCompanionVisualCues_haveDistinctEmojis() {
        // Thinking
        val thinkingCue = AgentVisualCues.resolve("thinking", isThinking = true)
        assertEquals("🤔", thinkingCue.emoji)

        // Screen viewing (animated gazing eyes)
        val viewScreenCue = AgentVisualCues.resolve(AgentAction.ACTION_READ_SCREEN)
        assertEquals("👀", viewScreenCue.emoji)

        // Tapping
        val clickTextCue = AgentVisualCues.resolve(AgentAction.ACTION_CLICK_TEXT)
        assertEquals("👆", clickTextCue.emoji)
        val clickAtCue = AgentVisualCues.resolve(AgentAction.ACTION_CLICK_AT)
        assertEquals("👆", clickAtCue.emoji)
        val clickElementCue = AgentVisualCues.resolve(AgentAction.ACTION_CLICK_ELEMENT)
        assertEquals("👆", clickElementCue.emoji)

        // Typing
        val typeCue = AgentVisualCues.resolve(AgentAction.ACTION_TYPE_TEXT)
        assertEquals("⌨️", typeCue.emoji)

        // IME Enter
        val enterCue = AgentVisualCues.resolve(AgentAction.ACTION_PRESS_ENTER)
        assertEquals("↩️", enterCue.emoji)

        // Scroll and Swipe
        val scrollCue = AgentVisualCues.resolve(AgentAction.ACTION_SCROLL)
        assertEquals("↕️", scrollCue.emoji)
        val swipeCue = AgentVisualCues.resolve(AgentAction.ACTION_SWIPE)
        assertEquals("👉", swipeCue.emoji)

        // System navigation
        val backCue = AgentVisualCues.resolve(AgentAction.ACTION_PRESS_BACK)
        assertEquals("◀️", backCue.emoji)
        val homeCue = AgentVisualCues.resolve(AgentAction.ACTION_PRESS_HOME)
        assertEquals("🏠", homeCue.emoji)

        // Apps & Web
        val appCue = AgentVisualCues.resolve(AgentAction.ACTION_OPEN_APP)
        assertEquals("📱", appCue.emoji)
        val urlCue = AgentVisualCues.resolve(AgentAction.ACTION_OPEN_URL)
        assertEquals("🌐", urlCue.emoji)

        // Wait
        val waitCue = AgentVisualCues.resolve("wait")
        assertEquals("⏳", waitCue.emoji)

        // Recovery & Memory
        val recoveryCue = AgentVisualCues.resolve("recovery")
        assertEquals("🔄", recoveryCue.emoji)
        val memoryCue = AgentVisualCues.resolve("memory")
        assertEquals("🧠", memoryCue.emoji)

        // Completion & Interruption
        val doneCue = AgentVisualCues.resolve(AgentAction.ACTION_DONE)
        assertEquals("✅", doneCue.emoji)
        val cancelCue = AgentVisualCues.resolve("interrupted")
        assertEquals("🛑", cancelCue.emoji)
    }

    // ─── 2. Skill Memory Subsystem ───────────────────────────────────

    @Test
    fun testSkillMemory_keywordExtractionAndSimilarity() {
        val keywords = skillMemoryService.extractKeywords("Open YouTube and search for cats")
        assertFalse(keywords.contains("and"))
        assertFalse(keywords.contains("for"))
        assertTrue(keywords.contains("youtube"))
        assertTrue(keywords.contains("search"))
        assertTrue(keywords.contains("cats"))

        val k1 = listOf("youtube", "search", "cats")
        val k2 = listOf("youtube", "search", "cats", "funny")
        val sim = skillMemoryService.jaccardSimilarity(k1, k2)
        // 3 / 4 = 0.75
        assertEquals(0.75, sim, 0.01)

        val kDisjoint = listOf("settings", "display", "dark")
        val simDisjoint = skillMemoryService.jaccardSimilarity(k1, kDisjoint)
        assertEquals(0.0, simDisjoint, 0.01)
    }

    @Test
    fun testSkillMemory_saveFindAndReliabilityMetric() = runBlocking {
        val goal = "Turn on dark mode in settings"
        val steps = listOf(
            ActionStep("open_app", mapOf("app_name" to "Settings")),
            ActionStep("click_text", mapOf("text" to "Display")),
            ActionStep("click_text", mapOf("text" to "Dark theme"))
        )

        skillMemoryService.saveSkill(goal, steps)

        // Match goal with slight variation (adds 'system', Jaccard similarity = 3/4 = 0.75 > 0.6)
        val found = skillMemoryService.findSkill("turn on dark mode in system settings")
        assertNotNull(found)
        assertEquals(3, found?.steps?.size)
        assertTrue(found?.isReliable == true) // successCount=1, failCount=0

        // Test reliability formula
        val skill = SavedSkill(
            id = "1",
            task = "Test",
            taskKeywords = listOf("test"),
            successCount = 2,
            failCount = 1,
            lastUsedTimestamp = System.currentTimeMillis(),
            steps = emptyList()
        )
        // 1 / (2 + 1) = 0.333 > 0.3 -> unreliable
        assertFalse(skill.isReliable)

        val reliableSkill = skill.copy(successCount = 4, failCount = 1)
        // 1 / (4 + 1) = 0.2 < 0.3 -> reliable
        assertTrue(reliableSkill.isReliable)
    }

    @Test
    fun testSkillMemory_recordFailure() = runBlocking {
        val goal = "Check bluetooth settings"
        val steps = listOf(ActionStep("open_app", mapOf("app_name" to "Settings")))
        skillMemoryService.saveSkill(goal, steps)

        val found = skillMemoryService.findSkill(goal)
        assertNotNull(found)
        val skillId = found!!.id

        skillMemoryService.recordFailure(skillId)
        val updated = skillMemoryService.findSkill(goal)
        assertEquals(1, updated?.failCount)
    }

    // ─── 3. Self-Healing Recovery Engine ─────────────────────────────

    @Test
    fun testRecoveryEngine_diagnoseAllFailureModes() {
        // 1. Loading screen
        val loadingRecovery = recoveryEngine.diagnose("click_text", "Screen: [0] text 'Buffering please wait...'")
        assertEquals("wait", loadingRecovery.action)
        assertTrue(loadingRecovery.description.contains("loading"))

        // 2. Keyboard blocking screen
        val keyboardRecovery = recoveryEngine.diagnose("click_text", "Screen: [1] view 'com.google.android.inputmethod.latin:id/keyboard'")
        assertEquals("press_back", keyboardRecovery.action)
        assertTrue(keyboardRecovery.description.contains("Keyboard"))

        // 3. Click failed on scrollable screen -> scroll down
        val scrollRecovery = recoveryEngine.diagnose("click_text", "Screen: [0] view 'content' [scrollable]")
        assertEquals("scroll", scrollRecovery.action)
        assertEquals("down", scrollRecovery.params["direction"])

        // 4. Click failed on non-scrollable screen -> press back
        val backRecovery = recoveryEngine.diagnose("click_text", "Screen: [0] btn 'Submit'")
        assertEquals("press_back", backRecovery.action)

        // 5. Open app failed -> press home
        val homeRecovery = recoveryEngine.diagnose("open_app", "Screen: empty")
        assertEquals("press_home", homeRecovery.action)
    }

    // ─── 4. Navigation Shortcuts ─────────────────────────────────────

    @Test
    fun testNavigationShortcuts_matchesPrivateAgentPatterns() {
        // Dark mode
        val darkMode = NavigationShortcutHelper.getNavigationShortcut("Switch to dark mode")
        assertNotNull(darkMode)
        assertEquals(2, darkMode?.size)
        assertEquals("open_app", darkMode?.get(0)?.action)
        assertEquals("Settings", darkMode?.get(0)?.params?.get("app_name"))
        assertEquals("click_text", darkMode?.get(1)?.action)
        assertEquals("Display", darkMode?.get(1)?.params?.get("text"))

        // Wi-Fi
        val wifi = NavigationShortcutHelper.getNavigationShortcut("Turn on wi-fi")
        assertNotNull(wifi)
        assertEquals("Network & internet", wifi?.get(1)?.params?.get("text"))

        // Bluetooth
        val bluetooth = NavigationShortcutHelper.getNavigationShortcut("Connect to bluetooth")
        assertNotNull(bluetooth)
        assertEquals("Connected devices", bluetooth?.get(1)?.params?.get("text"))

        // Quick app pattern
        val yt = NavigationShortcutHelper.getNavigationShortcut("Search youtube for dogs")
        assertNotNull(yt)
        assertEquals(1, yt?.size)
        assertEquals("YouTube", yt?.get(0)?.params?.get("app_name"))

        // Generic open regex
        val spotify = NavigationShortcutHelper.getNavigationShortcut("open spotify")
        assertNotNull(spotify)
        assertEquals("Spotify", spotify?.get(0)?.params?.get("app_name"))

        // Unknown task -> null (falls back to AI)
        val unknown = NavigationShortcutHelper.getNavigationShortcut("Calculate tip for dinner")
        assertNotNull(unknown) // Calculator keyword matches
        assertEquals("Calculator", unknown?.get(0)?.params?.get("app_name"))

        val noMatch = NavigationShortcutHelper.getNavigationShortcut("Inspect battery consumption percentage")
        assertNull(noMatch)

        val trulyGeneric = NavigationShortcutHelper.getNavigationShortcut("Find the highest mountain in Asia")
        assertNull(trulyGeneric)
    }

    // ─── 5. Code-as-Action System Prompts ─────────────────────────────

    @Test
    fun testTaskSystemPrompt_enforcesCodeAsActionDirectives() {
        val codeTaskPrompt = AgentPrompts.getTaskSystemPrompt()
        assertTrue(codeTaskPrompt.contains("phone automation agent"))
        assertTrue(codeTaskPrompt.contains("click_text(\"exact text to click\")"))
        assertTrue(codeTaskPrompt.contains("click_at(540, 960)"))
        assertTrue(codeTaskPrompt.contains("type_text(\"hello\", hint=\"optional hint\")"))
        assertTrue(codeTaskPrompt.contains("press_enter()"))
        assertTrue(codeTaskPrompt.contains("scroll(\"down\")"))
        assertTrue(codeTaskPrompt.contains("swipe(540, 2000, 540, 500)"))
        assertTrue(codeTaskPrompt.contains("open_app(\"WhatsApp\")"))
        assertTrue(codeTaskPrompt.contains("done(\"Task complete\")"))
        assertTrue(codeTaskPrompt.contains("Never scroll or swipe more than three times in a row"))
        assertFalse(codeTaskPrompt.contains("\"action\":"))
        assertFalse(codeTaskPrompt.contains("JSON object"))
    }
}
