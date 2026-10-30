package com.textmorph.app

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.tools.js.JsToolEntity
import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolDefinition
import com.textmorph.app.agent.tools.registry.AgentToolRegistry
import com.textmorph.app.agent.tools.registry.ToolSource
import com.textmorph.app.ui.chat.components.ChatToolExecutionHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsToolArchitectureUnitTest {

    @Test
    fun testJsToolEntity_parseFromScriptExtractsMetadata() {
        val scriptWithJSDoc = """
            /**
             * @tool currency_convert
             * @name Currency Converter
             * @description Converts foreign currency amounts at specified exchange rates.
             * @signature currency_convert(amount=100, rate=1.08)
             * @param {number} amount Base currency amount
             * @param {number} rate Exchange rate multiplier
             */
            function run(params) {
              const a = parseFloat(params.amount) || 0;
              const r = parseFloat(params.rate) || 1.0;
              return "Converted: " + (a * r).toFixed(2);
            }
        """.trimIndent()

        val tool = JsToolEntity.parseFromScript(scriptWithJSDoc, fallbackId = "fallback")
        assertEquals("currency_convert", tool.id)
        assertEquals("Currency Converter", tool.name)
        assertEquals("Converts foreign currency amounts at specified exchange rates.", tool.description)
        assertEquals("currency_convert(amount=100, rate=1.08)", tool.codeSignature)
        assertEquals(2, tool.parameters.size)
        assertEquals("amount", tool.parameters[0].name)
        assertEquals("rate", tool.parameters[1].name)
    }

    @Test
    fun testCodeActionParser_parsesWebAndJsTools() {
        // 1. search_web
        val searchAction = CodeActionParser.parse("search_web(\"Kotlin 2.0 release\")")
        assertNotNull(searchAction)
        assertEquals(AgentAction.ACTION_SEARCH_WEB, searchAction?.action)
        assertEquals("Kotlin 2.0 release", searchAction?.getStringParam("query"))

        // 2. fetch_page
        val fetchAction = CodeActionParser.parse("fetch_page(\"https://news.ycombinator.com\", 2500)")
        assertNotNull(fetchAction)
        assertEquals(AgentAction.ACTION_FETCH_PAGE, fetchAction?.action)
        assertEquals("https://news.ycombinator.com", fetchAction?.getStringParam("url"))
        assertEquals(2500, fetchAction?.getIntParam("char_limit"))

        // 3. create_js_tool
        val createToolAction = CodeActionParser.parse(
            "create_js_tool(\"discount_calc\", \"Discount Tool\", \"Computes discount\", \"discount_calc(p=100)\", \"function run() { return 80; }\")"
        )
        assertNotNull(createToolAction)
        assertEquals(AgentAction.ACTION_CREATE_JS_TOOL, createToolAction?.action)
        assertEquals("discount_calc", createToolAction?.getStringParam("id"))
    }

    @Test
    fun testAgentPrompts_resolveSystemPromptReplacesTags() {
        val inApp = listOf(
            AgentToolDefinition(
                id = "search_web",
                name = "Search Web",
                category = AgentToolCategory.APPS_AND_WEB,
                description = "Searches web via DuckDuckGo",
                codeSignature = "search_web(\"query\")"
            )
        )
        val jsTools = listOf(
            AgentToolDefinition(
                id = "get_weather",
                name = "Live Weather Forecast",
                category = AgentToolCategory.APPS_AND_WEB,
                description = "Checks current weather",
                codeSignature = "get_weather(location=\"Paris\")",
                source = ToolSource.IMPORTED_JS
            )
        )

        val template = "Welcome to TextMorph.\n[in app tools]\n\n[js tools]\nEnjoy!"
        val resolved = AgentPrompts.resolveSystemPrompt(template, inApp, jsTools)

        assertFalse(resolved.contains("[in app tools]"))
        assertFalse(resolved.contains("[js tools]"))
        assertTrue(resolved.contains("search_web(\"query\") : Searches web via DuckDuckGo"))
        assertTrue(resolved.contains("get_weather(location=\"Paris\") : Checks current weather"))
    }

    @Test
    fun testAgentToolRegistry_containsBuiltInWebAndInteractiveTools() {
        val tools = AgentToolRegistry.getInAppTools()
        val ids = tools.map { it.id }

        assertTrue(ids.contains(AgentAction.ACTION_SEARCH_WEB))
        assertTrue(ids.contains(AgentAction.ACTION_FETCH_PAGE))
        assertTrue(ids.contains(AgentAction.ACTION_REQUEST_USER_APPROVAL))
        assertTrue(ids.contains(AgentAction.ACTION_SUGGEST_NEXT_ACTIONS))
        assertTrue(ids.contains(AgentAction.ACTION_PRESENT_INTERACTIVE_VIEW))
        assertTrue(ids.contains(AgentAction.ACTION_OPEN_APP))
        assertTrue(ids.contains(AgentAction.ACTION_CLICK_TEXT))
    }

    @Test
    fun testChatToolExecutionHandler_detectsToolInvocation() {
        val rawSearch = "I will look that up for you.\nsearch_web(\"current weather in Tokyo\")"
        val detected = ChatToolExecutionHandler.detectToolInvocation(rawSearch, null)
        assertNotNull(detected)
        assertEquals(AgentAction.ACTION_SEARCH_WEB, detected?.action)
        assertEquals("current weather in Tokyo", detected?.getStringParam("query"))

        val observation = ChatToolExecutionHandler.formatObservationPrompt(detected!!, "Sunny, 22°C")
        assertTrue(observation.contains("[Tool Result for search_web]"))
        assertTrue(observation.contains("Sunny, 22°C"))
        assertTrue(observation.contains("suggest_next_actions"))
    }

    private fun assertFalse(condition: Boolean) {
        org.junit.Assert.assertFalse(condition)
    }
}
