package com.textmorph.app

import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.service.overlay.components.ToolbarGenerationDelegate
import com.textmorph.app.ui.chat.components.prepareSandboxHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedChatAndAgentUnitTest {

    @Test
    fun testAppSettingsFuseChatAndAgentDefaultsToTrue() {
        val settings = AppSettings()
        assertTrue("fuseChatAndAgent must default to true", settings.fuseChatAndAgent)
    }

    @Test
    fun testUnifiedSystemPromptContainsToolsAndArtifactGuidance() {
        val prompt = AgentPrompts.getUnifiedSystemPrompt()
        assertNotNull(prompt)
        assertTrue(prompt.contains("TextMorph"))
        assertTrue(prompt.contains("conversational intelligence"))
        assertTrue(prompt.contains("search_web"))
        assertTrue(prompt.contains("fetch_page"))
        assertTrue(prompt.contains("get_weather"))
        assertTrue(prompt.contains("present_interactive_view"))
        assertTrue(prompt.contains("suggest_next_actions"))
        assertTrue(prompt.contains("request_user_approval"))
        assertTrue(prompt.contains("viewport"))
        assertTrue(prompt.contains("canvas"))
        assertFalse(prompt.contains("\"action\": \"action_name\""))
    }

    @Test
    fun testCodeActionParserParsesBuiltinAndCustomJsTools() {
        // Test weather tool parsing
        val weatherAction = CodeActionParser.parse("get_weather(location=\"Paris\")")
        assertNotNull("Weather tool call should be parsed", weatherAction)
        assertEquals("get_weather", weatherAction?.action)
        assertEquals("Paris", weatherAction?.getStringParam("location"))

        // Test unit converter tool parsing
        val converterAction = CodeActionParser.parse("unit_converter(value=100.0, fromUnit=\"USD\", toUnit=\"EUR\")")
        assertNotNull("Unit converter tool call should be parsed", converterAction)
        assertEquals("unit_converter", converterAction?.action)
        assertEquals("100.0", converterAction?.getStringParam("value"))
        assertEquals("USD", converterAction?.getStringParam("fromUnit"))
        assertEquals("EUR", converterAction?.getStringParam("toUnit"))

        // Test custom JS tool with generic parameters
        val customToolAction = CodeActionParser.parse("calculate_custom_metric(param1=\"abc\", count=42)")
        assertNotNull("Custom JS tool should be parsed via generic fallback", customToolAction)
        assertEquals("calculate_custom_metric", customToolAction?.action)
        assertEquals("abc", customToolAction?.getStringParam("param1"))
        assertEquals("42", customToolAction?.getStringParam("count"))
    }

    @Test
    fun testToolbarTrimForInputField() {
        val rawGeneratedText = """
            The current weather in Tokyo is 22°C and clear.
            
            suggest_next_actions(suggestions=["Check weekend forecast", "View radar map"])
            
            Follow-up suggestions:
            1. Check tomorrow's forecast
            2. View humidity levels
            
            Would you like me to check tomorrow's forecast as well?
        """.trimIndent()

        val clean = ToolbarGenerationDelegate.trimForInputField(rawGeneratedText)
        assertEquals("The current weather in Tokyo is 22°C and clear.", clean)
        assertFalse(clean.contains("suggest_next_actions"))
        assertFalse(clean.contains("Follow-up suggestions"))
        assertFalse(clean.contains("Would you like me"))
    }

    @Test
    fun testToolbarTrimForInputFieldWithGemmaToolCallsAndApproval() {
        val rawText = """
            <|tool_call|>call:get_weather(location="London")<|call_end|>
            request_user_approval(message="Allow high precision location access?")
            London is currently 15°C with light rain.
        """.trimIndent()

        val clean = ToolbarGenerationDelegate.trimForInputField(rawText)
        assertEquals("London is currently 15°C with light rain.", clean)
        assertFalse(clean.contains("tool_call"))
        assertFalse(clean.contains("request_user_approval"))
    }

    @Test
    fun testPrepareSandboxHtmlInjectsViewportAndAutoCentering() {
        val snippet = "<canvas id=\"game\"></canvas><script>console.log('hi');</script>"
        val prepared = prepareSandboxHtml(snippet)

        assertTrue("Must inject viewport meta tag", prepared.contains("<meta name=\"viewport\""))
        assertTrue("Must inject centering display:flex styles", prepared.contains("display: flex"))
        assertTrue("Must inject canvas responsive auto-resize script", prepared.contains("c.width = window.innerWidth"))
    }

    @Test
    fun testPrepareSandboxHtmlPreservesExistingViewport() {
        val fullHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>body { background: #000; }</style>
            </head>
            <body><h1>Hello</h1></body>
            </html>
        """.trimIndent()

        val prepared = prepareSandboxHtml(fullHtml)
        assertTrue(prepared.contains("<h1>Hello</h1>"))
        assertTrue(prepared.contains("<head>"))
    }
}
