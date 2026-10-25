package com.textmorph.app

import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.ui.modelhub.components.ConversationDumpTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Naked LLM Output & Conversation Dump extraction and ordering.
 */
class ConversationDumpUnitTests {

    @Test
    fun testExtractDumpTurnsOrderedLatestToOldest() {
        val now = System.currentTimeMillis()
        val messages = listOf(
            ChatMessageEntity(
                sessionId = "s1",
                role = "user",
                content = "Turn 1 question",
                timestamp = now - 20000
            ),
            ChatMessageEntity(
                sessionId = "s1",
                role = "assistant",
                content = "Turn 1 answer",
                rawNakedOutput = "Turn 1 raw naked output",
                systemPrompt = "System prompt 1",
                timestamp = now - 18000
            ),
            ChatMessageEntity(
                sessionId = "s1",
                role = "user",
                content = "Turn 2 question",
                timestamp = now - 10000
            ),
            ChatMessageEntity(
                sessionId = "s1",
                role = "assistant",
                content = "Turn 2 answer",
                rawNakedOutput = "Turn 2 raw naked output",
                systemPrompt = "System prompt 2",
                timestamp = now - 8000
            )
        )

        val turns = ConversationDumpTurn.extractDumpTurns(messages)
        assertEquals(2, turns.size)

        // Turn at index 0 must be the LATEST turn (Turn 2)
        assertEquals("Turn 2 question", turns[0].userPrompt)
        assertEquals("Turn 2 raw naked output", turns[0].rawNakedOutput)
        assertEquals("System prompt 2", turns[0].systemPrompt)

        // Turn at index 1 must be the OLDEST turn (Turn 1)
        assertEquals("Turn 1 question", turns[1].userPrompt)
        assertEquals("Turn 1 raw naked output", turns[1].rawNakedOutput)
        assertEquals("System prompt 1", turns[1].systemPrompt)
    }

    @Test
    fun testRawNakedOutputPreservedWhenActionReplacedContent() {
        val now = System.currentTimeMillis()
        val messages = listOf(
            ChatMessageEntity(
                sessionId = "s1",
                role = "user",
                content = "Open YouTube",
                timestamp = now - 5000
            ),
            ChatMessageEntity(
                sessionId = "s1",
                role = "assistant",
                content = "Executing open_app…", // User-facing substituted response
                rawNakedOutput = "open_app(\"YouTube\")", // Original byte-for-byte naked LLM output!
                systemPrompt = "Agent System Prompt",
                actionPayloadJson = "open_app",
                actionStatus = "success",
                timestamp = now - 3000
            )
        )

        val turns = ConversationDumpTurn.extractDumpTurns(messages)
        assertEquals(1, turns.size)
        val turn = turns[0]
        assertEquals("Open YouTube", turn.userPrompt)
        assertEquals("Executing open_app…", turn.cleanContent)
        assertEquals("open_app(\"YouTube\")", turn.rawNakedOutput)
        assertEquals("open_app", turn.actionPayloadJson)
        assertEquals("success", turn.actionStatus)
        assertFalse(turn.isActionFailed)
    }

    @Test
    fun testEmptyMessagesProducesEmptyDump() {
        val turns = ConversationDumpTurn.extractDumpTurns(emptyList())
        assertTrue(turns.isEmpty())
    }

    @Test
    fun testActionFailureFlagDetected() {
        val now = System.currentTimeMillis()
        val messages = listOf(
            ChatMessageEntity(
                sessionId = "s1",
                role = "user",
                content = "Do something invalid",
                timestamp = now - 5000
            ),
            ChatMessageEntity(
                sessionId = "s1",
                role = "assistant",
                content = "Failed executing task",
                rawNakedOutput = "invalid_cmd()",
                actionStatus = "failed",
                timestamp = now - 3000
            )
        )

        val turns = ConversationDumpTurn.extractDumpTurns(messages)
        assertEquals(1, turns.size)
        assertTrue(turns[0].isActionFailed)
    }
}
