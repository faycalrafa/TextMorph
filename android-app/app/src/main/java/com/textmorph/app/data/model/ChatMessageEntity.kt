package com.textmorph.app.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a persistent message in a chat conversation thread.
 */
@Serializable
data class ChatMessageEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String, // "user", "assistant", "system", "action"
    val content: String,
    val thoughtContent: String? = null,
    val attachmentsJson: String = "[]", // List<AttachmentItem> serialized
    val actionPayloadJson: String? = null,
    val actionStatus: String? = null, // "pending", "running", "success", "failed"
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ChatAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String,
    val contentSnippet: String? = null,
    val localUri: String? = null,
    val sizeBytes: Long = 0L
)
