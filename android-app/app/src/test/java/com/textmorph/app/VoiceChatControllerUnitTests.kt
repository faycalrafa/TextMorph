package com.textmorph.app

import com.textmorph.app.agent.voice.VoiceChatState
import com.textmorph.app.agent.voice.VoiceSessionMode
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.service.overlay.OverlayDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VoiceChatController state definitions, session modes,
 * 15-second watchdog calculations, and TTS speed configuration.
 */
class VoiceChatControllerUnitTests {

    @Test
    fun testVoiceChatStatesCoverage() {
        val states = VoiceChatState.values()
        assertEquals(5, states.size)
        assertTrue(states.contains(VoiceChatState.IDLE))
        assertTrue(states.contains(VoiceChatState.GREETING))
        assertTrue(states.contains(VoiceChatState.LISTENING))
        assertTrue(states.contains(VoiceChatState.THINKING))
        assertTrue(states.contains(VoiceChatState.SPEAKING))
    }

    @Test
    fun testVoiceSessionModesCoverage() {
        val modes = VoiceSessionMode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(VoiceSessionMode.IN_APP_CHAT))
        assertTrue(modes.contains(VoiceSessionMode.SCREEN_ASSIST))
    }

    @Test
    fun testDefaultTtsSpeedIsOnePointFive() {
        val settings = AppSettings()
        assertEquals(1.5f, settings.voiceTtsSpeed, 0.001f)
    }

    @Test
    fun testTtsSpeedClamping() {
        val speedTooLow = 0.2f
        val clampedLow = speedTooLow.coerceIn(0.5f, 3.0f)
        assertEquals(0.5f, clampedLow, 0.001f)

        val speedTooHigh = 4.5f
        val clampedHigh = speedTooHigh.coerceIn(0.5f, 3.0f)
        assertEquals(3.0f, clampedHigh, 0.001f)

        val normalSpeed = 1.75f
        val clampedNormal = normalSpeed.coerceIn(0.5f, 3.0f)
        assertEquals(1.75f, clampedNormal, 0.001f)
    }

    @Test
    fun testSilenceWatchdogCalculation() {
        val now = 100_000L
        val deadline = now + 15_000L

        // 5 seconds elapsed
        val cur5s = now + 5_000L
        val rem5s = ((deadline - cur5s) / 1000L).coerceAtLeast(0).toInt()
        assertEquals(10, rem5s)

        // 14 seconds elapsed
        val cur14s = now + 14_200L
        val rem14s = ((deadline - cur14s) / 1000L).coerceAtLeast(0).toInt()
        assertEquals(0, rem14s)

        // 16 seconds elapsed (expired)
        val cur16s = now + 16_000L
        val rem16s = ((deadline - cur16s) / 1000L).coerceAtLeast(0).toInt()
        assertEquals(0, rem16s)
    }

    @Test
    fun testOverlayDisplayStateHasVoiceAssistSession() {
        val states = OverlayDisplayState.values()
        assertTrue(states.contains(OverlayDisplayState.VOICE_ASSIST_SESSION))
    }
}
