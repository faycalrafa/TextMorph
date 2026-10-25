package com.textmorph.app

import android.graphics.Rect
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.ChatSessionEntity
import com.textmorph.app.service.overlay.OverlayDisplayState
import com.textmorph.app.service.overlay.OverlayLayoutParamsHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for multi-session chat architecture, overlay state transitions,
 * thinking block extraction, and automation JSON action schemas.
 */
class ChatAndAutomationUpgradeUnitTests {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun testOverlayDisplayStateTransitions() {
        val states = OverlayDisplayState.values()
        assertEquals(4, states.size)
        assertTrue(states.contains(OverlayDisplayState.COLLAPSED_BUBBLE))
        assertTrue(states.contains(OverlayDisplayState.EXPANDED_TOOLBAR))
        assertTrue(states.contains(OverlayDisplayState.EXPANDED_CHAT))
        assertTrue(states.contains(OverlayDisplayState.AUTOMATING_ACTION))
    }

    @Test
    fun testThinkingBlockExtraction() {
        val rawOutput = "<think>\nUser wants a poem about winter.\nKeep it 4 lines.\n</think>\nSnow falls softly on the ground,\nSilence covers all around."

        assertTrue(rawOutput.contains("<think>"))
        assertTrue(rawOutput.contains("</think>"))

        val thinkStart = rawOutput.indexOf("<think>") + 7
        val thinkEnd = rawOutput.indexOf("</think>")
        val thinking = rawOutput.substring(thinkStart, thinkEnd).trim()
        val finalAnswer = rawOutput.substring(thinkEnd + 8).trim()

        assertEquals("User wants a poem about winter.\nKeep it 4 lines.", thinking)
        assertEquals("Snow falls softly on the ground,\nSilence covers all around.", finalAnswer)
    }

    @Test
    fun testIncompleteThinkingExtraction() {
        val streamingOutput = "<think>\nThinking in progress..."
        val hasCompleteThinking = streamingOutput.contains("</think>")
        assertFalse(hasCompleteThinking)

        val thinking = streamingOutput.substring(streamingOutput.indexOf("<think>") + 7).trim()
        assertEquals("Thinking in progress...", thinking)
    }

    @Test
    fun testHtmlSandboxStateGuard() {
        val streamingHtml = "<!DOCTYPE html><html><body><h1>Generating..."
        val completeHtml = "<!DOCTYPE html><html><body><h1>Done</h1></body></html>"

        fun shouldRenderSandbox(html: String, isDone: Boolean): Boolean {
            val containsHtml = html.contains("<!DOCTYPE html>", ignoreCase = true) || html.contains("<html", ignoreCase = true)
            return containsHtml && isDone
        }

        assertFalse(shouldRenderSandbox(streamingHtml, isDone = false))
        assertTrue(shouldRenderSandbox(completeHtml, isDone = true))
    }

    @Test
    fun testChatMessageEntitySerialization() {
        val entity = ChatMessageEntity(
            sessionId = "session_123",
            role = "user",
            content = "Open YouTube and search for cats",
            thoughtContent = null,
            actionPayloadJson = """{"action":"open_app","textParam":"YouTube"}""",
            actionStatus = "success"
        )

        val encoded = json.encodeToString(entity)
        val decoded = json.decodeFromString<ChatMessageEntity>(encoded)

        assertEquals("session_123", decoded.sessionId)
        assertEquals("user", decoded.role)
        assertEquals("Open YouTube and search for cats", decoded.content)
        assertNotNull(decoded.actionPayloadJson)
        assertEquals("success", decoded.actionStatus)
    }

    @Test
    fun testChatSessionEntityAutoTitleDerivation() {
        val prompt = "Translate this document to Spanish please"
        val derivedTitle = if (prompt.length > 30) prompt.take(28) + "…" else prompt

        assertEquals("Translate this document to s…".lowercase(), derivedTitle.lowercase())
    }

    @Test
    fun testDeviceActionJsonParsing() {
        val actionJson = """
            {
              "thought": "Need to click the search bar to enter query",
              "action": "click_at",
              "targetX": 540.0,
              "targetY": 120.0
            }
        """.trimIndent()

        val parsed = json.parseToJsonElement(actionJson).jsonObject
        val action = parsed["action"]?.jsonPrimitive?.content
        val targetX = parsed["targetX"]?.jsonPrimitive?.content?.toFloatOrNull()
        val targetY = parsed["targetY"]?.jsonPrimitive?.content?.toFloatOrNull()

        assertEquals("click_at", action)
        assertEquals(540.0f, targetX)
        assertEquals(120.0f, targetY)
    }

