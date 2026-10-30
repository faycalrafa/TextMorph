package com.textmorph.app

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.parser.GemmaToolCallParser
import com.textmorph.app.ui.chat.components.ChatToolCallExtractor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaToolCallParserUnitTest {

    @Test
    fun testGemmaNativeSearchWebToolCall() {
        val raw = "<|tool_call|>call:search_web{query:<|\"|>Android 17 newest features<|\"|>}<tool_call|>"
        val action = GemmaToolCallParser.parse(raw)

        assertNotNull(action)
        assertEquals(AgentAction.ACTION_SEARCH_WEB, action?.action)
        assertEquals("Android 17 newest features", action?.getStringParam("query"))
    }

    @Test
    fun testGemmaSearchWebWithoutCallPrefix() {
        val raw = "<|tool_call|>search_web{query:<|\"|>latest Android 17 features<|\"|>}<tool_call|>"
        val action = GemmaToolCallParser.parse(raw)

        assertNotNull(action)
        assertEquals(AgentAction.ACTION_SEARCH_WEB, action?.action)
        assertEquals("latest Android 17 features", action?.getStringParam("query"))
    }

    @Test
    fun testGemmaFetchPageWithCharLimit() {
        val raw = "<|tool_call|>call:fetch_page{url:<|\"|>https://developer.android.com<|\"|>,char_limit:5000}<tool_call|>"
        val action = GemmaToolCallParser.parse(raw)

        assertNotNull(action)
        assertEquals(AgentAction.ACTION_FETCH_PAGE, action?.action)
        assertEquals("https://developer.android.com", action?.getStringParam("url"))
        assertEquals(5000, action?.getIntParam("char_limit"))
    }

    @Test
    fun testGemmaSuggestNextActionsWithArray() {
        val raw = "<|tool_call|>call:suggest_next_actions{suggestions:[<|\"|>Tell me about privacy<|\"|>,<|\"|>Show release date<|\"|>]}<tool_call|>"
        val action = GemmaToolCallParser.parse(raw)

        assertNotNull(action)
        assertEquals("suggest_next_actions", action?.action)
        val arr = action?.params?.get("suggestions") as? JsonArray
        assertNotNull(arr)
        assertEquals(2, arr?.size)
        assertEquals("Tell me about privacy", (arr?.get(0) as? JsonPrimitive)?.content)
        assertEquals("Show release date", (arr?.get(1) as? JsonPrimitive)?.content)
    }

    @Test
    fun testGemmaRequestUserApprovalExtraction() {
        val raw = "I found multiple variants.<|tool_call|>call:request_user_approval{question:<|\"|>Which version?<|\"|>,options:[<|\"|>Beta 1<|\"|>,<|\"|>Beta 2<|\"|>]}<tool_call|>"
        val approval = ChatToolCallExtractor.extractApproval(raw)

        assertNotNull(approval)
        assertEquals("Which version?", approval?.question)
        assertEquals(listOf("Beta 1", "Beta 2"), approval?.options)
        assertEquals("I found multiple variants.", approval?.preText)
    }

    @Test
    fun testGemmaFollowUpSuggestionsExtraction() {
        val raw = "Here is the summary of Android 17 features.<|tool_call|>call:suggest_next_actions{suggestions:[<|\"|>Ask about APIs<|\"|>,<|\"|>Check compatibility<|\"|>]}<tool_call|>"
        val followUps = ChatToolCallExtractor.extractFollowUps(raw)

        assertNotNull(followUps)
        assertEquals(listOf("Ask about APIs", "Check compatibility"), followUps?.suggestions)
        assertEquals("Here is the summary of Android 17 features.", followUps?.remainingContent)
    }

    @Test
    fun testXmlToolCallParsing() {
        val raw = """
            <tool_call>
            {"name": "search_web", "arguments": {"query": "Kotlin Multiplatform"}}
            </tool_call>
        """.trimIndent()
        val action = GemmaToolCallParser.parse(raw)

        assertNotNull(action)
        assertEquals(AgentAction.ACTION_SEARCH_WEB, action?.action)
        assertEquals("Kotlin Multiplatform", action?.getStringParam("query"))
    }

    @Test
    fun testAgentActionParserPrioritizesGemmaFormat() {
        val raw = "<|tool_call|>call:click_text{text:<|\"|>Settings<|\"|>}<tool_call|>"
        val action = AgentActionParser.parseAction(raw)

        assertNotNull(action)
        assertEquals(AgentAction.ACTION_CLICK_TEXT, action?.action)
        assertEquals("Settings", action?.getStringParam("text"))
    }

    @Test
    fun testStripGemmaToolCallCleansOutput() {
        val raw = "Thinking about your request...<|tool_call|>call:search_web{query:<|\"|>Android 17<|\"|>}<tool_call|>"
        val stripped = AgentActionParser.stripActionPayload(raw)

        assertEquals("Thinking about your request...", stripped)
    }

    @Test
    fun testGemmaDeviceActions() {
        val openApp = GemmaToolCallParser.parse("<|tool_call|>call:open_app{app_name:<|\"|>Chrome<|\"|>}<tool_call|>")
        assertEquals(AgentAction.ACTION_OPEN_APP, openApp?.action)
        assertEquals("Chrome", openApp?.getStringParam("app_name"))

        val execTask = GemmaToolCallParser.parse("<|tool_call|>call:execute_task{goal:<|\"|>Search for shoes<|\"|>}<tool_call|>")
        assertEquals(AgentAction.ACTION_EXECUTE_TASK, execTask?.action)
        assertEquals("Search for shoes", execTask?.getStringParam("goal"))
    }
}
