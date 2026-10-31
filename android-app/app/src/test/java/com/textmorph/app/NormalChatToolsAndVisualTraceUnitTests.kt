package com.textmorph.app

import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.ui.chat.components.ChatToolCallExtractor
import com.textmorph.app.util.gif.GifEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class NormalChatToolsAndVisualTraceUnitTests {

    // ─── 1. Animated GIF Encoder ──────────────────────────────────────

    @Test
    fun testGifEncoderGeneratesValidGifFile() {
        val baos = ByteArrayOutputStream()
        val encoder = GifEncoder()
        encoder.setFrameRate(24f)
        encoder.setRepeat(0)

        val okStart = encoder.start(baos)
        assertTrue(okStart)

        // 10x10 RGB frame (red)
        val frame1 = ByteArray(10 * 10 * 3) { idx ->
            if (idx % 3 == 0) 255.toByte() else 0.toByte()
        }
        val okFrame1 = encoder.addFrameRgb(10, 10, frame1)
        assertTrue(okFrame1)

        // 10x10 RGB frame (blue)
        val frame2 = ByteArray(10 * 10 * 3) { idx ->
            if (idx % 3 == 2) 255.toByte() else 0.toByte()
        }
        val okFrame2 = encoder.addFrameRgb(10, 10, frame2)
        assertTrue(okFrame2)

        val okFinish = encoder.finish()
        assertTrue(okFinish)

        val bytes = baos.toByteArray()
        assertTrue(bytes.size > 50)

        // Validate GIF Header: "GIF89a"
        val header = String(bytes.sliceArray(0..5))
        assertEquals("GIF89a", header)

        // Validate Netscape Loop Extension
        val contentStr = String(bytes)
        assertTrue(contentStr.contains("NETSCAPE2.0"))

        // Validate GIF Trailer: 0x3B (59)
        assertEquals(0x3B.toByte(), bytes.last())
    }

    // ─── 2. Human-in-the-Loop Confirmation Extractor ──────────────────

    @Test
    fun testChatToolCallExtractor_approvalCodeSyntax() {
        val rawMessage = "I can help configure your settings.\n" +
                "request_user_approval(question=\"Which theme do you prefer?\", options=[\"Dark Mode\", \"Light Mode\", \"System Default\"])\n" +
                "Please make your selection."

        val approval = ChatToolCallExtractor.extractApproval(rawMessage)
        assertNotNull(approval)
        assertEquals("Which theme do you prefer?", approval?.question)
        assertEquals(3, approval?.options?.size)
        assertEquals("Dark Mode", approval?.options?.get(0))
        assertEquals("Light Mode", approval?.options?.get(1))
        assertEquals("System Default", approval?.options?.get(2))
        assertTrue(approval?.preText?.contains("configure your settings") == true)
        assertTrue(approval?.postText?.contains("Please make your selection") == true)
    }

    // ─── 3. Follow-Up Action Suggesters Extractor ────────────────────

    @Test
    fun testChatToolCallExtractor_followUpsCodeSyntax() {
        val rawMessage = "Here is the summary of today's tech news.\n" +
                "suggest_next_actions(suggestions=[\"Show AI breakthroughs\", \"Read top headlines\", \"Search hardware updates\"])"

        val followUps = ChatToolCallExtractor.extractFollowUps(rawMessage)
        assertNotNull(followUps)
        assertEquals(3, followUps?.suggestions?.size)
        assertEquals("Show AI breakthroughs", followUps?.suggestions?.get(0))
        assertEquals("Read top headlines", followUps?.suggestions?.get(1))
        assertEquals("Search hardware updates", followUps?.suggestions?.get(2))
        assertEquals("Here is the summary of today's tech news.", followUps?.remainingContent?.trim())
    }

    // ─── 4. Interactive Artifact Extractor ───────────────────────────

    @Test
    fun testChatToolCallExtractor_interactiveViewCodeSyntax() {
        val codeMessage = "present_interactive_view(title=\"Solar Orbit\", html=\"<!DOCTYPE html><html><body><canvas></canvas></body></html>\")"
        val viewCode = ChatToolCallExtractor.extractInteractiveViewHtml(codeMessage)
        assertNotNull(viewCode)
        assertEquals("Solar Orbit", viewCode?.first)
        assertTrue(viewCode?.second?.contains("<canvas></canvas>") == true)
    }

    // ─── 5. System Prompt Requirements ───────────────────────────────

    @Test
    fun testChatSystemPromptIncludesNewToolsAndSingleHtmlRule() {
        val prompt = AgentPrompts.CHAT_SYSTEM_PROMPT
        assertTrue(prompt.contains("request_user_approval"))
        assertTrue(prompt.contains("suggest_next_actions"))
        assertTrue(prompt.contains("present_interactive_view"))
        assertTrue(prompt.contains("SINGLE, complete, self-contained HTML file"))
        assertTrue(prompt.contains("auto-play immediately upon loading"))
        assertTrue(prompt.contains("10-second 24fps GIF animation"))
    }
}
