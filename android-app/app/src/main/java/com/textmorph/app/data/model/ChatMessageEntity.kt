package com.textmorph.app.data.model

import com.textmorph.app.agent.model.AgentTaskStep
import kotlinx.serialization.Serializable

/**
 * Represents a persistent message in a chat conversation thread with performance telemetry.
 */
@Serializable
data class ChatMessageEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String, // "user", "assistant", "system", "action"
    val content: String,
    val thoughtContent: String? = null,
    val rawNakedOutput: String? = null,
    val systemPrompt: String? = null,
    val attachmentsJson: String = "[]", // List<AttachmentItem> serialized
    val actionPayloadJson: String? = null,
    val actionStatus: String? = null, // "pending", "running", "success", "failed", "interrupted"
    val actionStepsJson: String? = null, // List<AgentTaskStep> serialized
    val timestamp: Long = System.currentTimeMillis(),
    val tokensPerSecond: Float? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null
) {
    fun getActionSteps(): List<AgentTaskStep> {
        return AgentTaskStep.deserializeList(actionStepsJson)
    }
}

@Serializable
data class ChatAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String,
    val contentSnippet: String? = null,
    val localUri: String? = null,
    val sizeBytes: Long = 0L
)
