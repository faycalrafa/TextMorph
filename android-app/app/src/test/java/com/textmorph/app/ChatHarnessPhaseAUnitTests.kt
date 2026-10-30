package com.textmorph.app

import com.textmorph.app.agent.guardrails.ExecutionGuardrail
import com.textmorph.app.agent.guardrails.ToolValidator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.router.ChatCommandRouter
import com.textmorph.app.agent.router.CommandRouteResult
import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolDefinition
import com.textmorph.app.agent.tools.registry.AgentToolParameter
import com.textmorph.app.agent.tools.registry.AgentToolRegistry
import com.textmorph.app.agent.tools.registry.ToolFilter
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHarnessPhaseAUnitTests {

    @Test
    fun testChatCommandRouterSlashCommands() {
        val workersResult = ChatCommandRouter.route("/workers 3")
        assertTrue(workersResult is CommandRouteResult.SetWorkersCommand)
        val workersCmd = workersResult as CommandRouteResult.SetWorkersCommand
        assertEquals(3, workersCmd.workerCount)

        val workersClamped = ChatCommandRouter.route("/workers 10")
        val clampedCmd = workersClamped as CommandRouteResult.SetWorkersCommand
        assertEquals(4, clampedCmd.workerCount)
        assertTrue(clampedCmd.message.contains("thermal throttling"))

        val planInline = ChatCommandRouter.route("/plan -w 3 Build an offline LLM pipeline")
        assertTrue(planInline is CommandRouteResult.PlanCommand)
        val planCmd = planInline as CommandRouteResult.PlanCommand
        assertEquals(3, planCmd.workersOverride)
        assertEquals("Build an offline LLM pipeline", planCmd.goal)

        val deepsearchInline = ChatCommandRouter.route("/deepsearch --workers=4 Android LiteRT benchmarks")
        assertTrue(deepsearchInline is CommandRouteResult.DeepSearchCommand)
        val deepCmd = deepsearchInline as CommandRouteResult.DeepSearchCommand
        assertEquals(4, deepCmd.workersOverride)
        assertEquals("Android LiteRT benchmarks", deepCmd.query)

        val benchmarkCmd = ChatCommandRouter.route("/benchmark")
        assertTrue(benchmarkCmd is CommandRouteResult.BenchmarkCommand)
    }

    @Test
    fun testChatCommandRouterFastExitForCasualChat() {
        val casualTurn = ChatCommandRouter.route("Hello, how are you today?")
        assertTrue(casualTurn is CommandRouteResult.StandardTurn)
        val stdTurn = casualTurn as CommandRouteResult.StandardTurn
        assertEquals(AgentPhase.PHASE_CHAT, stdTurn.phase)

        val tools = ToolFilter.getToolsForPhase(stdTurn.phase, null)
        assertEquals(0, tools.size)
        val prompt = ToolFilter.formatCompactToolSignatures(tools)
        assertTrue(prompt.isEmpty())
    }

    @Test
    fun testToolFilterPhaseDynamicTools() {
        val researchTools = ToolFilter.getToolsForPhase(AgentPhase.PHASE_RESEARCH, null)
        assertTrue(researchTools.any { it.id == "search_web" })
        assertTrue(researchTools.any { it.id == "fetch_page" })
        assertFalse(researchTools.any { it.id == "make_call" })

        val automationTools = ToolFilter.getToolsForPhase(AgentPhase.PHASE_AUTOMATION, null)
        assertTrue(automationTools.any { it.id == "click_text" })
        assertTrue(automationTools.any { it.id == "type_text" })
        assertTrue(automationTools.any { it.id == "open_app" })
    }

    @Test
    fun testToolValidatorMissingRequiredParamsAndSelfCorrectionPrompt() {
        val tool = AgentToolDefinition(
            id = "search_web",
            name = "Web Search",
            category = AgentToolCategory.APPS_AND_WEB,
            description = "Search web",
            codeSignature = "search_web(query: String)",
            parameters = listOf(
                AgentToolParameter(name = "query", label = "Query", type = "string", isRequired = true)
            )
        )

        // Invalid: missing required query
        val invalidAction = AgentAction(action = "search_web", params = emptyMap())
        val res = ToolValidator.validate(invalidAction, listOf(tool))
        assertFalse(res.isValid)
        assertTrue(res.error.contains("Missing required parameter: query"))

        val correctionPrompt = ToolValidator.synthesizeCorrectionPrompt(invalidAction, res.error)
        assertTrue(correctionPrompt.contains("Missing required parameter: query"))
        assertTrue(correctionPrompt.contains("Please fix the tool call parameters"))

        // Valid: with query parameter
        val validAction = AgentAction(action = "search_web", params = mapOf("query" to JsonPrimitive("LiteRT")))
        val validRes = ToolValidator.validate(validAction, listOf(tool))
        assertTrue(validRes.isValid)
    }

    @Test
    fun testExecutionGuardrailLoopDetectionAndBudget() {
        val guardrail = ExecutionGuardrail(maxHops = 3)
        val params = mapOf("query" to JsonPrimitive("android 17"))

        val firstCall = guardrail.evaluate("search_web", params)
        assertTrue(firstCall.allowed)

        // Loop detection: identical tool call back-to-back
        val duplicateCall = guardrail.evaluate("search_web", params)
        assertFalse(duplicateCall.allowed)
        assertTrue(duplicateCall.reason.contains("You already executed 'search_web'"))

        // Different tool call (hop 3 reaches max budget 3)
        val differentCall = guardrail.evaluate("fetch_page", mapOf("url" to JsonPrimitive("https://android.com")))
        assertTrue(differentCall.allowed)

        // Exceeded budget (hop 4 > 3)
        val fourthCall = guardrail.evaluate("search_web", mapOf("query" to JsonPrimitive("gemini")))
        assertFalse(fourthCall.allowed)
        assertTrue(fourthCall.reason.contains("budget exceeded"))
    }

    @Test
    fun testCodeActionParserEarlyTerminationDetection() {
        val incomplete = "I will check that for you.\n```cmd\nsearch_web(\"Android 17\")\n"
        assertFalse(CodeActionParser.hasCompleteAction(incomplete))

        val complete = "I will check that for you.\n```cmd\nsearch_web(\"Android 17\")\n```"
        assertTrue(CodeActionParser.hasCompleteAction(complete))

        val standalone = "search_web(\"Android 17\")"
        val parsed = CodeActionParser.parse(standalone)
        assertNotNull(parsed)
        assertEquals("search_web", parsed?.action)
        assertEquals("Android 17", parsed?.getStringParam("query"))
    }
}
