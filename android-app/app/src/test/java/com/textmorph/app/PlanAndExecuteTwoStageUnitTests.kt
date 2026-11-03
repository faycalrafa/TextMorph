package com.textmorph.app

import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.artifacts.SectionStatus
import com.textmorph.app.agent.orchestration.BoundedWorkerPool
import com.textmorph.app.agent.orchestration.DeepPlanEngine
import com.textmorph.app.agent.orchestration.DeepSearchCoordinator
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.router.ChatCommandRouter
import com.textmorph.app.agent.router.CommandRouteResult
import com.textmorph.app.llm.LlmEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying the Two-Stage Plan-and-Execute Engine, Interactive Plan Cards,
 * and Multi-Agent Worker Execution Pipeline (/plan & /deepsearch).
 */
class PlanAndExecuteTwoStageUnitTests {

    private val mockEngine = object : LlmEngine {
        override suspend fun initialize(modelPath: String?): Boolean = true
        override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?): String = ""
        override fun isLoaded(): Boolean = true
        override suspend fun unload() {}
    }

    @Test
    fun testPlanCommandRouting_StandardDecomposition() {
        val res = ChatCommandRouter.route("/plan create a swimming fish animation taking whole screen")
        assertTrue("Should be routed as PlanCommand", res is CommandRouteResult.PlanCommand)
        val cmd = res as CommandRouteResult.PlanCommand
        assertEquals("create a swimming fish animation taking whole screen", cmd.goal)
        assertFalse("Standard /plan should not be execute mode", cmd.isExecute)
        assertFalse("Standard /plan should not be edit mode", cmd.isEdit)
        assertNull(cmd.editFeedback)
    }

    @Test
    fun testPlanCommandRouting_ExecuteFlag() {
        val res1 = ChatCommandRouter.route("/plan --execute create a swimming fish animation")
        assertTrue(res1 is CommandRouteResult.PlanCommand)
        val cmd1 = res1 as CommandRouteResult.PlanCommand
        assertTrue("Should be execute mode", cmd1.isExecute)
        assertEquals("create a swimming fish animation", cmd1.goal)

        val res2 = ChatCommandRouter.route("/plan -x")
        assertTrue(res2 is CommandRouteResult.PlanCommand)
        val cmd2 = res2 as CommandRouteResult.PlanCommand
        assertTrue("Should be execute mode with -x flag", cmd2.isExecute)

        val res3 = ChatCommandRouter.route("/plan execute")
        assertTrue(res3 is CommandRouteResult.PlanCommand)
        val cmd3 = res3 as CommandRouteResult.PlanCommand
        assertTrue("Should be execute mode with leading execute keyword", cmd3.isExecute)
    }

    @Test
    fun testPlanCommandRouting_EditFlag() {
        val res1 = ChatCommandRouter.route("/plan --edit Use Canvas instead of SVGs and add mouse avoidance")
        assertTrue(res1 is CommandRouteResult.PlanCommand)
        val cmd1 = res1 as CommandRouteResult.PlanCommand
        assertTrue("Should be edit mode", cmd1.isEdit)
        assertEquals("Use Canvas instead of SVGs and add mouse avoidance", cmd1.editFeedback)

        val res2 = ChatCommandRouter.route("/plan -e Add tail ripple physics")
        assertTrue(res2 is CommandRouteResult.PlanCommand)
        val cmd2 = res2 as CommandRouteResult.PlanCommand
        assertTrue("Should be edit mode with -e flag", cmd2.isEdit)
        assertEquals("Add tail ripple physics", cmd2.editFeedback)
    }

    @Test
    fun testDeepSearchCommandRouting_TwoStageFlags() {
        val resPlan = ChatCommandRouter.route("/deepsearch On-device SLM inference latency")
        assertTrue(resPlan is CommandRouteResult.DeepSearchCommand)
        val cmdPlan = resPlan as CommandRouteResult.DeepSearchCommand
        assertFalse(cmdPlan.isExecute)
        assertFalse(cmdPlan.isEdit)
        assertEquals("On-device SLM inference latency", cmdPlan.query)

        val resExec = ChatCommandRouter.route("/deepsearch --execute On-device SLM")
        assertTrue(resExec is CommandRouteResult.DeepSearchCommand)
        val cmdExec = resExec as CommandRouteResult.DeepSearchCommand
        assertTrue(cmdExec.isExecute)

        val resEdit = ChatCommandRouter.route("/deepsearch --edit Focus on Snapdragon NPU vs CPU")
        assertTrue(resEdit is CommandRouteResult.DeepSearchCommand)
        val cmdEdit = resEdit as CommandRouteResult.DeepSearchCommand
        assertTrue(cmdEdit.isEdit)
        assertEquals("Focus on Snapdragon NPU vs CPU", cmdEdit.editFeedback)
    }

    @Test
    fun testParsePlanSections_PipeFormatAndRoleInference() {
        val planEngine = DeepPlanEngine(null, mockEngine)
        val rawLlmOutput = """
            1. Collect Vector Assets | Asset Harvester | Generate multi-segment fish SVGs and caustic textures.
            2. Kinematic Research | Research Scout | Extract sine-wave locomotion formulas and transform patterns.
            3. Viewport Construction | Logic Engineer | Setup 100vw/100vh full-screen responsive canvas.
            4. Performance & Layout Audit | QA Validator | Ensure 60 FPS and zero layout thrashing.
        """.trimIndent()

        val sections = planEngine.parsePlanSections(rawLlmOutput, "Fish Animation")
        assertEquals(4, sections.size)

        assertEquals("Collect Vector Assets", sections[0].title)
        assertEquals("Asset Harvester", sections[0].role)
        assertEquals("Generate multi-segment fish SVGs and caustic textures.", sections[0].rationale)

        assertEquals("Kinematic Research", sections[1].title)
        assertEquals("Research Scout", sections[1].role)

        assertEquals("Viewport Construction", sections[2].title)
        assertEquals("Logic Engineer", sections[2].role)

        assertEquals("Performance & Layout Audit", sections[3].title)
        assertEquals("QA Validator", sections[3].role)
    }

    @Test
    fun testParsePlanSections_DashFormatAndKeywordRoleInference() {
        val planEngine = DeepPlanEngine(null, mockEngine)
        val rawLlmOutput = """
            1. SVG Asset Creation - Draw fish body, segmented tail, and fins.
            2. Research Physics Formulas - Retrieve inverse kinematics for serpentine locomotion.
            3. Animation Script Assembly - Connect HTML canvas with requestAnimationFrame loop.
            4. Browser Performance Test - Verify 60 FPS under CPU throttling.
        """.trimIndent()

        val sections = planEngine.parsePlanSections(rawLlmOutput, "Fish Animation")
        assertEquals(4, sections.size)

        assertEquals("Asset Harvester", sections[0].role)
        assertEquals("Research Scout", sections[1].role)
        assertEquals("Logic Engineer", sections[2].role)
        assertEquals("QA Validator", sections[3].role)
    }

    @Test
    fun testFormatPlanMarkdown_CleanStructureWithoutTokenLeaks() {
        val planEngine = DeepPlanEngine(null, mockEngine)
        val sections = listOf(
            PlanSection(index = 1, title = "Asset Harvesting", queryOrGoal = "Generate SVGs", role = "Asset Harvester", rationale = "Generates SVG assets."),
            PlanSection(index = 2, title = "Kinematics Research", queryOrGoal = "Formulas", role = "Research Scout", rationale = "Retrieves motion formulas."),
            PlanSection(index = 3, title = "Canvas Assembly", queryOrGoal = "Build canvas", role = "Logic Engineer", rationale = "Constructs animation engine."),
            PlanSection(index = 4, title = "QA Audit", queryOrGoal = "Verify 60 FPS", role = "QA Validator", rationale = "Verifies 60 FPS.")
        )

        val markdown = planEngine.formatPlanMarkdown("Fish Animation", sections, version = 1)
        assertTrue(markdown.contains("### 📋 Proposed Execution Plan (v1)"))
        assertTrue(markdown.contains("| **01** | Asset Harvesting | `Asset Harvester` | Generates SVG assets. |"))
        assertTrue(markdown.contains("Click **Execute Plan**"))

        // Assert zero token leaks
        assertFalse(markdown.contains("<think>"))
        assertFalse(markdown.contains("write_section"))
        assertFalse(markdown.contains("<execute_tool>"))
    }

    @Test
    fun testWorkerPool_SanitizeSectionContent() {
        val pool = BoundedWorkerPool(2)

        val rawWithThink = """
            <think>
            The user wants me to draft Section 1.
            I will now generate the content.
            </think>
            ## 1. Visual Concepts
            This is the rich content of the section.
        """.trimIndent()

        val sanitizedThink = pool.sanitizeSectionContent(rawWithThink)
        assertFalse(sanitizedThink.contains("<think>"))
        assertFalse(sanitizedThink.contains("The user wants me to draft"))
        assertTrue(sanitizedThink.contains("## 1. Visual Concepts"))
        assertTrue(sanitizedThink.contains("This is the rich content of the section."))

        val rawWithPlaintextToolCall = """
            write_section(index=1, markdown="## 1. Vector Assets\n\nSVG path definition goes here.")
        """.trimIndent()

        val sanitizedTool = pool.sanitizeSectionContent(rawWithPlaintextToolCall)
        assertFalse(sanitizedTool.contains("write_section("))
        assertTrue(sanitizedTool.contains("## 1. Vector Assets"))
        assertTrue(sanitizedTool.contains("SVG path definition goes here."))

        val rawWithConnectionError = """
            ## 2. Kinematics
            Connection error: failed to connect to faycal-computer/100.110.183.49 (port 8080) from /100.96.202.23 after 15000ms
            The motion uses sine wave algorithms for swimming.
        """.trimIndent()

        val sanitizedError = pool.sanitizeSectionContent(rawWithConnectionError)
        assertFalse(sanitizedError.contains("Connection error:"))
        assertFalse(sanitizedError.contains("faycal-computer"))
        assertTrue(sanitizedError.contains("## 2. Kinematics"))
        assertTrue(sanitizedError.contains("The motion uses sine wave algorithms for swimming."))
    }

    @Test
    fun testDeepSearchCoordinator_FormatSearchPlanMarkdown() {
        val sections = listOf(
            PlanSection(index = 1, title = "Architecture & Models", queryOrGoal = "Examine on-device SLM architectures", role = "Research Scout", rationale = "Examine on-device SLM architectures"),
            PlanSection(index = 2, title = "Empirical Benchmarks", queryOrGoal = "Collect tokens/sec telemetry", role = "Research Scout", rationale = "Collect tokens/sec telemetry")
        )

        val markdown = DeepSearchCoordinator.formatSearchPlanMarkdown("On-device SLMs", sections, version = 1)
        assertTrue(markdown.contains("### 🔍 Proposed Deep Search Plan (v1)"))
        assertTrue(markdown.contains("| **01** | Architecture & Models | `Research Scout` |"))
        assertTrue(markdown.contains("Click **Execute Research**"))
    }
}
