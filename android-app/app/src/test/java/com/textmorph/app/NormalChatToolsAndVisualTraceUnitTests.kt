package com.textmorph.app

import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.ui.chat.components.ChatToolCallExtractor
import com.textmorph.app.util.gif.GifEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun testChatToolCallExtractor_followUpsWithCodeActions() {
        val rawMessage = "Paris is the capital of France.\n" +
                "suggest_next_actions(suggestions=[\"search_web(\\\"Paris attractions\\\")\", \"open_app(\\\"Google Maps\\\")\", \"Tell me more about French food\"])"

        val followUps = ChatToolCallExtractor.extractFollowUps(rawMessage)
        assertNotNull(followUps)
        assertEquals(3, followUps?.options?.size)

        val opt0 = followUps?.options?.get(0)
        assertNotNull(opt0)
        assertTrue(opt0!!.isAction)
        assertEquals("search_web", opt0.toolName)
        assertEquals("Web Search", opt0.toolDisplayName)
        assertTrue(opt0.title.contains("Paris attractions"))
        assertTrue(opt0.actionCommand?.contains("search_web") == true)

        val opt1 = followUps.options[1]
        assertTrue(opt1.isAction)
        assertEquals("open_app", opt1.toolName)
        assertEquals("Open App", opt1.toolDisplayName)
        assertEquals("Open Google Maps", opt1.title)

        val opt2 = followUps.options[2]
        assertFalse(opt2.isAction)
        assertEquals("Tell me more about French food", opt2.title)
        assertNull(opt2.actionCommand)
    }

    @Test
    fun testChatToolCallExtractor_markdownFollowUpSection() {
        val rawMessage = "Here is your requested itinerary.\n\n" +
                "Follow-up suggestions:\n" +
                "- Search Paris flight deals: search_web(\"Paris flights\")\n" +
                "- Open Calendar: open_app(\"Calendar\")\n" +
                "- What is the best month to travel?"

        val followUps = ChatToolCallExtractor.extractFollowUps(rawMessage)
        assertNotNull(followUps)
        assertEquals(3, followUps?.options?.size)
        assertEquals("Here is your requested itinerary.", followUps?.remainingContent?.trim())

        assertTrue(followUps!!.options[0].isAction)
        assertEquals("Search Paris flight deals", followUps.options[0].title)
        assertEquals("Web Search", followUps.options[0].toolDisplayName)

        assertTrue(followUps.options[1].isAction)
        assertEquals("Open Calendar", followUps.options[1].title)

        assertFalse(followUps.options[2].isAction)
        assertEquals("What is the best month to travel?", followUps.options[2].title)
    }

    @Test
    fun testAiChatInferenceUtils_isPresentationOrFollowUpAction() {
        val suggestAction = com.textmorph.app.agent.model.AgentAction(
            action = com.textmorph.app.agent.model.AgentAction.ACTION_SUGGEST_NEXT_ACTIONS
        )
        assertTrue(com.textmorph.app.ui.chat.components.AiChatInferenceUtils.isPresentationOrFollowUpAction("", suggestAction))

        val approvalAction = com.textmorph.app.agent.model.AgentAction(
            action = com.textmorph.app.agent.model.AgentAction.ACTION_REQUEST_USER_APPROVAL
        )
        assertTrue(com.textmorph.app.ui.chat.components.AiChatInferenceUtils.isPresentationOrFollowUpAction("", approvalAction))

        val contentWithFollowUp = "Paris is great.\nFollow-up suggestions:\n- search_web(\"Paris\")"
        val searchAction = com.textmorph.app.agent.model.AgentAction(action = "search_web")
        assertTrue(com.textmorph.app.ui.chat.components.AiChatInferenceUtils.isPresentationOrFollowUpAction(contentWithFollowUp, searchAction))

        // Normal active tool invocation without follow-up block
        val bareSearchContent = "search_web(\"Paris weather\")"
        assertFalse(com.textmorph.app.ui.chat.components.AiChatInferenceUtils.isPresentationOrFollowUpAction(bareSearchContent, searchAction))
    }

    @Test
    fun testChatMessageEntity_legacyActionStepsMapping() {
        val legacyStep = com.textmorph.app.agent.model.AgentTaskStep(
            stepIndex = 1,
            actionType = "open_app",
            title = "Opening YouTube...",
            detail = "Fast shortcut launcher",
            status = com.textmorph.app.agent.model.AgentStepStatus.COMPLETED
        )
        val entity = com.textmorph.app.data.model.ChatMessageEntity(
            sessionId = "s1",
            role = "assistant",
            content = "Done",
            actionStepsJson = com.textmorph.app.agent.model.AgentTaskStep.serializeList(listOf(legacyStep)),
            stepTracesJson = null
        )

        val traces = entity.getStepTraces()
        assertEquals(1, traces.size)
        assertEquals("open_app", traces[0].toolName)
        assertEquals("Opening YouTube...", traces[0].inputQuery)
        assertEquals("success", traces[0].status)
    }

    @Test
    fun testChatCommandRouter_codeActionRouting() {
        val openAppPhase = com.textmorph.app.agent.router.ChatCommandRouter.classifyPhase("open_app(\"WhatsApp\")")
        assertEquals(com.textmorph.app.agent.router.AgentPhase.PHASE_AUTOMATION, openAppPhase)

        val searchPhase = com.textmorph.app.agent.router.ChatCommandRouter.classifyPhase("search_web(\"Eiffel Tower\")")
        assertEquals(com.textmorph.app.agent.router.AgentPhase.PHASE_RESEARCH, searchPhase)
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
