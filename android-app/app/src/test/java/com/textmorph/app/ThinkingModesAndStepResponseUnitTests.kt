package com.textmorph.app

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.ThinkingMode
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.chat.components.AiChatInferenceUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingModesAndStepResponseUnitTests {

    @Test
    fun testThinkingModeBudgetCaps() {
        assertEquals(0, ThinkingMode.LOW.maxBudgetTokens)
        assertEquals(800, ThinkingMode.MEDIUM.maxBudgetTokens)
        assertEquals(1500, ThinkingMode.HIGH.maxBudgetTokens)

        assertTrue(ThinkingMode.HIGH.maxBudgetTokens <= 1500)
    }

    @Test
    fun testAppSettingsThinkingDefaultsAndBackwardCompatibility() {
        val defaultSettings = AppSettings()
        assertEquals(ThinkingMode.LOW, defaultSettings.thinkingMode)
        assertFalse(defaultSettings.enableThinking)
        assertFalse(defaultSettings.isThinkingActive)
        assertEquals(0, defaultSettings.effectiveThinkingBudget)

        // Test Medium Mode
        val mediumSettings = defaultSettings.copy(thinkingMode = ThinkingMode.MEDIUM)
        assertTrue(mediumSettings.isThinkingActive)
        assertEquals(800, mediumSettings.effectiveThinkingBudget)

        // Test High Mode capped at 1500 tokens
        val highSettings = defaultSettings.copy(thinkingMode = ThinkingMode.HIGH)
        assertTrue(highSettings.isThinkingActive)
        assertEquals(1500, highSettings.effectiveThinkingBudget)

        // Backward compatibility: old settings where only enableThinking = true was saved
        val legacySettings = defaultSettings.copy(enableThinking = true, thinkingMode = ThinkingMode.LOW)
        assertTrue(legacySettings.isThinkingActive)
        assertEquals(800, legacySettings.effectiveThinkingBudget)
    }

    @Test
    fun testCodeActionParserExtractsPrecedingConversationalIntent() {
        val modelOutput = "I'm checking the current weather and 3-day forecast in Tokyo.\n```tool\nweb_search(query=\"tokyo weather 3 days\")\n```"
        val action = CodeActionParser.parse(modelOutput)

        assertNotNull(action)
        assertEquals("search_web", action?.action)
        assertEquals("tokyo weather 3 days", action?.getStringParam("query"))
        assertEquals("I'm checking the current weather and 3-day forecast in Tokyo.", action?.reasoning)
    }

    @Test
    fun testAiChatInferenceUtilsExtractThought() {
        val outputWithThought = "<think>\nAnalyzing weather query for Tokyo.\nChecking forecasts.\n</think>\nHere is the weather in Tokyo: 18°C and partly cloudy."
        val (thought, answer) = AiChatInferenceUtils.extractThought(outputWithThought)

        assertNotNull(thought)
        assertTrue(thought!!.contains("Analyzing weather query for Tokyo"))
        assertEquals("Here is the weather in Tokyo: 18°C and partly cloudy.", answer.trim())

        // Test no thinking output
        val directOutput = "Here is the direct answer without thinking."
        val (noThought, directAnswer) = AiChatInferenceUtils.extractThought(directOutput)
        assertNull(noThought)
        assertEquals("Here is the direct answer without thinking.", directAnswer)
    }

    @Test
    fun testCreateStepTraceAttachesStepResponseAndThought() {
        val action = AgentAction(
            action = "web_search",
            params = mapOf("query" to kotlinx.serialization.json.JsonPrimitive("tokyo weather")),
            response = "I will search the web for Tokyo weather.",
            reasoning = "Looking up the Tokyo forecast."
        )

        val trace = AiChatInferenceUtils.createStepTrace(
            stepIndex = 1,
            action = action,
            stepResponse = action.reasoning,
            thought = "Thinking about user request"
        )

        assertEquals(1, trace.stepIndex)
        assertEquals("web_search", trace.toolName)
        assertEquals("Looking up the Tokyo forecast.", trace.stepResponse)
        assertEquals("Thinking about user request", trace.thought)
        assertTrue(trace.hasExplanation)
    }

    @Test
    fun testAgentStepTraceSerializationRoundTrip() {
        val traces = listOf(
            AgentStepTrace(
                stepIndex = 1,
                toolName = "web_search",
                inputQuery = "query=tokyo weather",
                status = "success",
                durationMs = 450L,
                outputSnippet = "18°C, Partly Cloudy",
                stepResponse = "Searching the web for Tokyo weather...",
                thought = "Need accurate forecast data"
            )
        )

        val serialized = ChatMessageEntity.serializeStepTraces(traces)
        assertTrue(serialized.contains("Searching the web for Tokyo weather..."))
        assertTrue(serialized.contains("Need accurate forecast data"))

        val message = ChatMessageEntity(
            sessionId = "test-session",
            role = "assistant",
            content = "Here is Tokyo's weather.",
            stepTracesJson = serialized
        )

        val deserialized = message.getStepTraces()
        assertEquals(1, deserialized.size)
        assertEquals("Searching the web for Tokyo weather...", deserialized[0].stepResponse)
        assertEquals("Need accurate forecast data", deserialized[0].thought)
        assertEquals("18°C, Partly Cloudy", deserialized[0].outputSnippet)
        assertEquals("success", deserialized[0].status)
    }

    @Test
    fun testOpenAiCloudEngineThinkingModeConfiguration() {
        val engine = OpenAiCloudEngine()
        engine.updateThinkingMode(ThinkingMode.HIGH)
        // Verify engine updates without exceptions
        engine.updateConfig("https://api.openai.com/v1", "test-key", "o3-mini")
        assertEquals(128000, engine.maxContextTokens)
    }
}
