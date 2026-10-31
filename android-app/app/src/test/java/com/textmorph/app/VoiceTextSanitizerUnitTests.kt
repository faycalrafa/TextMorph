package com.textmorph.app

import com.textmorph.app.agent.voice.VoiceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VoiceTextSanitizer natural spoken formatting:
 * Tests think tag removal, code action stripping, math/LaTeX to words translation,
 * and markdown clean-up.
 */
class VoiceTextSanitizerUnitTests {

    @Test
    fun testThinkTagRemoval() {
        val raw = "<think>\nThinking about 2+2=4\n</think>\nThe answer is 4."
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertEquals("The answer is 4.", sanitized)
        assertFalse(sanitized.contains("<think>"))
        assertFalse(sanitized.contains("Thinking about"))
    }

    @Test
    fun testUnclosedThinkTagRemoval() {
        val raw = "<think>I am planning the answer step by step"
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertEquals("", sanitized)
    }

    @Test
    fun testCodeActionStripping() {
        val raw = "Sure! click_text(\"Settings\") I will open settings for you. open_app(\"Camera\")"
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertFalse(sanitized.contains("click_text"))
        assertFalse(sanitized.contains("open_app"))
        assertTrue(sanitized.contains("I will open settings for you."))
    }

    @Test
    fun testMarkdownCodeBlocksAndUrlStripping() {
        val raw = "Here is the code:\n```kotlin\nval x = 42\n```\nCheck https://example.com for more info."
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertFalse(sanitized.contains("```"))
        assertFalse(sanitized.contains("val x = 42"))
        assertFalse(sanitized.contains("https://"))
        assertTrue(sanitized.contains("Here is the code:"))
        assertTrue(sanitized.contains("Check for more info."))
    }

    @Test
    fun testLatexFractionConversion() {
        val raw = "The result is \\frac{1}{2} of the total."
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertEquals("The result is 1 over 2 of the total.", sanitized)
    }

    @Test
    fun testLatexSquareRootConversion() {
        val raw = "The square root of nine is \\sqrt{9}."
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertEquals("The square root of nine is square root of 9.", sanitized)
    }

    @Test
    fun testPowersConversion() {
        val raw = "The equation has x^2 plus y^3."
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertEquals("The equation has x squared plus y cubed.", sanitized)
    }

    @Test
    fun testMathOperatorsConversion() {
        val raw = "5 \\times 3 \\approx 15, and x \\neq 0."
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertTrue(sanitized.contains("times"))
        assertTrue(sanitized.contains("approximately"))
        assertTrue(sanitized.contains("not equal to"))
    }

    @Test
    fun testMarkdownFormattingStripping() {
        val raw = "## Summary\n**Important:** The answer is *correct*."
        val sanitized = VoiceTextSanitizer.sanitizeForTts(raw)
        assertEquals("Summary Important: The answer is correct.", sanitized)
        assertFalse(sanitized.contains("#"))
        assertFalse(sanitized.contains("*"))
    }
}
