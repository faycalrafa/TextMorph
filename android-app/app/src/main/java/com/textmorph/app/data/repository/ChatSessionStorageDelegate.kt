package com.textmorph.app.data.repository

import android.util.AtomicFile
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.ChatSessionEntity
import com.textmorph.app.util.AppLogger
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Handles thread-safe atomic serialization of chat session metadata and message histories.
 */
class ChatSessionStorageDelegate(
    private val sessionsDir: File,
    private val indexFile: File,
    private val json: Json
) {
    companion object {
        private const val TAG = "ChatSessionStorage"
    }

    fun saveIndexToDisk(sessions: List<ChatSessionEntity>) {
        try {
            val atomicFile = AtomicFile(indexFile)
            val stream = atomicFile.startWrite()
            try {
                stream.write(json.encodeToString(sessions).toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                atomicFile.failWrite(stream)
                throw e
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed writing session index to disk: ${e.message}", e)
        }
    }

    fun saveMessagesToDisk(sessionId: String, messages: List<ChatMessageEntity>) {
        try {
            val target = File(sessionsDir, "session_$sessionId.json")
            val atomicFile = AtomicFile(target)
            val stream = atomicFile.startWrite()
            try {
                stream.write(json.encodeToString(messages).toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                atomicFile.failWrite(stream)
                throw e
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed saving messages for $sessionId: ${e.message}", e)
        }
    }
}
