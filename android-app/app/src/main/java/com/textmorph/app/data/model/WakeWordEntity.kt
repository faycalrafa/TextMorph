package com.textmorph.app.data.model

import kotlinx.serialization.Serializable

/**
 * Data entity representing a wake-word trigger phrase detected offline by Sherpa-ONNX.
 *
 * @property id Unique identifier (e.g. "computer", "hey_tamim", "jarvis", "custom_1").
 * @property displayName User-visible label (e.g. "Computer", "Hey Tamim", "Jarvis").
 * @property phonemes CMU phonetic token sequence(s) compatible with tokens.txt (may be multi-line).
 * @property aliasTag Tag identifier returned by Sherpa-ONNX (e.g. "@computer", "@hey_tamim").
 * @property isBuiltIn True for pre-packaged triggers, false for custom user additions.
 * @property isCalibrated True if the user has successfully passed the 3-attempt acoustic test.
 * @property isEnabled True if currently active in the detector (at most 2 can be enabled simultaneously).
 * @property testPassCount Number of successful test passes (0 to 3).
 */
@Serializable
data class WakeWordEntity(
    val id: String,
    val displayName: String,
    val phonemes: String,
    val aliasTag: String,
    val isBuiltIn: Boolean = true,
    val isCalibrated: Boolean = false,
    val isEnabled: Boolean = false,
    val testPassCount: Int = 0
)
