package com.textmorph.app.agent.voice

/**
 * Low-latency streaming sentence accumulator:
 * Buffers incoming LLM tokens and yields complete, sanitized sentences as soon as
 * a sentence terminator (. ! ? or paragraph break) is detected, enabling Android TTS
 * to begin speaking almost instantaneously while generation is still in progress.
 */
class VoiceSentenceBuffer {

    private val buffer = StringBuilder()
    private var sentenceIndex = 0

    /**
     * Appends a new token from the LLM stream.
     * Returns a list of newly completed, sanitized sentences ready for TTS queueing.
     */
    fun appendToken(token: String): List<String> {
        buffer.append(token)
        val completed = mutableListOf<String>()

        while (true) {
            val text = buffer.toString()
            val boundaryIndex = findSentenceBoundary(text)
            if (boundaryIndex < 0) break

            val rawSentence = text.substring(0, boundaryIndex + 1)
            buffer.delete(0, boundaryIndex + 1)

            val sanitized = VoiceTextSanitizer.sanitizeForTts(rawSentence)
            if (sanitized.isNotBlank()) {
                sentenceIndex++
                completed.add(sanitized)
            }
        }

        return completed
    }

    /**
     * Flushes any remaining characters in the buffer when the LLM stream finishes.
     * Returns the final sanitized sentence chunk, or null if empty.
     */
    fun flush(): String? {
        val remaining = buffer.toString()
        buffer.clear()
        val sanitized = VoiceTextSanitizer.sanitizeForTts(remaining)
        return if (sanitized.isNotBlank()) {
            sentenceIndex++
            sanitized
        } else {
            null
        }
    }

    /**
     * Clears internal buffer and resets sentence count.
     */
    fun reset() {
        buffer.clear()
        sentenceIndex = 0
    }

    val totalSentencesEmitted: Int
        get() = sentenceIndex

    companion object {
        /**
         * Identifies a sentence boundary: (. ! ? or \n\n) followed by whitespace, end-of-string,
         * or closing quote/parenthesis.
         */
        fun findSentenceBoundary(text: String): Int {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '.' || c == '!' || c == '?') {
                    // Lookahead: must be followed by whitespace, newline, or quote
                    val nextIdx = i + 1
                    if (nextIdx < text.length) {
                        val next = text[nextIdx]
                        if (next == ' ' || next == '\t' || next == '\n' || next == '"' || next == '\'') {
                            // Check not an abbreviation (e.g. e.g., i.e., vs., Dr., Mr.)
                            if (!isCommonAbbreviation(text, i)) {
                                return i
                            }
                        }
                    }
                } else if (c == '\n' && i + 1 < text.length && text[i + 1] == '\n') {
                    // Double newline is a natural paragraph boundary
                    return i
                }
                i++
            }
            return -1
        }

        private fun isCommonAbbreviation(text: String, dotIndex: Int): Boolean {
            val start = (dotIndex - 4).coerceAtLeast(0)
            val sub = text.substring(start, dotIndex + 1).lowercase()
            return sub.endsWith("e.g.") ||
                    sub.endsWith("i.e.") ||
                    sub.endsWith("vs.") ||
                    sub.endsWith("dr.") ||
                    sub.endsWith("mr.") ||
                    sub.endsWith("ms.")
        }
    }
}
