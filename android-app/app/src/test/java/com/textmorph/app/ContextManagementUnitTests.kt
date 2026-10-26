package com.textmorph.app

import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.ContextColorState
import com.textmorph.app.llm.context.ChatCompactor
import com.textmorph.app.llm.context.ContextPruner
import com.textmorph.app.util.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for context window compaction, payload stripping, token estimation,
 * and capacity metrics calculations.
 */
class ContextManagementUnitTests {

    @Test
    fun testTokenEstimator_estimatesTextAndImagesCorrectly() {
        val sampleText = "Hello world! This is an on-device AI test."
        val tokens = TokenEstimator.estimateTextTokens(sampleText)
        assertTrue("Estimated tokens should be greater than 5", tokens > 5)

        val imageTokens = TokenEstimator.estimateImageTokens(2)
        assertEquals(512, imageTokens)
    }

    @Test
    fun testContextColorState_thresholdsMatchSpecification() {
        val lowUsage = TokenEstimator.computeMetrics(
            systemPrompt = "System",
            summary = null,
            sessionState = null,
            recentMessages = emptyList(),
            currentInput = "Hi",
            maxContextTokens = 2000
        )
        assertEquals(ContextColorState.SAGE, lowUsage.colorState)

        // 70% usage -> AMBER
        val mediumMessage = ChatMessageEntity(
            sessionId = "s1",
            role = "user",
            content = "a".repeat(5200) // approx 1368+ tokens (68%)
        )
        val mediumUsage = TokenEstimator.computeMetrics(
            systemPrompt = "System",
            summary = null,
            sessionState = null,
            recentMessages = listOf(mediumMessage),
            currentInput = "Hi",
            maxContextTokens = 2000
        )
        assertEquals(ContextColorState.AMBER, mediumUsage.colorState)

        // >85% usage -> CRIMSON
        val heavyMessage = ChatMessageEntity(
            sessionId = "s1",
            role = "user",
            content = "a".repeat(7000) // approx 1800+ tokens
        )
        val highUsage = TokenEstimator.computeMetrics(
            systemPrompt = "System",
            summary = null,
            sessionState = null,
            recentMessages = listOf(heavyMessage),
            currentInput = "Hi",
            maxContextTokens = 2000
        )
        assertEquals(ContextColorState.CRIMSON, highUsage.colorState)
    }

    @Test
    fun testContextPruner_stripsThinkingTags() {
        val message = ChatMessageEntity(
            sessionId = "s1",
            role = "assistant",
            content = "<think>Analyzing user query about biology...</think>The mitochondria is the powerhouse of the cell."
        )
        val pruned = ContextPruner.pruneForInferenceContext(message)
        assertFalse(pruned.contains("<think>"))
        assertFalse(pruned.contains("Analyzing user query"))
        assertTrue(pruned.contains("powerhouse of the cell"))
    }

    @Test
    fun testContextPruner_collapsesHtmlDocuments() {
        val htmlDoc = "<!DOCTYPE html><html><head><title>Stopwatch Timer</title></head><body><h1>Timer</h1></body></html>"
        val message = ChatMessageEntity(
            sessionId = "s1",
            role = "assistant",
            content = "Here is your app:\n$htmlDoc"
        )
        val pruned = ContextPruner.pruneForInferenceContext(message)
        assertFalse(pruned.contains("<!DOCTYPE html>"))
        assertTrue(pruned.contains("[Generated HTML page: Stopwatch Timer]"))
    }

    @Test
    fun testContextPruner_collapsesJsonActions() {
        val jsonAction = "```json\n{\"action\": \"open_app\", \"target\": \"YouTube\"}\n```"
        val message = ChatMessageEntity(
            sessionId = "s1",
            role = "assistant",
            content = "Launching requested target:\n$jsonAction",
            actionPayloadJson = "{\"action\": \"open_app\", \"target\": \"YouTube\"}",
            actionStatus = "success"
        )
        val pruned = ContextPruner.pruneForInferenceContext(message)
        assertFalse(pruned.contains("```json"))
        assertTrue(pruned.contains("[Action: open_app -> YouTube [success]]"))
    }

