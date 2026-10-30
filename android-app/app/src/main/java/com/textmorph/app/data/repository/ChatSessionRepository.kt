package com.textmorph.app.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.ChatSessionEntity
import com.textmorph.app.data.model.ContextUsageMetrics
import com.textmorph.app.llm.context.ContextPruner
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.TokenEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Thread-safe multi-session chat repository with atomic JSON persistence.
 * Enforces zero-silent-loss and strict under-500-lines modularity.
 */
class ChatSessionRepository private constructor(private val context: Context) {

    private val TAG = "ChatSessionRepository"
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sessionsDir: File
        get() = File(context.filesDir, "chat_sessions").apply { if (!exists()) mkdirs() }

    private val indexFile: File
        get() = File(context.filesDir, "chat_sessions_index.json")

    private val storageDelegate by lazy {
        ChatSessionStorageDelegate(sessionsDir, indexFile, json)
    }

    private val _sessionsState = MutableStateFlow<List<ChatSessionEntity>>(emptyList())
    val sessionsState: StateFlow<List<ChatSessionEntity>> = _sessionsState.asStateFlow()
    val sessions: StateFlow<List<ChatSessionEntity>> get() = sessionsState

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _currentMessages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessageEntity>> = _currentMessages.asStateFlow()

    private val _activeGenerationStatus = MutableStateFlow<String?>(null)
    val activeGenerationStatus: StateFlow<String?> = _activeGenerationStatus.asStateFlow()

    fun setGenerationStatus(status: String?) {
        _activeGenerationStatus.value = status
    }

    suspend fun switchSession(sessionId: String) = selectSession(sessionId)

