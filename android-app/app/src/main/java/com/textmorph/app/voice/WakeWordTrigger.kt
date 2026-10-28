package com.textmorph.app.voice

/**
 * Represents a matched wake-word trigger phrase detected offline by Sherpa-ONNX KWS.
 */
sealed class WakeWordTrigger(val keyword: String, val displayName: String) {
    object Computer : WakeWordTrigger(KEYWORD_COMPUTER, "Computer")
    object HeyTamim : WakeWordTrigger(KEYWORD_HEY_TAMIM, "Hey Tamim")
    data class Custom(val rawKeyword: String) : WakeWordTrigger(rawKeyword, rawKeyword)

    companion object {
        const val KEYWORD_COMPUTER = "computer"
        const val KEYWORD_HEY_TAMIM = "hey_tamim"

        /**
         * Normalizes raw keyword from Sherpa-ONNX result (e.g. '@computer', '@hey_tamim', 'computer')
         * into a structured [WakeWordTrigger].
         */
        fun fromKeyword(raw: String): WakeWordTrigger {
            val normalized = raw.trim().removePrefix("@").lowercase()
            return when {
                normalized == KEYWORD_COMPUTER || normalized.contains("computer") -> Computer
                normalized == KEYWORD_HEY_TAMIM || normalized.contains("tamim") -> HeyTamim
                else -> Custom(normalized)
            }
        }
    }
}
