package com.textmorph.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Categorizes the entry point or subsystem that triggered an LLM interaction.
 */
@Serializable
enum class InteractionTriggerSource(val displayName: String) {
    CHAT_CONVERSATION("Chat / Agent"),
    VOICE_AGENT("Voice Agent"),
    CUSTOM_PROMPTS("Custom Prompts"),
    CUSTOM_COMMANDS("Custom Commands"),
    SYSTEM_ACTIVITY("System Activity")
}

/**
 * Immutable historical record of an LLM generation or tool execution across TextMorph.
 */
@Serializable
data class LlmInteractionRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: InteractionTriggerSource,
    val title: String,
    val systemPrompt: String? = null,
    val userPrompt: String,
    val rawOutput: String,
    val cleanOutput: String? = null,
    val thoughtContent: String? = null,
    val modelName: String? = null,
    val tokensPerSecond: Float? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null,
    val actionStepsJson: String? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null
)
