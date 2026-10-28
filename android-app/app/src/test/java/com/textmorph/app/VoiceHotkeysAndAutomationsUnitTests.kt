package com.textmorph.app

import com.textmorph.app.agent.planner.AiAutomationScriptGenerator
import com.textmorph.app.data.model.AutomationActionType
import com.textmorph.app.data.model.AutomationEntity
import com.textmorph.app.data.repository.AutomationRepository
import com.textmorph.app.ui.navigation.AppDestination
import com.textmorph.app.util.ClipboardHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying Hot Voice Keys, ScreenAssist triggers,
 * Automation persistence models, AI Script synthesis, and Clipboard caching.
 */
class VoiceHotkeysAndAutomationsUnitTests {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun testDefaultSeedAutomationsExist() {
        val seeds = AutomationRepository.getDefaultSeedAutomations()
        assertTrue("Seeds must not be empty", seeds.isNotEmpty())

        val assistMe = seeds.firstOrNull { it.id == "seed_assist_me" }
        assertNotNull("assist me seed must exist", assistMe)
        assertEquals("assist me", assistMe?.trigger)
        assertEquals(AutomationActionType.SCREEN_ASSIST, assistMe?.actionType)
        assertTrue("ScreenAssist must auto-copy to clipboard", assistMe?.copyToClipboard == true)
        assertEquals("📋 Screen Assist copied to clipboard!", assistMe?.toastMessage)

        val summarize = seeds.firstOrNull { it.id == "seed_summarize_screen" }
        assertNotNull("summarize screen seed must exist", summarize)
        assertEquals(AutomationActionType.SCREEN_ASSIST, summarize?.actionType)
        assertTrue("Summarize screen must copy to clipboard", summarize?.copyToClipboard == true)

        val callBrother = seeds.firstOrNull { it.id == "seed_call_brother" }
        assertNotNull("call brother seed must exist", callBrother)
        assertEquals("call my brother", callBrother?.trigger)
        assertEquals(AutomationActionType.PHONE_CALL, callBrother?.actionType)
        assertEquals("called out nouh !", callBrother?.toastMessage)

        val openYouTube = seeds.firstOrNull { it.id == "seed_open_youtube" }
        assertNotNull("open youtube seed must exist", openYouTube)
        assertEquals(AutomationActionType.OPEN_APP, openYouTube?.actionType)
        assertEquals("YouTube", openYouTube?.targetApp)
    }

    @Test
    fun testTriggerNormalizationAndMatching() {
        val entity = AutomationEntity(
            trigger = "Call My Brother!",
            title = "Call Brother",
            actionType = AutomationActionType.PHONE_CALL,
            targetNumber = "0612345678",
            toastMessage = "called out nouh !"
        )

        assertEquals("call my brother", entity.cleanTrigger)

        val seeds = AutomationRepository.getDefaultSeedAutomations()

        // Find matching helper logic simulation
        fun match(input: String, list: List<AutomationEntity>): AutomationEntity? {
            val clean = input.trim().lowercase().replace(Regex("[^a-z0-9 ]"), "")
            return list.firstOrNull { it.cleanTrigger == clean }
                ?: list.filter { clean.startsWith(it.cleanTrigger) }.maxByOrNull { it.cleanTrigger.length }
        }

        val exactMatch = match("assist me", seeds)
        assertNotNull(exactMatch)
        assertEquals("seed_assist_me", exactMatch?.id)

        val punctuationMatch = match("Assist Me!!", seeds)
        assertNotNull(punctuationMatch)
        assertEquals("seed_assist_me", punctuationMatch?.id)

        val prefixMatch = match("call my brother please", seeds)
        assertNotNull(prefixMatch)
        assertEquals("seed_call_brother", prefixMatch?.id)
    }

    @Test
    fun testAiScriptGeneratorFallbackPhoneCall() {
        val prompt = "When I say call my brother, call 0612345678 and show toast called out nouh !"
        val result = AiAutomationScriptGenerator.generateFallbackEntity(prompt)

        assertEquals("call my brother", result.trigger)
        assertEquals(AutomationActionType.PHONE_CALL, result.actionType)
        assertEquals("0612345678", result.targetNumber)
        assertEquals("called out nouh", result.toastMessage)
        assertFalse(result.copyToClipboard)
    }

    @Test
    fun testAiScriptGeneratorFallbackOpenApp() {
        val prompt = "When I say watch lofi, open YouTube and toast Enjoy the beats"
        val result = AiAutomationScriptGenerator.generateFallbackEntity(prompt)

        assertEquals("watch lofi", result.trigger)
        assertEquals(AutomationActionType.OPEN_APP, result.actionType)
        assertEquals("YouTube", result.targetApp)
        assertEquals("Enjoy the beats", result.toastMessage)
    }

    @Test
    fun testAiScriptGeneratorFallbackDeviceVolume() {
        val prompt = "When I say quiet time, set volume to 10 and toast Quiet time active"
        val result = AiAutomationScriptGenerator.generateFallbackEntity(prompt)

        assertEquals("quiet time", result.trigger)
        assertEquals(AutomationActionType.DEVICE_CONTROL, result.actionType)
        assertEquals("set_volume(10)", result.scriptCode)
        assertEquals("Quiet time active", result.toastMessage)
    }

    @Test
    fun testAiScriptGeneratorFallbackScreenAssist() {
        val prompt = "When I say assist me, summarize screen and copy to clipboard"
        val result = AiAutomationScriptGenerator.generateFallbackEntity(prompt)

        assertEquals("assist me", result.trigger)
        assertEquals(AutomationActionType.SCREEN_ASSIST, result.actionType)
        assertTrue(result.copyToClipboard)
        assertEquals("📋 Screen Assist copied to clipboard!", result.toastMessage)
    }

    @Test
    fun testClipboardHelperMemoryCache() {
        val sampleText = "Screen Assist Output: Success resolution at 12345"
        ClipboardHelper.recordExternalCopy(sampleText)

        // Simulating cache check
        val cached = ClipboardHelper.getRecentClipboardText(
            context = null,
            maxAgeMs = 60_000L
        )

        assertEquals(sampleText, cached)
    }

    @Test
    fun testAutomationEntitySerialization() {
        val entity = AutomationEntity(
            id = "custom_1",
            trigger = "night mode",
            title = "Activate Night Mode",
            description = "Dim display and mute",
            actionType = AutomationActionType.DEVICE_CONTROL,
            scriptCode = "set_brightness(10)\nset_volume(0)",
            toastMessage = "Goodnight!",
            copyToClipboard = false
        )

        val encoded = json.encodeToString(entity)
        val decoded = json.decodeFromString<AutomationEntity>(encoded)

        assertEquals(entity.id, decoded.id)
        assertEquals(entity.trigger, decoded.trigger)
        assertEquals(entity.actionType, decoded.actionType)
        assertEquals(entity.scriptCode, decoded.scriptCode)
        assertEquals(entity.toastMessage, decoded.toastMessage)
    }

    @Test
    fun testNewAppDestinationsExist() {
        assertEquals("Voice & Automations", AppDestination.AUTOMATIONS.title)
        assertEquals("Setup & Permissions", AppDestination.PERMISSIONS_GUIDE.title)
    }
}
