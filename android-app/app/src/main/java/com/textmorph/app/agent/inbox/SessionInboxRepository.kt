package com.textmorph.app.agent.inbox

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
 * Thread-safe repository managing the durable Session Inbox.
 * Enables queuing of user prompts and real-time injection of steering directives
 * at step boundaries without interrupting active background jobs.
 */
class SessionInboxRepository private constructor(context: Context) {

    private val dbHelper = AgentEventDatabaseHelper(context.applicationContext)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "SessionInboxRepo"
        @Volatile
        private var instance: SessionInboxRepository? = null

        fun getInstance(context: Context): SessionInboxRepository {
            return instance ?: synchronized(this) {
                instance ?: SessionInboxRepository(context).also { instance = it }
            }
        }
    }

    /**
     * Enqueues a new prompt or steering directive into the durable inbox.
     */
    suspend fun enqueue(
        sessionId: String,
        prompt: String,
        mode: AdmissionMode,
        attachmentsJson: String = "[]"
    ): InboxItem? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put(AgentEventDatabaseHelper.COL_INBOX_SESSION_ID, sessionId)
                    put(AgentEventDatabaseHelper.COL_INBOX_PROMPT, prompt)
                    put(AgentEventDatabaseHelper.COL_INBOX_MODE, mode.name)
                    put(AgentEventDatabaseHelper.COL_INBOX_ATTACHMENTS, attachmentsJson)
                    put(AgentEventDatabaseHelper.COL_INBOX_STATUS, InboxItem.STATUS_PENDING)
                    put(AgentEventDatabaseHelper.COL_INBOX_CREATED_AT, now)
                }

                val rowId = db.insertOrThrow(AgentEventDatabaseHelper.TABLE_INBOX, null, values)
                AppLogger.d(TAG, "Enqueued inbox item #$rowId [$mode] for session $sessionId")
                InboxItem(
                    inboxId = rowId,
                    sessionId = sessionId,
                    prompt = prompt,
                    admissionMode = mode,
                    attachmentsJson = attachmentsJson,
                    status = InboxItem.STATUS_PENDING,
                    createdAt = now
                )
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed enqueuing inbox item for session $sessionId: ${t.message}", t)
                null
            }
        }
    }

    /**
     * Checks if there is a pending STEER directive for the session.
     * Steer directives take absolute priority over standard QUEUE prompts.
     */
    suspend fun peekActiveSteer(sessionId: String): InboxItem? = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.readableDatabase
            val cursor: Cursor = db.query(
                AgentEventDatabaseHelper.TABLE_INBOX,
                null,
                "${AgentEventDatabaseHelper.COL_INBOX_SESSION_ID} = ? AND ${AgentEventDatabaseHelper.COL_INBOX_STATUS} = ? AND ${AgentEventDatabaseHelper.COL_INBOX_MODE} = ?",
                arrayOf(sessionId, InboxItem.STATUS_PENDING, AdmissionMode.STEER.name),
                null,
                null,
                "${AgentEventDatabaseHelper.COL_INBOX_CREATED_AT} ASC",
                "1"
            )
            cursor.use {
                if (it.moveToFirst()) parseCursorItem(it) else null
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error checking active steer directive for session $sessionId: ${t.message}", t)
            null
        }
    }

    /**
     * Retrieves the next pending item for execution (STEER first, then oldest QUEUE).
     */
    suspend fun peekNextPending(sessionId: String): InboxItem? = withContext(Dispatchers.IO) {
        val steer = peekActiveSteer(sessionId)
        if (steer != null) return@withContext steer

        try {
            val db = dbHelper.readableDatabase
            val cursor: Cursor = db.query(
                AgentEventDatabaseHelper.TABLE_INBOX,
                null,
                "${AgentEventDatabaseHelper.COL_INBOX_SESSION_ID} = ? AND ${AgentEventDatabaseHelper.COL_INBOX_STATUS} = ?",
                arrayOf(sessionId, InboxItem.STATUS_PENDING),
                null,
                null,
                "${AgentEventDatabaseHelper.COL_INBOX_CREATED_AT} ASC",
                "1"
            )
            cursor.use {
                if (it.moveToFirst()) parseCursorItem(it) else null
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error fetching next pending item for session $sessionId: ${t.message}", t)
            null
        }
    }

    /**
     * Marks an inbox item as consumed once its step or execution has processed it.
     */
    suspend fun markConsumed(inboxId: Long): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put(AgentEventDatabaseHelper.COL_INBOX_STATUS, InboxItem.STATUS_CONSUMED)
                    put(AgentEventDatabaseHelper.COL_INBOX_PROCESSED_AT, now)
                }
                val count = db.update(
                    AgentEventDatabaseHelper.TABLE_INBOX,
                    values,
                    "${AgentEventDatabaseHelper.COL_INBOX_ID} = ?",
                    arrayOf(inboxId.toString())
                )
                count > 0
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed marking inbox item #$inboxId as consumed: ${t.message}", t)
                false
            }
        }
    }

    /**
     * Cancels any pending items for a session.
     */
    suspend fun cancelPending(sessionId: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val values = ContentValues().apply {
                    put(AgentEventDatabaseHelper.COL_INBOX_STATUS, InboxItem.STATUS_CANCELLED)
                    put(AgentEventDatabaseHelper.COL_INBOX_PROCESSED_AT, System.currentTimeMillis())
                }
                db.update(
                    AgentEventDatabaseHelper.TABLE_INBOX,
                    values,
                    "${AgentEventDatabaseHelper.COL_INBOX_SESSION_ID} = ? AND ${AgentEventDatabaseHelper.COL_INBOX_STATUS} = ?",
                    arrayOf(sessionId, InboxItem.STATUS_PENDING)
                )
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed cancelling pending items for session $sessionId: ${t.message}", t)
                0
            }
        }
    }

    private fun parseCursorItem(cursor: Cursor): InboxItem {
        val modeStr = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_MODE))
        val mode = try {
            AdmissionMode.valueOf(modeStr)
        } catch (e: Exception) {
            AdmissionMode.QUEUE
        }

        return InboxItem(
            inboxId = cursor.getLong(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_ID)),
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_SESSION_ID)),
            prompt = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_PROMPT)),
            admissionMode = mode,
            attachmentsJson = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_ATTACHMENTS)),
            status = cursor.getString(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_STATUS)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_CREATED_AT)),
            processedAt = if (!cursor.isNull(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_PROCESSED_AT))) {
                cursor.getLong(cursor.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_INBOX_PROCESSED_AT))
            } else null
        )
    }
}
