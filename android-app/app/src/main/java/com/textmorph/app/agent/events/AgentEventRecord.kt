package com.textmorph.app.agent.events

import kotlinx.serialization.Serializable

/**
 * Immutable database entity representing an append-only event record in the SQLite event store.
 */
@Serializable
data class AgentEventRecord(
    val eventId: Long = 0L,
    val sessionId: String,
    val sequenceNumber: Long,
    val eventType: String,
    val payloadJson: String,
    val timestamp: Long = System.currentTimeMillis()
)
