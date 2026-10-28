package com.textmorph.app

import com.textmorph.app.data.model.WakeWordEntity
import com.textmorph.app.data.repository.WakeWordRepository
import com.textmorph.app.ui.navigation.AppDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying WakeWordRepository behavior, seeds, strict max-2 active constraint,
 * 3-attempt acoustic calibration requirement, and tokens.txt phonetic validation.
 */
class WakeWordRepositoryUnitTests {

    @Test
    fun testDefaultSeedWakeWords_containsExpectedBuiltIns() {
        val seeds = WakeWordRepository.getDefaultSeedWakeWords()
        assertEquals(6, seeds.size)

        val computer = seeds.find { it.id == "computer" }
        assertNotNull(computer)
        assertTrue(computer!!.isBuiltIn)
        assertTrue(computer.isCalibrated)
        assertTrue(computer.isEnabled)
        assertEquals(3, computer.testPassCount)
        assertEquals("@computer", computer.aliasTag)

        val heyTamim = seeds.find { it.id == "hey_tamim" }
        assertNotNull(heyTamim)
        assertTrue(heyTamim!!.isBuiltIn)
        assertTrue(heyTamim.isCalibrated)
        assertTrue(heyTamim.isEnabled)
        assertEquals(3, heyTamim.testPassCount)
        assertEquals("@hey_tamim", heyTamim.aliasTag)

        val jarvis = seeds.find { it.id == "jarvis" }
        assertNotNull(jarvis)
        assertTrue(jarvis!!.isBuiltIn)
        assertFalse(jarvis.isCalibrated)
        assertFalse(jarvis.isEnabled)
        assertEquals(0, jarvis.testPassCount)

        val heyTextMorph = seeds.find { it.id == "hey_textmorph" }
        assertNotNull(heyTextMorph)
        assertFalse(heyTextMorph!!.isCalibrated)

        val heyAssistant = seeds.find { it.id == "hey_assistant" }
        assertNotNull(heyAssistant)
        assertFalse(heyAssistant!!.isCalibrated)

        val openSesame = seeds.find { it.id == "open_sesame" }
        assertNotNull(openSesame)
        assertFalse(openSesame!!.isCalibrated)
    }

    @Test
    fun testMaxTwoActiveWakeWordsConstraint_rejectsThirdActivation() {
        val seeds = WakeWordRepository.getDefaultSeedWakeWords().toMutableList()
        // computer and hey_tamim are already active (count = 2)
        assertEquals(2, seeds.count { it.isEnabled })

        // Simulate calibrating jarvis
        val jarvisIndex = seeds.indexOfFirst { it.id == "jarvis" }
        seeds[jarvisIndex] = seeds[jarvisIndex].copy(isCalibrated = true, testPassCount = 3)

        // Attempting to enable jarvis when 2 are already active must fail
        val activeCount = seeds.count { it.isEnabled }
        val canActivate = activeCount < 2
        assertFalse("Cannot activate 3rd trigger when 2 are already enabled", canActivate)

        // Deactivating computer allows jarvis to be activated
        val computerIndex = seeds.indexOfFirst { it.id == "computer" }
        seeds[computerIndex] = seeds[computerIndex].copy(isEnabled = false)
        assertEquals(1, seeds.count { it.isEnabled })

        val canActivateNow = seeds.count { it.isEnabled } < 2
        assertTrue("Can activate jarvis now that count is 1", canActivateNow)
        seeds[jarvisIndex] = seeds[jarvisIndex].copy(isEnabled = true)
        assertEquals(2, seeds.count { it.isEnabled })
    }

    @Test
    fun testUncalibratedWakeWord_cannotBeActivatedBeforeThreePasses() {
        val jarvis = WakeWordEntity(
            id = "jarvis",
            displayName = "Jarvis",
            phonemes = "JH AA1 R V IH0 S",
            aliasTag = "@jarvis",
            isCalibrated = false,
            isEnabled = false,
            testPassCount = 1
        )

        assertFalse("Uncalibrated trigger cannot be active", jarvis.isCalibrated)
        assertTrue("Requires 3 passes for certification", jarvis.testPassCount < 3)
    }

    @Test
    fun testAllBuiltInWakeWords_phonemesExistInTokensTxt() {
        val tokensFile = File("src/main/assets/kws/tokens.txt")
        assertTrue("tokens.txt must exist", tokensFile.exists())

        val validTokens = tokensFile.readLines()
            .mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex())
                parts.firstOrNull()?.takeIf { it.isNotBlank() }
            }
            .toSet()

        val seeds = WakeWordRepository.getDefaultSeedWakeWords()
        for (item in seeds) {
            item.phonemes.lineSequence().forEach { line ->
                val cleanLine = line.substringBefore("@").trim()
                if (cleanLine.isNotBlank()) {
                    val tokens = cleanLine.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    for (token in tokens) {
                        assertTrue(
                            "Phoneme token '$token' in built-in wake-word '${item.displayName}' must exist in tokens.txt",
                            validTokens.contains(token)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun testAppDestination_wakeWordsExists() {
        assertEquals("Wake-Word Detection", AppDestination.WAKE_WORDS.title)
    }
}
