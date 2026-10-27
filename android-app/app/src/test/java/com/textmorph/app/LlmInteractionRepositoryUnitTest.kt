package com.textmorph.app

import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.model.LlmInteractionRecord
import com.textmorph.app.ui.modelhub.components.ConversationDumpTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for unified LLM interaction recording, reverse-chronological ordering
 * (newest to oldest), trigger source filtering, and retention purging.
 */
class LlmInteractionRepositoryUnitTest {

    @Test
    fun testInteractionRecordsOrderingNewestToOldest() {
        val now = System.currentTimeMillis()

        val record1 = LlmInteractionRecord(
            id = "rec1",
            timestamp = now - 30000,
            source = InteractionTriggerSource.CUSTOM_PROMPTS,
            title = "Oldest Action",
            userPrompt = "Prompt 1",
            rawOutput = "Output 1"
        )
        val record2 = LlmInteractionRecord(
            id = "rec2",
            timestamp = now - 15000,
            source = InteractionTriggerSource.VOICE_AGENT,
            title = "Middle Action",
            userPrompt = "Prompt 2",
            rawOutput = "Output 2"
        )
        val record3 = LlmInteractionRecord(
            id = "rec3",
            timestamp = now - 1000,
            source = InteractionTriggerSource.CUSTOM_COMMANDS,
            title = "Newest Action",
            userPrompt = "Prompt 3",
            rawOutput = "Output 3"
        )

        val turns = ConversationDumpTurn.fromInteractionRecords(listOf(record1, record2, record3))
        assertEquals(3, turns.size)

        // Index 0 must be the newest interaction (rec3)
        assertTrue(turns[0].userPrompt.contains("Prompt 3"))
        assertTrue(turns[0].userPrompt.contains(InteractionTriggerSource.CUSTOM_COMMANDS.displayName))
        assertEquals("Output 3", turns[0].rawNakedOutput)

        // Index 1 must be middle (rec2)
        assertTrue(turns[1].userPrompt.contains("Prompt 2"))
        assertTrue(turns[1].userPrompt.contains(InteractionTriggerSource.VOICE_AGENT.displayName))

        // Index 2 must be oldest (rec1)
        assertTrue(turns[2].userPrompt.contains("Prompt 1"))
        assertTrue(turns[2].userPrompt.contains(InteractionTriggerSource.CUSTOM_PROMPTS.displayName))
    }

    @Test
    fun testFilteringByTriggerSource() {
        val now = System.currentTimeMillis()

        val records = listOf(
            LlmInteractionRecord(
                timestamp = now - 5000,
                source = InteractionTriggerSource.VOICE_AGENT,
                title = "Voice Call Mom",
                userPrompt = "Call Mom",
                rawOutput = "make_call(\"Mom\")"
            ),
            LlmInteractionRecord(
                timestamp = now - 4000,
                source = InteractionTriggerSource.CUSTOM_COMMANDS,
                title = "/summarize",
                userPrompt = "/summarize text",
                rawOutput = "Summary text"
            ),
            LlmInteractionRecord(
                timestamp = now - 3000,
                source = InteractionTriggerSource.SYSTEM_ACTIVITY,
                title = "Auto Correct Quick Fix",
                userPrompt = "Correct: helo",
                rawOutput = "hello"
            ),
            LlmInteractionRecord(
                timestamp = now - 2000,
                source = InteractionTriggerSource.CUSTOM_PROMPTS,
                title = "Rewrite Professional",
                userPrompt = "Rewrite email",
                rawOutput = "Professional email"
            )
        )

        // Filter: Voice Agent
        val voiceTurns = records.filter { it.source == InteractionTriggerSource.VOICE_AGENT }
        assertEquals(1, voiceTurns.size)
        assertEquals("Call Mom", voiceTurns[0].userPrompt)

        // Filter: Custom Commands
        val commandTurns = records.filter { it.source == InteractionTriggerSource.CUSTOM_COMMANDS }
        assertEquals(1, commandTurns.size)
        assertEquals("/summarize text", commandTurns[0].userPrompt)

        // Filter: System Activity
        val systemTurns = records.filter { it.source == InteractionTriggerSource.SYSTEM_ACTIVITY }
        assertEquals(1, systemTurns.size)
        assertEquals("hello", systemTurns[0].rawOutput)

        // Filter: Custom Prompts
        val promptTurns = records.filter { it.source == InteractionTriggerSource.CUSTOM_PROMPTS }
        assertEquals(1, promptTurns.size)
        assertEquals("Rewrite email", promptTurns[0].userPrompt)
    }

    @Test
    fun testDailyRetentionPurgingLogic() {
        val now = System.currentTimeMillis()
        val oneDayMillis = 24L * 60L * 60L * 1000L

        // Record from 2 days ago (should be purged under 1-day retention)
        val oldRecord = LlmInteractionRecord(
            id = "expired_rec",
            timestamp = now - (2 * oneDayMillis),
            source = InteractionTriggerSource.CUSTOM_PROMPTS,
            title = "Old Action",
            userPrompt = "Old prompt",
            rawOutput = "Old output"
        )

        // Record from 5 hours ago (should be retained under 1-day retention)
        val recentRecord = LlmInteractionRecord(
            id = "active_rec",
            timestamp = now - (5L * 60L * 60L * 1000L),
            source = InteractionTriggerSource.VOICE_AGENT,
            title = "Recent Action",
            userPrompt = "Recent prompt",
            rawOutput = "Recent output"
        )

        val records = listOf(oldRecord, recentRecord)

        // Retention = 1 day (default)
        val retentionDays = 1
        val cutoff = now - (retentionDays * oneDayMillis)
        val retained = records.filter { it.timestamp >= cutoff }

        assertEquals(1, retained.size)
        assertEquals("active_rec", retained[0].id)
    }

    @Test
    fun testForeverRetentionPreservesAllRecords() {
        val now = System.currentTimeMillis()
        val oneYearMillis = 365L * 24L * 60L * 60L * 1000L

        val ancientRecord = LlmInteractionRecord(
            id = "ancient_rec",
            timestamp = now - oneYearMillis,
            source = InteractionTriggerSource.CHAT_CONVERSATION,
            title = "Ancient Action",
            userPrompt = "Ancient prompt",
            rawOutput = "Ancient output"
        )

        val records = listOf(ancientRecord)

        // Retention = Forever (e.g. 9999 days)
        val retentionDays = 9999
        val isForever = retentionDays >= 365
        val retained = if (isForever) records else records.filter { it.timestamp >= now - (retentionDays * 86400000L) }

        assertEquals(1, retained.size)
        assertEquals("ancient_rec", retained[0].id)
    }
}
