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

    fun loadMessagesFromDisk(sessionId: String): List<ChatMessageEntity> {
        return try {
            val sessionFile = File(sessionsDir, "session_$sessionId.json")
            if (!sessionFile.exists()) return emptyList()
            val content = sessionFile.readText()
            val messages = json.decodeFromString<List<ChatMessageEntity>>(content)
            messages.sortedBy { it.timestamp }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading session messages for $sessionId: ${e.message}", e)
            emptyList()
        }
    }

    fun purgeSessionFiles(sessionId: String, cacheDir: File) {
        try {
            val target = File(sessionsDir, "session_$sessionId.json")
            if (target.exists()) target.delete()
            purgeSessionArtifacts(sessionId, cacheDir)
            AppLogger.d(TAG, "Purged all files for session $sessionId")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error purging files for $sessionId: ${e.message}")
        }
    }

    fun purgeSessionArtifacts(sessionId: String, cacheDir: File) {
        try {
            val safeId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val stagingFile = File(cacheDir, "artifact_staging_$safeId.json")
            if (stagingFile.exists()) stagingFile.delete()
            val tempStaging = File(cacheDir, "artifact_staging_$safeId.json.tmp")
            if (tempStaging.exists()) tempStaging.delete()
            val deepSearchFile = File(cacheDir, "deepsearch_context_$safeId.txt")
            if (deepSearchFile.exists()) deepSearchFile.delete()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error purging artifacts for $sessionId: ${e.message}")
        }
    }
}
