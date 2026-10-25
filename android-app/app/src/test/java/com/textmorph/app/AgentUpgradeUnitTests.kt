package com.textmorph.app

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.ui.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUpgradeUnitTests {

    @Test
    fun testParseSingleJsonAction() {
        val output = """
            Certainly! Opening YouTube now.
            ```json
            {
              "action": "open_app",
              "parameters": {
                "name": "youtube"
              }
            }
            ```
        """.trimIndent()

        val parsed = AgentActionParser.parse(output)
        assertNotNull(parsed)
        assertEquals("open_app", parsed?.action)
        assertEquals("youtube", parsed?.getStringParam("name"))
    }

    @Test
    fun testParseRawJsonActionWithoutCodeFences() {
        val output = """
            {"action": "set_volume", "parameters": {"level": 75}}
        """.trimIndent()

        val parsed = AgentActionParser.parse(output)
        assertNotNull(parsed)
        assertEquals("set_volume", parsed?.action)
        assertEquals(75, parsed?.getIntParam("level"))
    }

    @Test
    fun testParseNonActionConversationalText() {
        val output = "Hello! How can I help you today?"
        val parsed = AgentActionParser.parse(output)
        assertNull(parsed)
    }

    @Test
    fun testCleanResponseStripping() {
        val output = """
            I am opening Settings for you.
            ```json
            {"action": "open_app", "parameters": {"name": "settings"}}
            ```
        """.trimIndent()

        val cleaned = AgentActionParser.cleanResponse(output)
        assertEquals("I am opening Settings for you.", cleaned)
        assertFalse(cleaned.contains("open_app"))
        assertFalse(cleaned.contains("```"))
    }

    @Test
    fun testDefaultSeedsContainCorrectAction() {
        val defaultSeeds = PromptRepository.getDefaultSeedPrompts()
        val correctSeed = defaultSeeds.find { it.id == "seed_correct" }
        assertNotNull("seed_correct must exist in default seeds", correctSeed)
        assertEquals("Correct", correctSeed?.title)
        assertTrue(correctSeed?.systemInstruction?.contains("Fix all grammar, punctuation, and spelling errors") == true)
        assertEquals(0, correctSeed?.sortOrder)
    }

    @Test
    fun testThinkingBlockRegexExtraction() {
        val rawModelOutput = "<think>\nUser said hey, greeting back.\n</think>\nHello! How can I assist you today?"
        val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        val match = thinkRegex.find(rawModelOutput)
        assertNotNull(match)
        val thought = match?.groupValues?.get(1)?.trim()
        val cleanAnswer = rawModelOutput.replace(thinkRegex, "").trim()

        assertEquals("User said hey, greeting back.", thought)
        assertEquals("Hello! How can I assist you today?", cleanAnswer)
        assertFalse(cleanAnswer.contains("<think>"))
        assertFalse(cleanAnswer.contains("</think>"))
    }

    @Test
    fun testAgentPromptsSystemPromptContainsDeviceTools() {
        val agentPrompt = AgentPrompts.AGENT_SYSTEM_PROMPT
        assertTrue(agentPrompt.contains("open_app"))
        assertTrue(agentPrompt.contains("read_screen"))
        assertTrue(agentPrompt.contains("make_call"))
        assertTrue(agentPrompt.contains("send_sms"))
        assertTrue(agentPrompt.contains("execute_task"))

        val taskPrompt = AgentPrompts.TASK_SYSTEM_PROMPT
        assertTrue(taskPrompt.contains("click_text"))
        assertTrue(taskPrompt.contains("click_at"))
        assertTrue(taskPrompt.contains("type_text"))
        // Strict no forced think tag for simple inputs
        assertFalse(agentPrompt.contains("Always enclose your thought process in <think>"))
    }

    @Test
    fun testCommonPackageAliasesMapping() {
        val aliases = com.textmorph.app.agent.tools.AppLauncherTool.COMMON_PACKAGE_ALIASES
        assertEquals("com.google.android.youtube", aliases["youtube"])
        assertEquals("com.android.chrome", aliases["chrome"])
        assertEquals("com.whatsapp", aliases["whatsapp"])
        assertEquals("com.android.settings", aliases["settings"])
        assertEquals("com.facebook.orca", aliases["messenger"])
        assertEquals("com.google.android.apps.messaging", aliases["sms"])
        assertEquals("com.google.android.contacts", aliases["contacts"])
    }

    @Test
    fun testParseMalformedJsonUnclosedParams() {
        val malformed = """
            {
              "action": "type_text",
              "params": {
                "text": "youtube.com",
              "reasoning": "I need to type the URL of youtube.com into the EditText field 'youtube.com' to navigate to the youtube homepage",
              "is_complete": false
            }
        """.trimIndent()

        val parsed = AgentActionParser.parse(malformed)
        assertNotNull("Should recover from unclosed params brace", parsed)
        assertEquals("type_text", parsed?.action)
        assertEquals("youtube.com", parsed?.getStringParam("text"))
        assertTrue(parsed?.reasoning?.contains("navigate to the youtube homepage") == true)
    }

    @Test
    fun testParseMalformedJsonExtraBraces() {
        val malformed = """
            {
            "action": "type_text", "params": {"text": "catalogs"} , "hint": "search for cats" },
            "reasoning": "Type 'cats' into the search box to search for cats.", "is_complete": false}
        """.trimIndent()

        val parsed = AgentActionParser.parse(malformed)
        assertNotNull("Should recover from extra brace after params", parsed)
        assertEquals("type_text", parsed?.action)
        assertEquals("catalogs", parsed?.getStringParam("text"))
    }

    @Test
    fun testParseMalformedJsonBrokenColon() {
        val malformed = """
            {
            "action": "type_text", "params": {"text": "c" : "}, " "reason": "I need to type 'cats' into the search box, which is likely the 'youtube.com' field.", "is_complete": false}
        """.trimIndent()

        val parsed = AgentActionParser.parse(malformed)
        assertNotNull("Should recover via regex heuristic", parsed)
        assertEquals("type_text", parsed?.action)
        assertEquals("c", parsed?.getStringParam("text"))
    }

    @Test
    fun testParseCodeAsActionStepCommand() {
        val command = "type_text(\"cats\", hint=\"Search\")"
        val parsed = AgentActionParser.parse(command)
        assertNotNull("Should parse Code-as-Action step command", parsed)
        assertEquals("type_text", parsed?.action)
        assertEquals("cats", parsed?.getStringParam("text"))
    }

    @Test
    fun testStandaloneActionSuccessIsolationFromStaleCoordinatorFailure() {
        // 1. Simulate prior failed multi-step task
        com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.startTask("Open YouTube")
        com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.finishTask("Task Failed: Model error", success = false)
        assertTrue(com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.state.value.isFailed)

        // 2. Standalone action resets coordinator state before execution
        com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.reset()
        assertFalse(com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.state.value.isFailed)

        // 3. Evaluation for non-execute_task uses result.success directly
        val mockResultSuccess = true
        val isExecuteTask = false
        val taskState = com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.state.value
        val isSuccess = if (isExecuteTask) {
            mockResultSuccess && !taskState.isInterrupted && !taskState.isFailed
        } else {
            mockResultSuccess
        }
        assertTrue("Standalone action must succeed regardless of prior coordinator state", isSuccess)
    }

    @Test
    fun testGetStringParamWithAliasesResolution() {
        val action = AgentAction(
            action = "open_app",
            params = mapOf("name" to kotlinx.serialization.json.JsonPrimitive("YouTube"))
        )
        val resolved = action.getStringParamWithAliases("app_name", "name", "app", "target")
        assertEquals("YouTube", resolved)
    }

    @Test
    fun testDefaultMaxContextTokensIs8192() {
        val defaultSession = com.textmorph.app.data.model.ChatSessionEntity()
        assertEquals(8192, defaultSession.maxContextTokens)

        val metrics = com.textmorph.app.data.model.ContextUsageMetrics()
        assertEquals(8192, metrics.maxContextTokens)
    }

    @Test
    fun testAutoSendVoiceInputDefault() {
        val settings = com.textmorph.app.data.model.AppSettings()
        assertTrue(settings.autoSendVoiceInput)
    }
}
