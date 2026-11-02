package com.textmorph.app

import com.textmorph.app.agent.orchestration.DeepSearchCoordinator
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.agent.router.ChatCommandRouter
import com.textmorph.app.agent.router.CommandRouteResult
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.CloudApiPreset
import com.textmorph.app.ui.chat.components.ChatToolCallExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSearchCoordinatorAndPresetsUnitTest {

    @Test
    fun testDeepSearchCoordinator_isAmbiguousQuery() {
        // Underspecified queries that require human-in-the-loop clarification
        assertTrue(DeepSearchCoordinator.isAmbiguousQuery("faycal rafa"))
        assertTrue(DeepSearchCoordinator.isAmbiguousQuery("albert einstein"))
        assertTrue(DeepSearchCoordinator.isAmbiguousQuery("quantum"))

        // Specified queries with domain keywords or details that can proceed directly
        assertFalse(DeepSearchCoordinator.isAmbiguousQuery("faycal rafa AI software engineering"))
        assertFalse(DeepSearchCoordinator.isAmbiguousQuery("quantum computing algorithms overview"))
        assertFalse(DeepSearchCoordinator.isAmbiguousQuery("faycal rafa: biography and research"))
    }

    @Test
    fun testDeepSearchCoordinator_buildClarificationApprovalCall() {
        val call = DeepSearchCoordinator.buildClarificationApprovalCall("faycal rafa")
        assertTrue(call.startsWith("request_user_approval("))
        assertTrue(call.contains("faycal rafa"))
        assertTrue(call.contains("AI & Software Engineering"))

        // Verify ChatToolCallExtractor parses this approval call correctly
        val approval = ChatToolCallExtractor.extractApproval(call)
        assertNotNull(approval)
        assertTrue(approval?.question?.contains("faycal rafa") == true)
        assertTrue(approval?.options?.size ?: 0 >= 2)
    }

    @Test
    fun testCodeActionParser_conversationalAndQuestionGuardrails() {
        // Questions mentioning commands must NOT trigger execution
        assertNull(CodeActionParser.parse("Would you like me to run search_web(\"Paris\")?"))
        assertNull(CodeActionParser.parse("Should I open_app(\"Calendar\")?"))
        assertNull(CodeActionParser.parse("Do you want me to type_text(\"hello\")?"))
        assertNull(CodeActionParser.parse("Can I click_text(\"Submit\")?"))
        assertNull(CodeActionParser.parse("What would happen if I run click_at(x=100, y=200)?"))

        // Explanatory prose mentions must NOT trigger execution
        assertNull(CodeActionParser.parse("You can use search_web(\"query\") to find live data."))
        assertNull(CodeActionParser.parse("For example: open_app(\"Settings\")."))
        assertNull(CodeActionParser.parse("Try using click_text(\"Login\") to proceed."))

        // Genuine direct executable actions MUST trigger
        assertNotNull(CodeActionParser.parse("search_web(\"current weather\")"))
        assertNotNull(CodeActionParser.parse("```\nopen_app(\"Settings\")\n```"))
        assertNotNull(CodeActionParser.parse("I'll look that up:\nsearch_web(\"Tokyo\")"))
        assertNotNull(CodeActionParser.parse("- Flight Deal: search_web(\"cheap flights\")"))
    }

    @Test
    fun testCodeActionParser_multiDialectAndControlTokenEnvelopes() {
        // Gemma-4 native tool-call envelopes
        val gemmaCall = CodeActionParser.parse("<|tool_call>call:search_web(query=\"latest quarterly earnings report for Apple\")")
        assertNotNull(gemmaCall)
        org.junit.Assert.assertEquals("search_web", gemmaCall?.action)
        assertTrue(gemmaCall?.params?.get("query").toString().contains("Apple"))

        val gemmaCallClosed = CodeActionParser.parse("<|tool_call>call:search_web(query=\"Android 15 features\")<|tool_call_end>")
        assertNotNull(gemmaCallClosed)
        org.junit.Assert.assertEquals("search_web", gemmaCallClosed?.action)

        // Qwen XML tool-call envelope
        val qwenCall = CodeActionParser.parse("<tool_call>\nsearch_web(query=\"Kotlin 2.1\")\n</tool_call>")
        assertNotNull(qwenCall)
        org.junit.Assert.assertEquals("search_web", qwenCall?.action)

        // Llama python tag envelope
        val llamaCall = CodeActionParser.parse("<|python_tag|>open_app(\"Settings\")<|eot_id|>")
        assertNotNull(llamaCall)
        org.junit.Assert.assertEquals("open_app", llamaCall?.action)

        // Action / call prefix formats
        val actionPrefix = CodeActionParser.parse("Action: click_text(\"Submit\")")
        assertNotNull(actionPrefix)
        org.junit.Assert.assertEquals("click_text", actionPrefix?.action)

        val callPrefix = CodeActionParser.parse("call:open_app(\"YouTube\")")
        assertNotNull(callPrefix)
        org.junit.Assert.assertEquals("open_app", callPrefix?.action)

        // stripActionPayload clean UI checks
        val stripped1 = CodeActionParser.stripActionPayload("<|tool_call>call:search_web(query=\"Apple\")")
        org.junit.Assert.assertEquals("", stripped1)

        val stripped2 = CodeActionParser.stripActionPayload("I'll look that up:\n<|tool_call>call:search_web(query=\"Apple\")")
        assertTrue(stripped2.contains("I'll look that up:"))
        assertFalse(stripped2.contains("<|tool_call>"))

        // hasCompleteAction checks
        assertTrue(CodeActionParser.hasCompleteAction("<|tool_call>call:search_web(query=\"Apple\")"))
        assertFalse(CodeActionParser.hasCompleteAction("<|tool_call>call:search_web(query="))
    }

    @Test
    fun testChatCommandRouter_questionsAboutCommands() {
        // Questions about slash commands must NOT trigger execution
        val q1 = ChatCommandRouter.route("What does /plan do?")
        assertTrue(q1 is CommandRouteResult.StandardTurn)
        assertFalse(q1 is CommandRouteResult.PlanCommand)

        val q2 = ChatCommandRouter.route("/plan how does this work?")
        assertTrue(q2 is CommandRouteResult.StandardTurn)
        assertFalse(q2 is CommandRouteResult.PlanCommand)

        val q3 = ChatCommandRouter.route("Can you explain /benchmark?")
        assertTrue(q3 is CommandRouteResult.StandardTurn)
        assertFalse(q3 is CommandRouteResult.BenchmarkCommand)

        // Real commands must trigger execution
        val c1 = ChatCommandRouter.route("/plan Refactor authentication architecture") as CommandRouteResult.PlanCommand
        assertEquals("Refactor authentication architecture", c1.goal)

        val c2 = ChatCommandRouter.route("/deepsearch --workers 2 On-device SLM inference") as CommandRouteResult.DeepSearchCommand
        assertEquals("On-device SLM inference", c2.query)
        assertEquals(2, c2.workersOverride)
    }

    @Test
    fun testCloudApiPreset_modelDefaults() {
        val settings = AppSettings()
        assertTrue(settings.apiPresets.isNotEmpty())
        val names = settings.apiPresets.map { it.name }
        assertTrue(names.contains("OpenAI"))
        assertTrue(names.contains("OpenRouter"))
        assertTrue(names.contains("Groq"))
        assertTrue(names.contains("Ollama Local"))

        val customPreset = CloudApiPreset(
            name = "My Local LM Studio",
            baseUrl = "http://192.168.1.100:1234/v1",
            apiKey = "lm-studio",
            defaultModelId = "meta-llama-3.1-8b-instruct"
        )
        assertEquals("http://192.168.1.100:1234/v1", customPreset.baseUrl)
        assertEquals("meta-llama-3.1-8b-instruct", customPreset.defaultModelId)
    }

    @Test
    fun testCloudApiPreset_tokenPreservationAcrossPresets() {
        var settings = AppSettings()
        // Simulate user saving token for OpenAI preset
        val openAiPreset = settings.apiPresets.first { it.id == "openai" }
        val updatedOpenAi = openAiPreset.copy(apiKey = "sk-openai-secret-token")
        val presetsAfterOpenAi = settings.apiPresets.map { if (it.id == "openai") updatedOpenAi else it }
        settings = settings.copy(apiPresets = presetsAfterOpenAi, cloudApiKey = "sk-openai-secret-token", activePresetId = "openai")

        // User switches to Groq preset
        val groqPreset = settings.apiPresets.first { it.id == "groq" }
        val updatedGroq = groqPreset.copy(apiKey = "gsk-groq-secret-token")
        val presetsAfterGroq = settings.apiPresets.map { if (it.id == "groq") updatedGroq else it }
        settings = settings.copy(apiPresets = presetsAfterGroq, cloudApiKey = "gsk-groq-secret-token", activePresetId = "groq")

        // Switch back to OpenAI preset - verify OpenAI token was preserved and NOT overwritten!
        val preservedOpenAi = settings.apiPresets.first { it.id == "openai" }
        assertEquals("sk-openai-secret-token", preservedOpenAi.apiKey)

        // Switch back to Groq preset - verify Groq token was preserved!
        val preservedGroq = settings.apiPresets.first { it.id == "groq" }
        assertEquals("gsk-groq-secret-token", preservedGroq.apiKey)
    }
}
