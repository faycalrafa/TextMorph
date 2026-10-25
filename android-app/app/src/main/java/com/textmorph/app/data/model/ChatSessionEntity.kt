package com.textmorph.app.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a persistent chat conversation session thread with context compaction state.
 */
@Serializable
data class ChatSessionEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "New Conversation",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelName: String = "",
    val engineType: String = "",
    val totalTokens: Int = 0,
    val isPinned: Boolean = false,
    val isAgentMode: Boolean = false,
    val summary: String? = null,
    val sessionState: String? = null,
    val maxContextTokens: Int = 8192
)
