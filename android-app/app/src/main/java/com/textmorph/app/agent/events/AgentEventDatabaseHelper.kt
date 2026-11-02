package com.textmorph.app.agent.events

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.textmorph.app.util.AppLogger

/**
 * SQLite database helper for the TextMorph Event-Sourced Agentic Engine.
 * Enables Write-Ahead Logging (WAL) by default for high-concurrency non-blocking reads/writes.
 */
class AgentEventDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val TAG = "AgentEventDbHelper"
        const val DATABASE_NAME = "textmorph_agentic_engine.db"
        const val DATABASE_VERSION = 1

        const val TABLE_EVENTS = "agent_events"
        const val COL_EVENT_ID = "event_id"
        const val COL_SESSION_ID = "session_id"
        const val COL_SEQUENCE_NUM = "sequence_number"
        const val COL_EVENT_TYPE = "event_type"
        const val COL_PAYLOAD_JSON = "payload_json"
        const val COL_TIMESTAMP = "timestamp"

        const val TABLE_CLAIMS = "agent_execution_claims"
        const val COL_CLAIM_SESSION_ID = "session_id"
        const val COL_CLAIM_EXECUTION_ID = "execution_id"
        const val COL_CLAIM_STEP_INDEX = "current_step_index"
        const val COL_CLAIM_PHASE = "phase"
        const val COL_CLAIM_CLAIMED_AT = "claimed_at"
        const val COL_CLAIM_HEARTBEAT_AT = "heartbeat_at"
        const val COL_CLAIM_LEASE_MS = "lease_duration_ms"
        const val COL_CLAIM_PENDING_ACTION = "pending_action_json"

        const val TABLE_INBOX = "agent_session_inbox"
        const val COL_INBOX_ID = "inbox_id"
        const val COL_INBOX_SESSION_ID = "session_id"
        const val COL_INBOX_PROMPT = "prompt"
        const val COL_INBOX_MODE = "admission_mode"
        const val COL_INBOX_ATTACHMENTS = "attachments_json"
        const val COL_INBOX_STATUS = "status"
        const val COL_INBOX_CREATED_AT = "created_at"
        const val COL_INBOX_PROCESSED_AT = "processed_at"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try {
            db.enableWriteAheadLogging()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to enable SQLite WAL: ${t.message}", t)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        AppLogger.i(TAG, "Creating TextMorph Agentic Engine database tables...")
        try {
            // 1. Append-Only Event Store Table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                    $COL_EVENT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_SESSION_ID TEXT NOT NULL,
                    $COL_SEQUENCE_NUM INTEGER NOT NULL,
                    $COL_EVENT_TYPE TEXT NOT NULL,
                    $COL_PAYLOAD_JSON TEXT NOT NULL,
                    $COL_TIMESTAMP INTEGER NOT NULL
                );
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_session_seq ON $TABLE_EVENTS($COL_SESSION_ID, $COL_SEQUENCE_NUM);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_timestamp ON $TABLE_EVENTS($COL_TIMESTAMP);")

            // 2. Write-Ahead Execution Claims Table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_CLAIMS (
                    $COL_CLAIM_SESSION_ID TEXT PRIMARY KEY,
                    $COL_CLAIM_EXECUTION_ID TEXT NOT NULL,
                    $COL_CLAIM_STEP_INDEX INTEGER NOT NULL,
                    $COL_CLAIM_PHASE TEXT NOT NULL,
                    $COL_CLAIM_CLAIMED_AT INTEGER NOT NULL,
                    $COL_CLAIM_HEARTBEAT_AT INTEGER NOT NULL,
                    $COL_CLAIM_LEASE_MS INTEGER NOT NULL,
                    $COL_CLAIM_PENDING_ACTION TEXT
                );
                """.trimIndent()
            )

            // 3. Durable Session Inbox Table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_INBOX (
                    $COL_INBOX_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_INBOX_SESSION_ID TEXT NOT NULL,
                    $COL_INBOX_PROMPT TEXT NOT NULL,
                    $COL_INBOX_MODE TEXT NOT NULL,
                    $COL_INBOX_ATTACHMENTS TEXT NOT NULL,
                    $COL_INBOX_STATUS TEXT NOT NULL,
                    $COL_INBOX_CREATED_AT INTEGER NOT NULL,
                    $COL_INBOX_PROCESSED_AT INTEGER
                );
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_inbox_session_status ON $TABLE_INBOX($COL_INBOX_SESSION_ID, $COL_INBOX_STATUS);")

            AppLogger.i(TAG, "Agentic Engine SQLite tables initialized successfully.")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Fatal error creating database tables: ${t.message}", t)
            throw t
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        AppLogger.i(TAG, "Upgrading Agentic Engine database from $oldVersion to $newVersion")
    }
}
