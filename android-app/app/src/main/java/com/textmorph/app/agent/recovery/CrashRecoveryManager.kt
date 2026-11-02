package com.textmorph.app.agent.recovery

import android.content.Context
import com.textmorph.app.agent.core.ExecutionClaim
import com.textmorph.app.agent.core.WriteAheadClaimManager
import com.textmorph.app.agent.events.AgentEventStore
import com.textmorph.app.agent.events.CrashRecoveryEvent
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Startup crash recovery manager for the TextMorph Agentic Engine.
 * Sweeps orphaned execution claims following process death (e.g. Android LMK)
 * and safely transitions state machines out of zombie states.
 */
class CrashRecoveryManager private constructor(context: Context) {

    private val TAG = "CrashRecoveryManager"
    private val claimManager = WriteAheadClaimManager.getInstance(context)
    private val eventStore = AgentEventStore.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile
        private var instance: CrashRecoveryManager? = null

        fun getInstance(context: Context): CrashRecoveryManager {
            return instance ?: synchronized(this) {
                instance ?: CrashRecoveryManager(context).also { instance = it }
            }
        }
    }

    /**
     * Sweeps all orphaned claims and repairs interrupted sessions.
     * Should be called on application launch and service startup.
     */
    suspend fun sweepAndRecover(): Int = withContext(Dispatchers.IO) {
        var recoveredCount = 0
        try {
            val orphans = claimManager.getOrphanedClaims()
            if (orphans.isEmpty()) {
                AppLogger.d(TAG, "Zero orphaned execution claims detected on startup.")
                return@withContext 0
            }

            AppLogger.w(TAG, "Detected ${orphans.size} orphaned claims after process termination. Starting recovery...")

            for (orphan in orphans) {
                recoverOrphanedClaim(orphan)
                recoveredCount++
            }
            AppLogger.i(TAG, "Successfully processed $recoveredCount orphaned claims.")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error during crash recovery sweep: ${t.message}", t)
        }
        recoveredCount
    }

    private suspend fun recoverOrphanedClaim(orphan: ExecutionClaim) {
        try {
            val sessionId = orphan.sessionId
            val stepIndex = orphan.currentStepIndex
            val phase = orphan.phase

            AppLogger.w(TAG, "Recovering session $sessionId interrupted at step $stepIndex during phase $phase")

            val recoveryAction = when (phase) {
                ExecutionClaim.PHASE_INFERRING -> "RETRY_INFERENCE"
                ExecutionClaim.PHASE_EXECUTING -> "VERIFY_ACTION"
                else -> "RESET_IDLE"
            }

            val recoveryEvent = CrashRecoveryEvent(
                sessionId = sessionId,
                recoveredStepIndex = stepIndex,
                recoveryAction = recoveryAction,
                diagnosticMessage = "Process terminated unexpectedly during $phase. Orphaned lease duration expired."
            )

            // Append crash recovery event to audit log
            eventStore.appendEvent(
                sessionId = sessionId,
                eventType = recoveryEvent.eventType,
                payloadJson = json.encodeToString(recoveryEvent)
            )

            // Release the stale lease
            claimManager.releaseClaim(sessionId)

            AppLogger.i(TAG, "Session $sessionId recovered successfully (Action=$recoveryAction)")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed recovering orphan claim for ${orphan.sessionId}: ${t.message}", t)
        }
    }
}
