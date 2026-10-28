package com.textmorph.app

import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.voice.VoiceCommandManager
import com.textmorph.app.voice.WakeWordTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests verifying offline multi-wake-word detection parsing,
 * VoiceCommandManager routing, model asset integrity, and settings persistence.
 */
class WakeWordDetectorUnitTests {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------------------------------------------------------------------------------------------
    // 1. WakeWordTrigger Normalization & Classification
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testWakeWordTrigger_parsesComputerCorrectly() {
        val triggerFromTag = WakeWordTrigger.fromKeyword("@computer")
        assertEquals(WakeWordTrigger.Computer, triggerFromTag)
        assertEquals("computer", triggerFromTag.keyword)
        assertEquals("Computer", triggerFromTag.displayName)

        val triggerBare = WakeWordTrigger.fromKeyword("computer")
        assertEquals(WakeWordTrigger.Computer, triggerBare)

        val triggerUpper = WakeWordTrigger.fromKeyword("  @COMPUTER  ")
        assertEquals(WakeWordTrigger.Computer, triggerUpper)
    }

    @Test
    fun testWakeWordTrigger_parsesHeyTamimCorrectly() {
        val triggerFromTag = WakeWordTrigger.fromKeyword("@hey_tamim")
        assertEquals(WakeWordTrigger.HeyTamim, triggerFromTag)
        assertEquals("hey_tamim", triggerFromTag.keyword)
        assertEquals("Hey Tamim", triggerFromTag.displayName)

        val triggerBare = WakeWordTrigger.fromKeyword("hey_tamim")
        assertEquals(WakeWordTrigger.HeyTamim, triggerBare)

        val triggerSpace = WakeWordTrigger.fromKeyword("hey tamim")
        assertEquals(WakeWordTrigger.HeyTamim, triggerSpace)
    }

    @Test
    fun testWakeWordTrigger_parsesCustomKeyword() {
        val trigger = WakeWordTrigger.fromKeyword("@open_notes")
        assertTrue(trigger is WakeWordTrigger.Custom)
        assertEquals("open_notes", trigger.keyword)
        assertEquals("open_notes", trigger.displayName)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. VoiceCommandManager Routing & Lifecycle Callbacks
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testVoiceCommandManager_routesTriggerToRegisteredHandler() = runBlocking {
        var receivedTrigger: WakeWordTrigger? = null
        var finishedCallbackCalled = false

        VoiceCommandManager.registerListeningHandler { trigger, onFinished ->
            receivedTrigger = trigger
            onFinished()
        }

        VoiceCommandManager.onWakeWordDetected("@computer") {
            finishedCallbackCalled = true
        }

        // Allow coroutines on Dispatchers.Main to process
        kotlinx.coroutines.delay(100L)

        assertEquals(WakeWordTrigger.Computer, receivedTrigger)
        assertTrue(finishedCallbackCalled)

        VoiceCommandManager.unregisterListeningHandler()
    }

    @Test
    fun testVoiceCommandManager_emitsEventsOnSharedFlow() = runBlocking {
        val job = launch {
            val event = VoiceCommandManager.wakeWordEvents.first()
            assertEquals(WakeWordTrigger.HeyTamim, event)
        }

        kotlinx.coroutines.delay(50L)
        VoiceCommandManager.onWakeWordDetected("@hey_tamim") {}

        job.join()
    }

    // ---------------------------------------------------------------------------------------------
    // 3. AppSettings Persistence for Wake-Word Detection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testAppSettings_wakeWordDetectionDefaultIsFalse() {
        val settings = AppSettings()
        assertFalse(settings.wakeWordDetectionEnabled)
    }

    @Test
    fun testAppSettings_wakeWordDetectionSerializesAndDeserializes() {
        val original = AppSettings(wakeWordDetectionEnabled = true)
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<AppSettings>(serialized)

        assertTrue(deserialized.wakeWordDetectionEnabled)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. KWS Model Assets & Keywords File Integrity
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testKwsAssets_filesExistAndMeetFootprintBudget() {
        // Resolve assets relative to project directory
        val assetsDir = File("src/main/assets/kws")
        assertTrue("Assets kws directory must exist", assetsDir.exists())

        val encoder = File(assetsDir, "encoder.int8.onnx")
        val decoder = File(assetsDir, "decoder.int8.onnx")
        val joiner = File(assetsDir, "joiner.int8.onnx")
        val tokens = File(assetsDir, "tokens.txt")
        val keywords = File(assetsDir, "keywords.txt")

        assertTrue("encoder.int8.onnx must exist", encoder.exists() && encoder.length() > 0)
        assertTrue("decoder.int8.onnx must exist", decoder.exists() && decoder.length() > 0)
        assertTrue("joiner.int8.onnx must exist", joiner.exists() && joiner.length() > 0)
        assertTrue("tokens.txt must exist", tokens.exists() && tokens.length() > 0)
        assertTrue("keywords.txt must exist", keywords.exists() && keywords.length() > 0)

        val totalBytes = encoder.length() + decoder.length() + joiner.length() + tokens.length() + keywords.length()
        val totalMb = totalBytes / (1024.0 * 1024.0)

        // Must stay strictly below 15MB footprint requirement (<15 MB)
        assertTrue("Total KWS assets size must be < 15MB, actual: $totalMb MB", totalMb < 15.0)
    }

    @Test
    fun testKeywordsFile_registersBothRequiredTriggerPhrases() {
        val keywordsFile = File("src/main/assets/kws/keywords.txt")
        assertTrue("keywords.txt must exist", keywordsFile.exists())

        val content = keywordsFile.readText()
        assertTrue("keywords.txt must define @computer alias", content.contains("@computer"))
        assertTrue("keywords.txt must define @hey_tamim alias", content.contains("@hey_tamim"))
        assertTrue("keywords.txt must contain computer phonemes", content.contains("k ah m p y uw t er"))
        assertTrue("keywords.txt must contain hey tamim phonemes", content.contains("h ey / t aa m ih m"))
    }
}
