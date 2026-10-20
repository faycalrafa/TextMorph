package com.textmorph.app

import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.llm.LiteRtLmEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMorphUnitTests {

    @Test
    fun testDefaultSeedPromptsContainsCoreCategories() {
        val seeds = PromptRepository.getDefaultSeedPrompts()
        assertNotNull(seeds)
        assertTrue(seeds.size >= 10)

        val hasTextTransform = seeds.any { it.category == PromptCategory.TEXT_TRANSFORM }
        val hasSmartReply = seeds.any { it.category == PromptCategory.SMART_REPLY }
        val hasVision = seeds.any { it.category == PromptCategory.VISION_ACTION }

        assertTrue("Should contain TEXT_TRANSFORM seeds", hasTextTransform)
        assertTrue("Should contain SMART_REPLY seeds", hasSmartReply)
        assertTrue("Should contain VISION_ACTION seeds", hasVision)
    }

    @Test
    fun testSeedPromptFixGrammar() {
        val seeds = PromptRepository.getDefaultSeedPrompts()
        val fixGrammar = seeds.firstOrNull { it.id == "seed_fix_grammar" }
        assertNotNull(fixGrammar)
        assertEquals("Fix Grammar", fixGrammar?.title)
        assertFalse(fixGrammar!!.requiresVision)
    }

    @Test
    fun testVersionComparison() {
        fun isVersionNewer(remote: String, current: String): Boolean {
            val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        assertTrue(isVersionNewer("1.0.1", "1.0.0"))
        assertTrue(isVersionNewer("1.1.0", "1.0.9"))
        assertTrue(isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(isVersionNewer("1.0.0", "1.0.1"))
    }
}
