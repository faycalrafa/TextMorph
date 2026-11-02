package com.textmorph.app.agent.inbox

import kotlinx.serialization.Serializable

/**
 * Represents a queued prompt or steering directive stored in the durable Session Inbox.
 */
@Serializable
data class InboxItem(
    val inboxId: Long = 0L,
    val sessionId: String,
    val prompt: String,
    val admissionMode: AdmissionMode,
    val attachmentsJson: String = "[]",
    val status: String = STATUS_PENDING, // PENDING, CONSUMED, CANCELLED
    val createdAt: Long = System.currentTimeMillis(),
    val processedAt: Long? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_CONSUMED = "CONSUMED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
