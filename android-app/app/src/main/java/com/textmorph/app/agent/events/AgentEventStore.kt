package com.textmorph.app.agent.events

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thread-safe append-only Event Store for TextMorph's Agentic Engine.
 * Implements Write-Ahead Logging (WAL) backed persistence and reactive event broadcasting.
 */
class AgentEventStore private constructor(context: Context) {

    private val dbHelper = AgentEventDatabaseHelper(context.applicationContext)
    private val mutex = Mutex()
    private val _eventFlow = MutableSharedFlow<AgentEventRecord>(extraBufferCapacity = 128)
    val eventFlow: SharedFlow<AgentEventRecord> = _eventFlow.asSharedFlow()

    companion object {
        private const val TAG = "AgentEventStore"
        @Volatile
        private var instance: AgentEventStore? = null

        fun getInstance(context: Context): AgentEventStore {
            return instance ?: synchronized(this) {
                instance ?: AgentEventStore(context).also { instance = it }
            }
        }
    }

    /**
     * Appends an event to the append-only event store for a session.
     * Automatically assigns the next monotonically increasing sequence number.
     */
    suspend fun appendEvent(
        sessionId: String,
        eventType: String,
        payloadJson: String
    ): AgentEventRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val nextSeq = getNextSequenceNumber(sessionId)
                val now = System.currentTimeMillis()

                val values = ContentValues().apply {
                    put(AgentEventDatabaseHelper.COL_SESSION_ID, sessionId)
                    put(AgentEventDatabaseHelper.COL_SEQUENCE_NUM, nextSeq)
                    put(AgentEventDatabaseHelper.COL_EVENT_TYPE, eventType)
                    put(AgentEventDatabaseHelper.COL_PAYLOAD_JSON, payloadJson)
                    put(AgentEventDatabaseHelper.COL_TIMESTAMP, now)
                }

                val rowId = db.insertOrThrow(AgentEventDatabaseHelper.TABLE_EVENTS, null, values)
                val record = AgentEventRecord(
                    eventId = rowId,
                    sessionId = sessionId,
                    sequenceNumber = nextSeq,
                    eventType = eventType,
                    payloadJson = payloadJson,
                    timestamp = now
                )
                _eventFlow.tryEmit(record)
                record
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed appending event '$eventType' for session $sessionId: ${t.message}", t)
                null
            }
        }
    }

    /**
     * Reads all events for a given session sorted by sequence number.
     */
    suspend fun getEventsForSession(
        sessionId: String,
        sinceSequence: Long = 0L
    ): List<AgentEventRecord> = withContext(Dispatchers.IO) {
        val results = mutableListOf<AgentEventRecord>()
        try {
            val db = dbHelper.readableDatabase
            val cursor: Cursor = db.query(
                AgentEventDatabaseHelper.TABLE_EVENTS,
                null,
                "${AgentEventDatabaseHelper.COL_SESSION_ID} = ? AND ${AgentEventDatabaseHelper.COL_SEQUENCE_NUM} >= ?",
                arrayOf(sessionId, sinceSequence.toString()),
                null,
                null,
                "${AgentEventDatabaseHelper.COL_SEQUENCE_NUM} ASC"
            )
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_EVENT_ID)
                val sessIdx = it.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_SESSION_ID)
                val seqIdx = it.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_SEQUENCE_NUM)
                val typeIdx = it.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_EVENT_TYPE)
                val payloadIdx = it.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_PAYLOAD_JSON)
                val timeIdx = it.getColumnIndexOrThrow(AgentEventDatabaseHelper.COL_TIMESTAMP)

                while (it.moveToNext()) {
                    results.add(
                        AgentEventRecord(
                            eventId = it.getLong(idIdx),
                            sessionId = it.getString(sessIdx),
                            sequenceNumber = it.getLong(seqIdx),
                            eventType = it.getString(typeIdx),
                            payloadJson = it.getString(payloadIdx),
                            timestamp = it.getLong(timeIdx)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error querying events for session $sessionId: ${t.message}", t)
        }
        results
    }

    /**
     * Determines the next sequence number for a session.
     */
    private fun getNextSequenceNumber(sessionId: String): Long {
        return try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT MAX(${AgentEventDatabaseHelper.COL_SEQUENCE_NUM}) FROM ${AgentEventDatabaseHelper.TABLE_EVENTS} WHERE ${AgentEventDatabaseHelper.COL_SESSION_ID} = ?",
                arrayOf(sessionId)
            )
            cursor.use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    it.getLong(0) + 1L
                } else {
                    1L
                }
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error calculating next sequence number for $sessionId: ${t.message}", t)
            1L
        }
    }

    /**
     * Purges all events for a session (e.g. during session deletion).
     */
    suspend fun purgeSessionEvents(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                val count = db.delete(
                    AgentEventDatabaseHelper.TABLE_EVENTS,
                    "${AgentEventDatabaseHelper.COL_SESSION_ID} = ?",
                    arrayOf(sessionId)
                )
                AppLogger.d(TAG, "Purged $count events for session $sessionId")
                true
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed purging events for session $sessionId: ${t.message}", t)
                false
            }
        }
    }
}
