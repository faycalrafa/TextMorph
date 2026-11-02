package com.textmorph.app.agent.core

import kotlinx.serialization.Serializable

/**
 * Represents an active execution lease on a session to ensure crash-safe step execution.
 */
@Serializable
data class ExecutionClaim(
    val sessionId: String,
    val executionId: String,
    val currentStepIndex: Int,
    val phase: String, // PERCEIVING, INFERRING, EXECUTING, WAITING_PERMISSION, SUSPENDED
    val claimedAt: Long = System.currentTimeMillis(),
    val heartbeatAt: Long = System.currentTimeMillis(),
    val leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
    val pendingActionJson: String? = null
) {
    companion object {
        const val DEFAULT_LEASE_DURATION_MS = 45_000L

        const val PHASE_PERCEIVING = "PERCEIVING"
        const val PHASE_INFERRING = "INFERRING"
        const val PHASE_EXECUTING = "EXECUTING"
        const val PHASE_WAITING_PERMISSION = "WAITING_PERMISSION"
        const val PHASE_SUSPENDED = "SUSPENDED"
    }

    val isExpired: Boolean
        get() = (System.currentTimeMillis() - heartbeatAt) > leaseDurationMs
}
