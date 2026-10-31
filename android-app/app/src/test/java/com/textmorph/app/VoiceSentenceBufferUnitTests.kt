package com.textmorph.app

import com.textmorph.app.agent.voice.VoiceSentenceBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VoiceSentenceBuffer low-latency streaming chunker:
 * Tests incremental token accumulation, sentence boundary detection,
 * abbreviation protection, and stream flushing.
 */
class VoiceSentenceBufferUnitTests {

    @Test
    fun testIncrementalTokenChunking() {
        val buffer = VoiceSentenceBuffer()

        // Push partial words
        val res1 = buffer.appendToken("Hel")
        assertTrue(res1.isEmpty())

        val res2 = buffer.appendToken("lo there. How ")
        assertEquals(1, res2.size)
        assertEquals("Hello there.", res2[0])

        val res3 = buffer.appendToken("are you doing?")
        assertTrue(res3.isEmpty())

        val res4 = buffer.appendToken(" ")
        assertEquals(1, res4.size)
        assertEquals("How are you doing?", res4[0])

        val flushed = buffer.flush()
        assertNull(flushed)
    }

    @Test
    fun testDoubleNewlineParagraphBreak() {
        val buffer = VoiceSentenceBuffer()
        buffer.appendToken("First thought")
        val res = buffer.appendToken("\n\nSecond thought begins")
        assertEquals(1, res.size)
        assertEquals("First thought", res[0])

        val finalChunk = buffer.flush()
        assertEquals("Second thought begins", finalChunk)
    }

    @Test
    fun testAbbreviationProtection() {
        val buffer = VoiceSentenceBuffer()
        val res = buffer.appendToken("Please consult Dr. Smith before starting.")
        // "Dr. " should not prematurely split sentence
        val finalSentence = buffer.appendToken(" ")
        assertEquals(1, finalSentence.size)
        assertEquals("Please consult Dr. Smith before starting.", finalSentence[0])
    }

    @Test
    fun testFlushRemainingIncompleteSentence() {
        val buffer = VoiceSentenceBuffer()
        buffer.appendToken("This sentence does not end with punctuation")
        val finalChunk = buffer.flush()
        assertEquals("This sentence does not end with punctuation", finalChunk)
    }

    @Test
    fun testBufferReset() {
        val buffer = VoiceSentenceBuffer()
        buffer.appendToken("Partial text here")
        buffer.reset()
        val flushed = buffer.flush()
        assertNull(flushed)
        assertEquals(0, buffer.totalSentencesEmitted)
    }
}
