package com.textmorph.app.data.model

import com.textmorph.app.agent.model.AgentTaskStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

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
    val isAgentMode: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val tokensPerSecond: Float? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null
) {
    val isAgentTurn: Boolean
        get() = isAgentMode || actionPayloadJson != null || actionStepsJson != null

    fun getActionSteps(): List<AgentTaskStep> {
        return AgentTaskStep.deserializeList(actionStepsJson)
    }

    fun getAttachments(): List<ChatAttachment> {
        if (attachmentsJson.isBlank() || attachmentsJson == "[]") return emptyList()
        return try {
            json.decodeFromString(attachmentsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun serializeAttachments(attachments: List<ChatAttachment>): String {
            if (attachments.isEmpty()) return "[]"
            return try {
                json.encodeToString(attachments)
            } catch (e: Exception) {
                "[]"
            }
        }
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
