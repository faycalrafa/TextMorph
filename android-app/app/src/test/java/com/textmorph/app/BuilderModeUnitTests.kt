package com.textmorph.app

import com.textmorph.app.agent.builder.FileEditingEngine
import com.textmorph.app.agent.router.ChatCommandRouter
import com.textmorph.app.agent.router.CommandRouteResult
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.data.model.ChatMessageEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying /builder mode, FileEditingEngine 4-tier progressive matching,
 * syntax rollback, lazy comment guardrails, and full step details trace preservation.
 */
class BuilderModeUnitTests {

    private lateinit var tempDir: File
    private lateinit var engine: FileEditingEngine

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "textmorph_builder_test_${System.currentTimeMillis()}").apply { mkdirs() }
        engine = FileEditingEngine(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // Tier 1: Exact Substring Match
    // -------------------------------------------------------------------------
    @Test
    fun testTier1ExactMatch() {
        val filename = "index.html"
        val initial = "<html>\n  <body>\n    <h1>Hello</h1>\n  </body>\n</html>"
        engine.writeFile(filename, initial)

        val res = engine.editFile(filename, "    <h1>Hello</h1>", "    <h1>Hello Aquarium</h1>")
        assertTrue("Expected Tier 1 match in result: $res", res.contains("Tier 1 (Exact Match)"))
        assertTrue(res.contains("SUCCESS"))

        val content = File(tempDir, filename).readText()
        assertTrue(content.contains("<h1>Hello Aquarium</h1>"))
        assertFalse(content.contains("<h1>Hello</h1>"))
    }

    // -------------------------------------------------------------------------
    // Tier 2: Trimmed Line Match (Trailing Whitespace)
    // -------------------------------------------------------------------------
    @Test
    fun testTier2TrailingWhitespaceMatch() {
        val filename = "style.css"
        val initial = "body {\n    margin: 0;   \n    background: #000; \n}"
        engine.writeFile(filename, initial)

        val search = "body {\n    margin: 0;\n    background: #000;\n}"
        val replace = "body {\n    margin: 0;\n    background: #004d99;\n}"

        val res = engine.editFile(filename, search, replace)
        assertTrue("Expected Tier 2 match in result: $res", res.contains("Tier 2 (Trimmed Line Match)"))

        val content = File(tempDir, filename).readText()
        assertTrue(content.contains("#004d99"))
    }

    // -------------------------------------------------------------------------
    // Tier 3: Indentation-Insensitive Match
    // -------------------------------------------------------------------------
    @Test
    fun testTier3IndentationInsensitiveMatch() {
        val filename = "calc.py"
        val initial = "class Calc:\n    def add(self):\n        x = 10\n        y = 20\n        return x + y\n"
        engine.writeFile(filename, initial)

        // 4B model sends search with 0 leading spaces
        val search = "x = 10\ny = 20\nreturn x + y"
        val replace = "x = 10\ny = 50\nz = 100\nreturn x + y + z"

        val res = engine.editFile(filename, search, replace)
        assertTrue("Expected Tier 3 match in result: $res", res.contains("Tier 3 (Indentation-Insensitive Match)"))

        val content = File(tempDir, filename).readText()
        // Relative indentation must be preserved (8 spaces)
        assertTrue(content.contains("        x = 10\n        y = 50\n        z = 100\n        return x + y + z"))
    }

    // -------------------------------------------------------------------------
    // Tier 4: Fuzzy Anchor Match
    // -------------------------------------------------------------------------
    @Test
    fun testTier4FuzzyAnchorMatch() {
        val filename = "app.js"
        val initial = """
            function setupAquarium() {
                const canvas = document.getElementById('water');
                const ctx = canvas.getContext('2d');
                const fishList = [];
                for (let i = 0; i < 5; i++) {
                    fishList.push(new Fish(i * 50, 100));
                }
                return fishList;
            }
        """.trimIndent()
        engine.writeFile(filename, initial)

        // Middle line is slightly hallucinated (skipped const ctx)
        val search = """
            function setupAquarium() {
                const canvas = document.getElementById('water');
                const fishList = [];
                for (let i = 0; i < 5; i++) {
                    fishList.push(new Fish(i * 50, 100));
                }
                return fishList;
            }
        """.trimIndent()

        val replace = """
            function setupAquarium() {
                const canvas = document.getElementById('water');
                const ctx = canvas.getContext('2d');
                const fishList = [new Shark(100, 100)];
                return fishList;
            }
        """.trimIndent()

        val res = engine.editFile(filename, search, replace)
        assertTrue("Expected Tier 4 match in result: $res", res.contains("Tier 4 (Fuzzy Anchor Match"))

        val content = File(tempDir, filename).readText()
        assertTrue(content.contains("new Shark(100, 100)"))
    }

    // -------------------------------------------------------------------------
    // Error Handling: Ambiguous Multiple Matches (>1)
    // -------------------------------------------------------------------------
    @Test
    fun testAmbiguousMultipleMatchesError() {
        val filename = "dup.txt"
        engine.writeFile(filename, "item\nitem\nitem\n")

        val res = engine.editFile(filename, "item", "replacement")
        assertTrue(res.startsWith("ERROR: SEARCH block matched 3 places"))
        assertTrue(res.contains("Include 2-3 more surrounding lines"))
    }

    // -------------------------------------------------------------------------
    // Error Handling: 0 Matches with Closest Suggestion
    // -------------------------------------------------------------------------
    @Test
    fun testZeroMatchesClosestSuggestion() {
        val filename = "config.py"
        engine.writeFile(filename, "PORT = 8080\nHOST = '127.0.0.1'\nDEBUG = True\n")

        val search = "HOST = '0.0.0.0'\nDEBUG = False"
        val res = engine.editFile(filename, search, "HOST = '10.0.0.1'")
        assertTrue(res.startsWith("ERROR: SEARCH block not found in config.py"))
        assertTrue(res.contains("Did you mean this section"))
        assertTrue(res.contains("HOST = '127.0.0.1'"))
    }

    // -------------------------------------------------------------------------
    // Error Handling: Lazy Placeholder Comment Rejection
    // -------------------------------------------------------------------------
    @Test
    fun testLazyPlaceholderRejection() {
        val filename = "doc.html"
        engine.writeFile(filename, "<div>\n  <p>Hello</p>\n</div>")

        val res = engine.editFile(filename, "<p>Hello</p>", "<p>Hi</p>\n// ... existing code ...")
        assertTrue(res.contains("ERROR: Lazy placeholder comment detected"))
    }

    // -------------------------------------------------------------------------
    // Syntax Validation & Automatic Rollback
    // -------------------------------------------------------------------------
    @Test
    fun testSyntaxValidationRollback() {
        val filename = "app.js"
        val initial = "function test() {\n    return [1, 2, 3];\n}"
        engine.writeFile(filename, initial)

        // Introduce mismatched bracket: [1, 2, 3)
        val search = "return [1, 2, 3];"
        val replace = "return [1, 2, 3);"

        val res = engine.editFile(filename, search, replace)
        assertTrue(res.contains("ERROR: Syntax validation failed"))
        assertTrue(res.contains("Edit was ROLLED BACK"))

        val content = File(tempDir, filename).readText()
        assertEquals(initial, content) // Disk file preserved
    }

    // -------------------------------------------------------------------------
    // Command Routing for /builder
    // -------------------------------------------------------------------------
    @Test
    fun testCommandRoutingForBuilder() {
        val res1 = ChatCommandRouter.route("/builder make aquarium animation")
        assertTrue(res1 is CommandRouteResult.BuilderCommand)
        val cmd1 = res1 as CommandRouteResult.BuilderCommand
        assertEquals("make aquarium animation", cmd1.goal)
        assertFalse(cmd1.isExecute)

        val res2 = ChatCommandRouter.route("/builder --execute build fish game")
        assertTrue(res2 is CommandRouteResult.BuilderCommand)
        val cmd2 = res2 as CommandRouteResult.BuilderCommand
        assertEquals("build fish game", cmd2.goal)
        assertTrue(cmd2.isExecute)
    }

    // -------------------------------------------------------------------------
    // Full Untruncated Step Details Preservation
    // -------------------------------------------------------------------------
    @Test
    fun testFullStepDetailsTracePreservation() {
        val longOutput = (1..50).joinToString("\n") { "Line $it: Detailed tool execution log output" }
        val trace = AgentStepTrace(
            stepIndex = 1,
            toolName = "FileEditingEngine",
            inputQuery = "<<<<<<< WRITE index.html\n...code...\n>>>>>>> END",
            status = "success",
            durationMs = 1500L,
            outputSnippet = longOutput,
            thought = "I need to initialize the aquarium canvas and CSS viewport."
        )

        val json = ChatMessageEntity.serializeStepTraces(listOf(trace))
        assertNotNull(json)

        val deserialized = ChatMessageEntity(
            sessionId = "test_session",
            role = "assistant",
            content = "Done",
            stepTracesJson = json
        ).getStepTraces()

        assertEquals(1, deserialized.size)
        val restored = deserialized.first()
        assertEquals("FileEditingEngine", restored.toolName)
        assertEquals(longOutput, restored.outputSnippet)
        assertEquals("I need to initialize the aquarium canvas and CSS viewport.", restored.thought)
    }
}