    suspend fun clearSessionMessages(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _currentMessages.value = emptyList()
            saveMessagesToDisk(sessionId, emptyList())
            AppLogger.d(TAG, "Cleared messages for session $sessionId")
        }
    }

    init {
        repositoryScope.launch {
            loadAllSessions()
        }
    }

    suspend fun loadAllSessions() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!indexFile.exists()) {
                    AppLogger.d(TAG, "No chat session index found. Creating initial default session.")
                    val defaultSession = ChatSessionEntity(title = "Default Chat")
                    _sessionsState.value = listOf(defaultSession)
                    _activeSessionId.value = defaultSession.id
                    saveIndexToDisk(listOf(defaultSession))
                    saveMessagesToDisk(defaultSession.id, emptyList())
                    return@withContext
                }
                val content = indexFile.readText()
                val parsed = json.decodeFromString<List<ChatSessionEntity>>(content)
                val migrated = parsed.map { session ->
                    if (session.maxContextTokens <= 2048) session.copy(maxContextTokens = 8192) else session
                }
                val sorted = migrated.sortedByDescending { it.updatedAt }
                if (sorted.isEmpty()) {
                    val defaultSession = ChatSessionEntity(title = "Default Chat")
                    _sessionsState.value = listOf(defaultSession)
                    _activeSessionId.value = defaultSession.id
                    saveIndexToDisk(listOf(defaultSession))
                    saveMessagesToDisk(defaultSession.id, emptyList())
                    return@withContext
                }
                _sessionsState.value = sorted
                AppLogger.i(TAG, "Loaded ${sorted.size} chat sessions from storage.")

                // Automatically restore the most recent active session
                if (_activeSessionId.value == null && sorted.isNotEmpty()) {
                    val mostRecent = sorted.first()
                    _activeSessionId.value = mostRecent.id
                    loadMessagesInternal(mostRecent.id)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed loading chat session index: ${e.message}", e)
                _sessionsState.value = emptyList()
            }
        }
    }

    suspend fun createSession(
        title: String = "New Conversation",
        modelName: String = "",
        engineType: String = ""
    ): ChatSessionEntity = withContext(Dispatchers.IO) {
        mutex.withLock {
            val newSession = ChatSessionEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                modelName = modelName,
                engineType = engineType
            )
            val updatedList = listOf(newSession) + _sessionsState.value
            _sessionsState.value = updatedList
            _activeSessionId.value = newSession.id
            _currentMessages.value = emptyList()

            saveIndexToDisk(updatedList)
            saveMessagesToDisk(newSession.id, emptyList())
            AppLogger.i(TAG, "Created new chat session: ${newSession.id} ('${newSession.title}')")
            newSession
        }
    }

    suspend fun getOrCreateVoiceAgentSession(): ChatSessionEntity = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = _sessionsState.value.firstOrNull {
                it.id == VOICE_AGENT_SESSION_ID || it.title == VOICE_AGENT_SESSION_TITLE
            }
            if (existing != null) {
                return@withLock existing
            }
            val newSession = ChatSessionEntity(
                id = VOICE_AGENT_SESSION_ID,
                title = VOICE_AGENT_SESSION_TITLE,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isAgentMode = true
            )
            val updatedList = listOf(newSession) + _sessionsState.value
            _sessionsState.value = updatedList
            saveIndexToDisk(updatedList)
            saveMessagesToDisk(newSession.id, emptyList())
            AppLogger.i(TAG, "Created dedicated Voice Agent session: ${newSession.id}")
            newSession
        }
    }

    suspend fun selectSession(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = _sessionsState.value.find { it.id == sessionId }
            if (session == null) {
                AppLogger.w(TAG, "Session $sessionId not found for selection.")
                return@withContext
            }
            _activeSessionId.value = sessionId
            loadMessagesInternal(sessionId)
            AppLogger.d(TAG, "Selected active chat session: $sessionId ('${session.title}')")
        }
    }

    private fun loadMessagesInternal(sessionId: String) {
        try {
            val sessionFile = File(sessionsDir, "session_$sessionId.json")
            if (!sessionFile.exists()) {
                _currentMessages.value = emptyList()
                return
            }
            val content = sessionFile.readText()
            val messages = json.decodeFromString<List<ChatMessageEntity>>(content)
            _currentMessages.value = messages.sortedBy { it.timestamp }
            AppLogger.d(TAG, "Loaded ${messages.size} messages for session $sessionId.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading session messages for $sessionId: ${e.message}", e)
            _currentMessages.value = emptyList()
        }
    }

    suspend fun appendMessage(message: ChatMessageEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sessionId = message.sessionId
            val existing = _currentMessages.value.toMutableList()
            existing.add(message)
            _currentMessages.value = existing
            saveMessagesToDisk(sessionId, existing)

            // Auto-update session updatedAt and derive title if first user prompt
            val sessions = _sessionsState.value.toMutableList()
            val index = sessions.indexOfFirst { it.id == sessionId }
            if (index != -1) {
                val current = sessions[index]
                var newTitle = current.title
                if ((newTitle == "New Conversation" || newTitle.isBlank()) && message.role == "user") {
                    val clean = message.content.trim().lines().firstOrNull() ?: "Conversation"
                    newTitle = if (clean.length > 32) clean.take(30) + "…" else clean
                }
                val updatedSession = current.copy(
                    updatedAt = System.currentTimeMillis(),
                    title = newTitle
                )
                sessions[index] = updatedSession
                val sorted = sessions.sortedByDescending { it.updatedAt }
                _sessionsState.value = sorted
                saveIndexToDisk(sorted)
            }
            AppLogger.d(TAG, "Appended message ${message.id} to session $sessionId (total=${existing.size})")
        }
    }

    suspend fun updateLastAssistantMessage(
        sessionId: String,
        content: String,
        thoughtContent: String? = null,
        rawNakedOutput: String? = null,
        systemPrompt: String? = null,
        actionPayloadJson: String? = null,
        actionStatus: String? = null,
        actionStepsJson: String? = null,
        tokensPerSecond: Float? = null,
        totalTokens: Int? = null,
        durationMs: Long? = null
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = _currentMessages.value.toMutableList()
            val lastAssistantIndex = existing.indexOfLast { it.role == "assistant" || it.role == "action" }
            if (lastAssistantIndex != -1) {
                val last = existing[lastAssistantIndex]
                existing[lastAssistantIndex] = last.copy(
                    content = content,
                    thoughtContent = thoughtContent ?: last.thoughtContent,
                    rawNakedOutput = rawNakedOutput ?: last.rawNakedOutput,
                    systemPrompt = systemPrompt ?: last.systemPrompt,
                    actionPayloadJson = actionPayloadJson ?: last.actionPayloadJson,
                    actionStatus = actionStatus ?: last.actionStatus,
                    actionStepsJson = actionStepsJson ?: last.actionStepsJson,
                    tokensPerSecond = tokensPerSecond ?: last.tokensPerSecond,
                    totalTokens = totalTokens ?: last.totalTokens,
                    durationMs = durationMs ?: last.durationMs
                )
                _currentMessages.value = existing
                saveMessagesToDisk(sessionId, existing)
            }
        }
    }

    suspend fun renameSession(sessionId: String, newTitle: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _sessionsState.value.map {
                if (it.id == sessionId) it.copy(title = newTitle.trim(), updatedAt = System.currentTimeMillis()) else it
            }.sortedByDescending { it.updatedAt }
            _sessionsState.value = updated
            saveIndexToDisk(updated)
            AppLogger.i(TAG, "Renamed session $sessionId to '$newTitle'")
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _sessionsState.value.filterNot { it.id == sessionId }
            _sessionsState.value = updated
            saveIndexToDisk(updated)

            val sessionFile = File(sessionsDir, "session_$sessionId.json")
            if (sessionFile.exists()) {
                sessionFile.delete()
            }

            if (_activeSessionId.value == sessionId) {
                val next = updated.firstOrNull()
                _activeSessionId.value = next?.id
                if (next != null) {
                    loadMessagesInternal(next.id)
                } else {
                    _currentMessages.value = emptyList()
                }
            }
            AppLogger.i(TAG, "Deleted session $sessionId (remaining=${updated.size})")
        }
    }

    suspend fun setAgentMode(sessionId: String, isAgentMode: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sessions = _sessionsState.value.toMutableList()
            val index = sessions.indexOfFirst { it.id == sessionId }
            if (index != -1) {
                val updated = sessions[index].copy(isAgentMode = isAgentMode, updatedAt = System.currentTimeMillis())
                sessions[index] = updated
                _sessionsState.value = sessions
                saveIndexToDisk(sessions)
                AppLogger.d(TAG, "Updated agent mode to $isAgentMode for session $sessionId")
            }
        }
    }

    suspend fun switchMode(isAgentMode: Boolean) = withContext(Dispatchers.IO) {
        val currentId = _activeSessionId.value ?: _sessionsState.value.firstOrNull()?.id
        if (currentId != null) {
            setAgentMode(currentId, isAgentMode)
            AppLogger.d(TAG, "Toggled agent mode to $isAgentMode for current session $currentId")
        }
    }

    suspend fun recordDiagnosticTurn(
        userPrompt: String,
        systemPrompt: String?,
        rawOutput: String,
        thoughtContent: String? = null,
        actionPayloadJson: String? = null,
        actionStatus: String? = null,
        tokensPerSecond: Float? = null,
        totalTokens: Int? = null,
        durationMs: Long? = null,
        attachmentsJson: String = "[]"
    ) = withContext(Dispatchers.IO) {
        val activeId = _activeSessionId.value ?: _sessionsState.value.firstOrNull()?.id ?: run {
            val created = createSession("Diagnostics Workspace")
            created.id
        }
        val isAgent = _sessionsState.value.firstOrNull { it.id == activeId }?.isAgentMode == true
        val userMsg = ChatMessageEntity(
            sessionId = activeId,
            role = "user",
            content = userPrompt,
            attachmentsJson = attachmentsJson,
            systemPrompt = systemPrompt,
            isAgentMode = isAgent
        )
        val assistantMsg = ChatMessageEntity(
            sessionId = activeId,
            role = "assistant",
            content = rawOutput,
            thoughtContent = thoughtContent,
            rawNakedOutput = rawOutput,
            systemPrompt = systemPrompt,
            actionPayloadJson = actionPayloadJson,
            actionStatus = actionStatus,
            isAgentMode = isAgent,
            tokensPerSecond = tokensPerSecond,
            totalTokens = totalTokens,
            durationMs = durationMs
        )
        appendMessage(userMsg)
        appendMessage(assistantMsg)
        AppLogger.i(TAG, "Recorded diagnostic turn for session $activeId: prompt='${userPrompt.take(40)}...'")
    }

    suspend fun truncateMessages(sessionId: String, messageId: String, inclusive: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = _currentMessages.value.toMutableList()
            val index = existing.indexOfFirst { it.id == messageId }
            if (index != -1) {
                val keepUntil = if (inclusive) index else index + 1
                val truncated = existing.subList(0, keepUntil).toList()
                _currentMessages.value = truncated
                saveMessagesToDisk(sessionId, truncated)
                AppLogger.i(TAG, "Truncated messages for session $sessionId at message $messageId (inclusive=$inclusive, remaining=${truncated.size})")
            }
        }
    }

    fun getActiveSession(): ChatSessionEntity? {
        val activeId = _activeSessionId.value ?: return null
        return _sessionsState.value.find { it.id == activeId }
    }

    suspend fun updateSessionCompactionState(
        sessionId: String,
        summary: String?,
        sessionState: String?
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sessions = _sessionsState.value.toMutableList()
            val index = sessions.indexOfFirst { it.id == sessionId }
            if (index != -1) {
                val current = sessions[index]
                val updatedSession = current.copy(
                    summary = summary ?: current.summary,
                    sessionState = sessionState ?: current.sessionState,
                    updatedAt = System.currentTimeMillis()
                )
                sessions[index] = updatedSession
                val sorted = sessions.sortedByDescending { it.updatedAt }
                _sessionsState.value = sorted
                saveIndexToDisk(sorted)
                AppLogger.d(TAG, "Updated compaction state for session $sessionId")
            }
        }
    }

    suspend fun updateSessionMaxContextTokens(
        sessionId: String,
        maxTokens: Int
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sessions = _sessionsState.value.toMutableList()
            val index = sessions.indexOfFirst { it.id == sessionId }
            if (index != -1) {
                val current = sessions[index]
                val updatedSession = current.copy(
                    maxContextTokens = maxTokens,
                    updatedAt = System.currentTimeMillis()
                )
                sessions[index] = updatedSession
                _sessionsState.value = sessions
                saveIndexToDisk(sessions)
                AppLogger.d(TAG, "Updated maxContextTokens to $maxTokens for session $sessionId")
            }
        }
    }

    suspend fun pruneSessionArtifacts(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sessionFile = File(sessionsDir, "session_$sessionId.json")
            if (!sessionFile.exists()) return@withContext
            try {
                val content = sessionFile.readText()
                val messages = json.decodeFromString<List<ChatMessageEntity>>(content)
                val pruned = messages.map { ContextPruner.pruneMessageEntity(it) }
                saveMessagesToDisk(sessionId, pruned)
                if (_activeSessionId.value == sessionId) {
                    _currentMessages.value = pruned
                }
                AppLogger.i(TAG, "Pruned artifacts from ${pruned.size} messages for session $sessionId")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed pruning artifacts for session $sessionId: ${e.message}", e)
            }
        }
    }

    fun computeActiveContextMetrics(
        currentInput: String = "",
        currentImageCount: Int = 0
    ): ContextUsageMetrics {
        val active = getActiveSession()
        val messages = _currentMessages.value
        return TokenEstimator.computeMetrics(
            systemPrompt = "You are TextMorph, an intelligent on-device AI companion and assistant for Android.",
            summary = active?.summary,
            sessionState = active?.sessionState,
            recentMessages = messages,
            currentInput = currentInput,
            currentImageCount = currentImageCount,
            maxContextTokens = active?.maxContextTokens ?: 131072
        )
    }

    private fun saveIndexToDisk(sessions: List<ChatSessionEntity>) {
        storageDelegate.saveIndexToDisk(sessions)
    }

    private fun saveMessagesToDisk(sessionId: String, messages: List<ChatMessageEntity>) {
        storageDelegate.saveMessagesToDisk(sessionId, messages)
    }

    companion object {
        const val VOICE_AGENT_SESSION_ID = "voice_agent_session_shared"
        const val VOICE_AGENT_SESSION_TITLE = "Voice Agent Missions"

        @Volatile
        private var instance: ChatSessionRepository? = null

        fun getInstance(context: Context): ChatSessionRepository {
            return instance ?: synchronized(this) {
                instance ?: ChatSessionRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
