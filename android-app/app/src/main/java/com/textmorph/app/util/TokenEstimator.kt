package com.textmorph.app.util

import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.ContextUsageMetrics

/**
 * Fast, allocation-friendly heuristic token estimator for on-device and cloud LLM context windows.
 * Avoids heavy JNI tokenizer overhead while providing accurate capacity visualization.
 */
object TokenEstimator {

    private const val TOKENS_PER_IMAGE = 256
    private const val AVERAGE_CHARS_PER_TOKEN = 3.8

    /**
     * Estimates the token count of a given string snippet.
     * Incorporates word boundaries, punctuation, and structural newlines.
     */
    fun estimateTextTokens(text: CharSequence?): Int {
        if (text.isNullOrEmpty()) return 0
        var tokens = 0
        var inWord = false

        for (i in 0 until text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    if (inWord) inWord = false
                    if (c == '\n') tokens += 1 // Newlines often create discrete tokens
                }
                c in ",.!?;:()[]{}\"'`~@#\$%^&*+-=<>/\\|" -> {
                    inWord = false
                    tokens += 1 // Punctuation/symbols generally form distinct tokens
                }
                else -> {
                    if (!inWord) {
                        inWord = true
                        tokens += 1
                    }
                }
            }
        }

        // Apply length-based fallback check to balance dense alphanumeric runs
        val lengthEstimate = kotlin.math.ceil(text.length / AVERAGE_CHARS_PER_TOKEN).toInt()
        return kotlin.math.max(tokens, lengthEstimate)
    }

    /**
     * Estimates tokens consumed by an image in multimodal models.
     */
    fun estimateImageTokens(imageCount: Int = 1): Int {
        return (imageCount.coerceAtLeast(0)) * TOKENS_PER_IMAGE
    }

    /**
     * Estimates tokens consumed by an individual chat message entity.
     */
    fun estimateMessageTokens(message: ChatMessageEntity): Int {
        var count = 4 // Per-message framing overhead (<|im_start|>, role, newline, <|im_end|>)
        count += estimateTextTokens(message.content)
        if (!message.thoughtContent.isNullOrBlank()) {
            count += estimateTextTokens(message.thoughtContent)
        }
        if (!message.actionPayloadJson.isNullOrBlank()) {
            count += estimateTextTokens(message.actionPayloadJson)
        }
        return count
    }

    /**
     * Computes full context breakdown and usage metrics for a session.
     */
    fun computeMetrics(
        systemPrompt: String?,
        summary: String?,
        sessionState: String?,
        recentMessages: List<ChatMessageEntity>,
        currentInput: String?,
        currentImageCount: Int = 0,
        maxContextTokens: Int = 8192
    ): ContextUsageMetrics {
        val sysTokens = estimateTextTokens(systemPrompt) +
                (if (!sessionState.isNullOrBlank()) estimateTextTokens(sessionState) + 8 else 0)

        val summaryTokens = if (!summary.isNullOrBlank()) {
            estimateTextTokens(summary) + 12 // Include framing tag overhead
        } else {
            0
        }

        var recentTokens = 0
        for (msg in recentMessages) {
            recentTokens += estimateMessageTokens(msg)
        }

        val inputTokens = estimateTextTokens(currentInput) + estimateImageTokens(currentImageCount)

        return ContextUsageMetrics(
            systemPromptTokens = sysTokens,
            compressedHistoryTokens = summaryTokens,
            recentMessagesTokens = recentTokens,
            currentInputTokens = inputTokens,
            maxContextTokens = maxContextTokens
        )
    }
}
