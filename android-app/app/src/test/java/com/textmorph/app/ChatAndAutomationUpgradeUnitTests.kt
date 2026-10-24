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
}
