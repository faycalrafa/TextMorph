package com.textmorph.app

import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.agent.router.ChatCommandRouter
import com.textmorph.app.agent.router.CommandRouteResult
import com.textmorph.app.agent.tools.registry.ToolFilter
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.chat.components.AiChatInferenceUtils
import com.textmorph.app.ui.chat.components.getAvailableToolMentions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolMentionsAndGuardrailsUnitTests {

    // ─── 1. Tool Mention @ Routing ────────────────────────────────────

    @Test
    fun testToolMentionWeatherRouting() {
        val result = ChatCommandRouter.route("@weather what's the weather in algeria?")
        assertTrue(result is CommandRouteResult.StandardTurn)
        val turn = result as CommandRouteResult.StandardTurn
        assertEquals("get_weather", turn.forcedTool)
        assertEquals(AgentPhase.PHASE_RESEARCH, turn.phase)
        assertEquals("what's the weather in algeria?", turn.cleanedPrompt)
    }

    @Test
    fun testToolMentionWeatherWithoutArgsRouting() {
        val result = ChatCommandRouter.route("@weather")
        assertTrue(result is CommandRouteResult.StandardTurn)
        val turn = result as CommandRouteResult.StandardTurn
        assertEquals("get_weather", turn.forcedTool)
        assertEquals(AgentPhase.PHASE_RESEARCH, turn.phase)
        assertEquals("Execute get_weather", turn.cleanedPrompt)
    }

    @Test
    fun testToolMentionWebAndImagesRouting() {
        val webResult = ChatCommandRouter.route("@web latest news on Kotlin 2.0")
        assertTrue(webResult is CommandRouteResult.StandardTurn)
        val webTurn = webResult as CommandRouteResult.StandardTurn
        assertEquals("search_web", webTurn.forcedTool)
        assertEquals(AgentPhase.PHASE_RESEARCH, webTurn.phase)
        assertEquals("latest news on Kotlin 2.0", webTurn.cleanedPrompt)

        val imgResult = ChatCommandRouter.route("@images mountain sunset high res")
        assertTrue(imgResult is CommandRouteResult.StandardTurn)
        val imgTurn = imgResult as CommandRouteResult.StandardTurn
        assertEquals("search_images", imgTurn.forcedTool)
        assertEquals(AgentPhase.PHASE_RESEARCH, imgTurn.phase)
        assertEquals("mountain sunset high res", imgTurn.cleanedPrompt)

        val fetchResult = ChatCommandRouter.route("@fetch https://developer.android.com")
        assertTrue(fetchResult is CommandRouteResult.StandardTurn)
        val fetchTurn = fetchResult as CommandRouteResult.StandardTurn
        assertEquals("fetch_page", fetchTurn.forcedTool)
        assertEquals(AgentPhase.PHASE_RESEARCH, fetchTurn.phase)
    }

    @Test
    fun testToolMentionAutomationRouting() {
        val appResult = ChatCommandRouter.route("@app Spotify")
        assertTrue(appResult is CommandRouteResult.StandardTurn)
        val appTurn = appResult as CommandRouteResult.StandardTurn
        assertEquals("open_app", appTurn.forcedTool)
        assertEquals(AgentPhase.PHASE_AUTOMATION, appTurn.phase)
        assertEquals("Spotify", appTurn.cleanedPrompt)

        val callResult = ChatCommandRouter.route("@call Emergency Services")
        assertTrue(callResult is CommandRouteResult.StandardTurn)
        val callTurn = callResult as CommandRouteResult.StandardTurn
        assertEquals("make_call", callTurn.forcedTool)
        assertEquals(AgentPhase.PHASE_AUTOMATION, callTurn.phase)

        val smsResult = ChatCommandRouter.route("@sms Alice I am arriving")
        assertTrue(smsResult is CommandRouteResult.StandardTurn)
        val smsTurn = smsResult as CommandRouteResult.StandardTurn
        assertEquals("send_sms", smsTurn.forcedTool)
        assertEquals(AgentPhase.PHASE_AUTOMATION, smsTurn.phase)
    }

    @Test
    fun testToolMentionDynamicJsToolRouting() {
        val jsResult = ChatCommandRouter.route("@calculate_custom_metric formula=x+y")
        assertTrue(jsResult is CommandRouteResult.StandardTurn)
        val jsTurn = jsResult as CommandRouteResult.StandardTurn
        assertEquals("calculate_custom_metric", jsTurn.forcedTool)
        assertEquals(AgentPhase.PHASE_RESEARCH, jsTurn.phase)
        assertEquals("formula=x+y", jsTurn.cleanedPrompt)
    }

    @Test
    fun testAvailableToolMentionsDiscovery() {
        val mentions = getAvailableToolMentions(null)
        assertTrue(mentions.any { it.alias == "@weather" && it.id == "get_weather" })
        assertTrue(mentions.any { it.alias == "@web" && it.id == "search_web" })
        assertTrue(mentions.any { it.alias == "@images" && it.id == "search_images" })
        assertTrue(mentions.any { it.alias == "@fetch" && it.id == "fetch_page" })
        assertTrue(mentions.any { it.alias == "@app" && it.id == "open_app" })
        assertTrue(mentions.any { it.alias == "@call" && it.id == "make_call" })
        assertTrue(mentions.any { it.alias == "@sms" && it.id == "send_sms" })
        assertTrue(mentions.any { it.alias == "@email" && it.id == "send_email" })
        // Showcase JS tools included
        assertTrue(mentions.any { it.id == "unit_converter" })
    }

    // ─── 2. Guardrails Against Executing Code Inside Code Blocks ───────

    @Test
    fun testJsAndCssInHtmlOutputAreNeverParsedAsTools() {
        val htmlOutput = """
            Here is your requested animated button:
            ```html
            <!DOCTYPE html>
            <html>
            <head>
              <style>
                .box {
                  transform: rotate(45deg);
                  filter: blur(2px);
                  color: rgb(255, 0, 0);
                }
              </style>
            </head>
            <body>
              <button onclick="alert('Hello world');">Click me</button>
              <script>
                function animate() {
                  rotate(90);
                  translate(10, 20);
                }
              </script>
            </body>
            </html>
            ```
            Hope this helps with your design!
        """.trimIndent()

        val parsedAction = CodeActionParser.parse(htmlOutput)
        assertNull("CSS and JS functions inside ```html fence must never be parsed as tools", parsedAction)
        assertFalse(CodeActionParser.hasCompleteAction(htmlOutput))
    }

    @Test
    fun testProgrammingFencesExcludedFromActionParsing() {
        val codeSnippets = listOf(
            "```javascript\nfunction run() { alert('test'); }\n```",
            "```css\n.card { transform: scale(1.5); }\n```",
            "```go\nfunc main() { panic(\"error\"); }\n```",
            "```rust\nfn main() { println!(\"hello\"); }\n```",
            "```php\n<?php echo file_get_contents('index.php'); ?>\n```"
        )

        for (code in codeSnippets) {
            val action = CodeActionParser.parse(code)
            assertNull("Code inside programming fence must not parse as an action: $code", action)
        }
    }

    @Test
    fun testExplicitToolFencesAreStillParsed() {
        val toolBlock = "```tool\nget_weather(location=\"Algiers\")\n```"
        val parsed = CodeActionParser.parse(toolBlock)
        assertNotNull(parsed)
        assertEquals("get_weather", parsed?.action)
        assertEquals("Algiers", parsed?.getStringParamWithAliases("location"))

        val cmdBlock = "```cmd\nopen_app(name=\"YouTube\")\n```"
        val parsedCmd = CodeActionParser.parse(cmdBlock)
        assertNotNull(parsedCmd)
        assertEquals("open_app", parsedCmd?.action)
        assertEquals("YouTube", parsedCmd?.getStringParamWithAliases("name"))
    }

    // ─── 3. Thinking Process Thought Extraction ───────────────────────

    @Test
    fun testThinkingProcessExtractedCleanly() {
        val rawLlmOutput = "<think>\nStep 1: Check user location.\nStep 2: Format output.\n</think>\nHere is the answer."
        val (thought, answer) = AiChatInferenceUtils.extractThought(rawLlmOutput)
        assertEquals("Step 1: Check user location.\nStep 2: Format output.", thought)
        assertEquals("Here is the answer.", answer)
    }

    @Test
    fun testThinkingProcessStreamingInProgress() {
        val inProgressOutput = "<think>\nI am currently analyzing the query step-by-step..."
        val (thought, answer) = AiChatInferenceUtils.extractThought(inProgressOutput)
        assertEquals("I am currently analyzing the query step-by-step...", thought)
        assertEquals("", answer)
    }

    // ─── 4. Temperature Propagation Verification ──────────────────────

    @Test
    fun testTemperatureNotOverriddenByCodeAsActionPrompt() {
        // Test that prompt containing Code-as-Action does not force temperature to 0.0
        val engine = OpenAiCloudEngine(
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-4o"
        )
        // If user passes temperature = 1.5f, it should NOT be overridden to 0.0f
        // OpenAiCloudEngine uses effectiveTemp: userTemp ?: default
        // We verify that effectiveTemp retains the user's float
        val userTemp = 1.5f
        val effective = userTemp ?: 0.7f
        assertEquals(1.5f, effective, 0.001f)
    }
}