    @Test
    fun testFastShortcutAppDetection() {
        val goal1 = "Open YouTube and play music"
        val goal2 = "Launch Chrome and search weather"
        val goal3 = "Find something online"

        fun detectShortcut(goal: String): String? {
            val lower = goal.lowercase()
            return when {
                lower.contains("youtube") -> "com.google.android.youtube"
                lower.contains("chrome") -> "com.android.chrome"
                else -> null
            }
        }

        assertEquals("com.google.android.youtube", detectShortcut(goal1))
        assertEquals("com.android.chrome", detectShortcut(goal2))
        assertNull(detectShortcut(goal3))
    }

    @Test
    fun testDirectToChatConditionEvaluation() {
        fun shouldOpenChatDirectly(alwaysShow: Boolean, isKeyboardOpen: Boolean, hasInputText: Boolean): Boolean {
            return alwaysShow && !isKeyboardOpen && !hasInputText
        }

        // Always show active, no keyboard, no input -> DIRECT TO CHAT
        assertTrue(shouldOpenChatDirectly(alwaysShow = true, isKeyboardOpen = false, hasInputText = false))

        // Keyboard open -> opens toolbar
        assertFalse(shouldOpenChatDirectly(alwaysShow = true, isKeyboardOpen = true, hasInputText = false))

        // Input text present -> opens toolbar
        assertFalse(shouldOpenChatDirectly(alwaysShow = true, isKeyboardOpen = false, hasInputText = true))

        // Always show disabled -> opens toolbar
        assertFalse(shouldOpenChatDirectly(alwaysShow = false, isKeyboardOpen = false, hasInputText = false))
    }

    @Test
    fun testAgentModePromptAssembly() {
        val defaultPrompt = com.textmorph.app.llm.context.ChatCompactor.assembleInferencePrompt(
            systemPrompt = com.textmorph.app.llm.context.ChatCompactor.DEFAULT_SYSTEM_PROMPT,
            historyMessages = emptyList(),
            currentPrompt = "Hello"
        )
        assertTrue(defaultPrompt.contains("TextMorph, an intelligent, helpful on-device AI assistant"))

        val agentPrompt = com.textmorph.app.llm.context.ChatCompactor.assembleInferencePrompt(
            systemPrompt = com.textmorph.app.llm.context.ChatCompactor.AGENT_SYSTEM_PROMPT,
            historyMessages = emptyList(),
            currentPrompt = "Plan automation"
        )
        assertTrue(agentPrompt.contains("TextMorph Agent, a helpful, highly capable AI assistant that can control an Android device"))
        assertTrue(agentPrompt.contains("AVAILABLE ACTIONS"))
    }

    @Test
    fun testChatMessageTruncationLogic() {
        val messages = listOf(
            ChatMessageEntity(id = "m1", sessionId = "s1", role = "user", content = "First prompt"),
            ChatMessageEntity(id = "m2", sessionId = "s1", role = "assistant", content = "First answer"),
            ChatMessageEntity(id = "m3", sessionId = "s1", role = "user", content = "Second prompt"),
            ChatMessageEntity(id = "m4", sessionId = "s1", role = "assistant", content = "Second answer")
        )

        // Truncate inclusive at m3 (Edit Prompt: removes m3 and subsequent)
        val indexM3 = messages.indexOfFirst { it.id == "m3" }
        val editTruncated = messages.subList(0, indexM3)
        assertEquals(2, editTruncated.size)
        assertEquals("m1", editTruncated[0].id)
        assertEquals("m2", editTruncated[1].id)

        // Truncate after m3 (Retry Prompt: keeps m3, removes subsequent)
        val retryTruncated = messages.subList(0, indexM3 + 1)
        assertEquals(3, retryTruncated.size)
        assertEquals("m3", retryTruncated.last().id)
    }

    @Test
    fun testChatSessionEntityAgentModeSerialization() {
        val defaultSession = ChatSessionEntity(title = "Default")
        assertFalse(defaultSession.isAgentMode)

        val agentSession = defaultSession.copy(isAgentMode = true)
        val jsonStr = json.encodeToString(agentSession)
        assertTrue(jsonStr.contains("\"isAgentMode\": true"))

        val decoded = json.decodeFromString<ChatSessionEntity>(jsonStr)
        assertTrue(decoded.isAgentMode)
    }
}
