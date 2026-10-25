package com.textmorph.app.data.model

import androidx.compose.ui.graphics.Color

/**
 * Breakdown of tokens in the active conversational context window.
 */
data class ContextUsageMetrics(
    val systemPromptTokens: Int = 0,
    val compressedHistoryTokens: Int = 0,
    val recentMessagesTokens: Int = 0,
    val currentInputTokens: Int = 0,
    val maxContextTokens: Int = 8192
) {
    val totalUsedTokens: Int
        get() = systemPromptTokens + compressedHistoryTokens + recentMessagesTokens + currentInputTokens

    val freeSpaceTokens: Int
        get() = (maxContextTokens - totalUsedTokens).coerceAtLeast(0)

    val usageFraction: Float
        get() = if (maxContextTokens > 0) {
            (totalUsedTokens.toFloat() / maxContextTokens).coerceIn(0f, 1f)
        } else 0f

    val colorState: ContextColorState
        get() = when {
            usageFraction >= 0.85f -> ContextColorState.CRIMSON
            usageFraction >= 0.60f -> ContextColorState.AMBER
            else -> ContextColorState.SAGE
        }
}

enum class ContextColorState(val colorHex: Long, val label: String) {
    SAGE(0xFF3F4F44, "Ample Headroom"),
    AMBER(0xFFA27B5C, "Approaching Limit"),
    CRIMSON(0xFFE57373, "High Capacity");

    val composeColor: Color
        get() = Color(colorHex)
}