    @Test
    fun testChatCompactor_slidingWindowPartition() {
        val messages = (1..14).map { i ->
            ChatMessageEntity(
                sessionId = "s1",
                role = if (i % 2 == 1) "user" else "assistant",
                content = "Turn $i"
            )
        }

        val (older, recent) = ChatCompactor.partitionWindow(messages, recentTurnCount = 4)
        assertEquals(6, older.size)
        assertEquals(8, recent.size)
        assertEquals("Turn 14", recent.last().content)
    }

    @Test
    fun testChatCompactor_assemblesInferencePromptWithCompactedContext() {
        val history = listOf(
            ChatMessageEntity(sessionId = "s1", role = "user", content = "Tell me a joke"),
            ChatMessageEntity(sessionId = "s1", role = "assistant", content = "Why did the chicken cross the road?")
        )

        val prompt = ChatCompactor.assembleInferencePrompt(
            systemPrompt = "You are TextMorph.",
            sessionSummary = "• User previously asked for jokes.",
            sessionState = "- User prefers short one-liners.",
            historyMessages = history,
            currentPrompt = "Give me another one",
            documentText = "Joke Book Chapter 1"
        )

        assertTrue(prompt.contains("<|im_start|>system\nYou are TextMorph."))
        assertTrue(prompt.contains("[Active Session Memory & Constraints]:\n- User prefers short one-liners."))
        assertTrue(prompt.contains("[Summary of Previous Conversation]:\n• User previously asked for jokes."))
        assertTrue(prompt.contains("<|im_start|>user\nTell me a joke\n<|im_end|>"))
        assertTrue(prompt.contains("[Attached Document Context]:\nJoke Book Chapter 1"))
        assertTrue(prompt.contains("Give me another one"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun testHtmlSnippetExtractor_streamingAndCompletedSnippets() {
        // Case 1: Plain text without HTML
        val plain = com.textmorph.app.ui.chat.components.HtmlSnippetExtractor.extractSegments("Just a simple answer.", isDone = true)
        org.junit.Assert.assertNull(plain.htmlContent)
        assertEquals("Just a simple answer.", plain.preText)

        // Case 2: Incomplete streaming HTML
        val streaming = com.textmorph.app.ui.chat.components.HtmlSnippetExtractor.extractSegments(
            "Building page now:\n```html\n<!DOCTYPE html><html><body><h1>Streaming</h1>",
            isDone = false
        )
        org.junit.Assert.assertNotNull(streaming.htmlContent)
        assertFalse(streaming.isHtmlComplete)
        assertEquals("Building page now:", streaming.preText)
        assertTrue(streaming.htmlContent!!.contains("<h1>Streaming</h1>"))
        assertEquals("", streaming.postText)

        // Case 3: Fully completed HTML with preamble and postscript
        val completed = com.textmorph.app.ui.chat.components.HtmlSnippetExtractor.extractSegments(
            "Here is the interactive app:\n```html\n<!DOCTYPE html><html><body><h1>App</h1></body></html>\n```\nLet me know if you need tweaks!",
            isDone = true
        )
        org.junit.Assert.assertNotNull(completed.htmlContent)
        assertTrue(completed.isHtmlComplete)
        assertEquals("Here is the interactive app:", completed.preText)
        assertEquals("<!DOCTYPE html><html><body><h1>App</h1></body></html>", completed.htmlContent)
        assertEquals("Let me know if you need tweaks!", completed.postText)
    }

    @Test
    fun testAssembleInferencePrompt_clampsOversizedPromptToContextLimit() {
        val massivePrompt = "This is a very long log line repeated many times. ".repeat(600) // ~30,000 chars, ~7800 tokens
        val assembled = ChatCompactor.assembleInferencePrompt(
            systemPrompt = "System instruction",
            historyMessages = emptyList(),
            currentPrompt = massivePrompt,
            maxContextTokens = 4096,
            maxOutputTokens = 1024
        )
        val tokenEstimate = TokenEstimator.estimateTextTokens(assembled)
        assertTrue("Assembled prompt should be clamped under 4096 tokens (was $tokenEstimate)", tokenEstimate < 4096)
        assertTrue("Assembled prompt should contain truncation notice", assembled.contains("Notice: Input truncated to fit model's 4096 hardware context capacity"))
    }
}
