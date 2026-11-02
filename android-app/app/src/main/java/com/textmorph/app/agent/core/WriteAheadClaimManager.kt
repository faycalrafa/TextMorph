package com.textmorph.app.agent.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.textmorph.app.agent.events.AgentEventDatabaseHelper
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thread-safe manager for write-ahead execution claims in the TextMorph Agentic Engine.
 * Leases execution locks per step to prevent race conditions and enable reliable crash recovery.
 */
class WriteAheadClaimManager private constructor(context: Context) {

    private val dbHelper = AgentEventDatabaseHelper(context.applicationContext)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "WriteAheadClaimMgr"
        @Volatile
        private var instance: WriteAheadClaimManager? = null

        fun getInstance(context: Context): WriteAheadClaimManager {
            return instance ?: synchronized(this) {
                instance ?: WriteAheadClaimManager(context).also { instance = it }
            }
        }
    }

    /**
     * Acquires or updates an active execution lease for a session step.
     */
    suspend fun acquireClaim(
        sessionId: String,
        executionId: String,
        stepIndex: Int,
        phase: String = ExecutionClaim.PHASE_PERCEIVING,
        leaseDurationMs: Long = ExecutionClaim.DEFAULT_LEASE_DURATION_MS,
        pendingActionJson: String? = null
    ): ExecutionClaim? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put(AgentEventDatabaseHelper.COL_CLAIM_SESSION_ID, sessionId)
                    put(AgentEventDatabaseHelper.COL_CLAIM_EXECUTION_ID, executionId)
                    put(AgentEventDatabaseHelper.COL_CLAIM_STEP_INDEX, stepIndex)
                    put(AgentEventDatabaseHelper.COL_CLAIM_PHASE, phase)
                    put(AgentEventDatabaseHelper.COL_CLAIM_CLAIMED_AT, now)
                    put(AgentEventDatabaseHelper.COL_CLAIM_HEARTBEAT_AT, now)
                    put(AgentEventDatabaseHelper.COL_CLAIM_LEASE_MS, leaseDurationMs)
                    put(AgentEventDatabaseHelper.COL_CLAIM_PENDING_ACTION, pendingActionJson)
                }

                db.insertWithOnConflict(
                    AgentEventDatabaseHelper.TABLE_CLAIMS,
                    null,
                    values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
                )

                AppLogger.d(TAG, "Acquired execution claim for $sessionId (step=$stepIndex, phase=$phase)")
                ExecutionClaim(
                    sessionId = sessionId,
                    executionId = executionId,
                    currentStepIndex = stepIndex,
                    phase = phase,
                    claimedAt = now,
                    heartbeatAt = now,
                    leaseDurationMs = leaseDurationMs,
                    pendingActionJson = pendingActionJson
                )
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed acquiring claim for session $sessionId: ${t.message}", t)
                null
            }
        }
    }

    /**
     * Updates the heartbeat timestamp for an active claim to prevent lease expiration.
     */
    suspend fun heartbeat(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val values = ContentValues().apply {
                    put(AgentEventDatabaseHelper.COL_CLAIM_HEARTBEAT_AT, System.currentTimeMillis())
                }
                val updated = db.update(
                    AgentEventDatabaseHelper.TABLE_CLAIMS,
                    values,
                    "${AgentEventDatabaseHelper.COL_CLAIM_SESSION_ID} = ?",
                    arrayOf(sessionId)
                )
                updated > 0
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Heartbeat update failed for session $sessionId: ${t.message}")
                false
            }
        }
    }

    /**
     * Updates the phase and optional pending action of an active claim.
     */
    suspend fun updatePhase(
        sessionId: String,
        phase: String,
        pendingActionJson: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val values = ContentValues().apply {
                    put(AgentEventDatabaseHelper.COL_CLAIM_PHASE, phase)
                    put(AgentEventDatabaseHelper.COL_CLAIM_HEARTBEAT_AT, System.currentTimeMillis())
                    if (pendingActionJson != null) {
                        put(AgentEventDatabaseHelper.COL_CLAIM_PENDING_ACTION, pendingActionJson)
                    }
                }
                val updated = db.update(
                    AgentEventDatabaseHelper.TABLE_CLAIMS,
                    values,
                    "${AgentEventDatabaseHelper.COL_CLAIM_SESSION_ID} = ?",
                    arrayOf(sessionId)
                )
                updated > 0
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed updating claim phase to '$phase' for session $sessionId: ${t.message}", t)
                false
            }
        }
    }

    /**
     * Releases an execution claim when a task finishes, pauses, or cancels.
     */
    suspend fun releaseClaim(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val count = db.delete(
                    AgentEventDatabaseHelper.TABLE_CLAIMS,
                    "${AgentEventDatabaseHelper.COL_CLAIM_SESSION_ID} = ?",
                    arrayOf(sessionId)
                )
                AppLogger.d(TAG, "Released execution claim for session $sessionId")
                count > 0
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed releasing claim for session $sessionId: ${t.message}", t)
                false
            }
        }
    }

    /**
     * Queries an active claim for a session.
     */
    suspend fun getClaim(sessionId: String): ExecutionClaim? = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.readableDatabase
            val cursor: Cursor = db.query(
                AgentEventDatabaseHelper.TABLE_CLAIMS,
                null,
                "${AgentEventDatabaseHelper.COL_CLAIM_SESSION_ID} = ?",
                arrayOf(sessionId),
                null,
                null,
                null
            )
            cursor.use {
                if (it.moveToFirst()) parseCursorClaim(it) else null
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error fetching claim for session $sessionId: ${t.message}", t)
            null
        }
    }

    /**
     * Queries all orphaned claims where heartbeat is older than lease duration.
     */
    suspend fun getOrphanedClaims(): List<ExecutionClaim> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ExecutionClaim>()
        try {
            val db = dbHelper.readableDatabase
            val cursor: Cursor = db.query(
                AgentEventDatabaseHelper.TABLE_CLAIMS,
                null,
                null,
                null,
                null,
                null,
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val claim = parseCursorClaim(it)
                    if (claim.isExpired) {
                        list.add(claim)
                    }
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error checking for orphaned claims: ${t.message}", t)
        }
        list
    }

    private fun parseCursorClaim(cursor: Cursor): ExecutionClaim {
        return ExecutionClaim(
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_CLAIM_SESSION_ID)),
            executionId = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_CLAIM_EXECUTION_ID)),
            currentStepIndex = cursor.getInt(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_CLAIM_STEP_INDEX)),
            phase = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_CLAIM_PHASE)),
            claimedAt = cursor.getLong(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_CLAIM_CLAIMED_AT)),
            heartbeatAt = cursor.getLong(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_CLAIM_HEARTBEAT_AT)),
            leaseDurationMs = cursor.getLong(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_CLAIM_LEASE_MS)),
            pendingActionJson = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_CLAIM_PENDING_ACTION))
        )
    }
}
