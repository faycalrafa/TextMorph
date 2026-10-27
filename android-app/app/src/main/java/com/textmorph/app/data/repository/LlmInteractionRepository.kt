package com.textmorph.app.data.repository

import android.content.Context
import android.util.AtomicFile
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.model.LlmInteractionRecord
import com.textmorph.app.util.AppLogger
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

/**
 * Thread-safe singleton repository recording all LLM generations, commands, and agent
 * missions across TextMorph. Supports configurable retention duration and automated daily purging.
 */
class LlmInteractionRepository(private val context: Context) {

    private val TAG = "LlmInteractionRepo"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val storageFile = File(context.filesDir, "llm_interactions.json")
    private val prefs = context.getSharedPreferences("llm_interaction_prefs", Context.MODE_PRIVATE)

    private val _interactions = MutableStateFlow<List<LlmInteractionRecord>>(emptyList())
    val interactions: StateFlow<List<LlmInteractionRecord>> = _interactions.asStateFlow()

    private val _retentionDays = MutableStateFlow(prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS))
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    init {
        scope.launch {
            loadRecordsInternal()
            purgeExpiredRecordsInternal()
        }
    }

    suspend fun recordInteraction(
        source: InteractionTriggerSource,
        title: String,
        userPrompt: String,
        systemPrompt: String? = null,
        rawOutput: String,
        cleanOutput: String? = null,
        thoughtContent: String? = null,
        modelName: String? = null,
        tokensPerSecond: Float? = null,
        totalTokens: Int? = null,
        durationMs: Long? = null,
        actionStepsJson: String? = null,
        isError: Boolean = false,
        errorMessage: String? = null
    ): LlmInteractionRecord = withContext(Dispatchers.IO) {
        val record = LlmInteractionRecord(
            source = source,
            title = title,
            userPrompt = userPrompt,
            systemPrompt = systemPrompt,
            rawOutput = rawOutput,
            cleanOutput = cleanOutput,
            thoughtContent = thoughtContent,
            modelName = modelName,
            tokensPerSecond = tokensPerSecond,
            totalTokens = totalTokens,
            durationMs = durationMs,
            actionStepsJson = actionStepsJson,
            isError = isError,
            errorMessage = errorMessage
        )

        mutex.withLock {
            val updated = listOf(record) + _interactions.value
            _interactions.value = updated.sortedByDescending { it.timestamp }
            saveToDisk(updated)
            AppLogger.d(TAG, "Recorded LLM interaction: source=${source.name}, title='$title', prompt='${userPrompt.take(30)}...'")
        }
        record
    }

    suspend fun setRetentionDays(days: Int) = withContext(Dispatchers.IO) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
        _retentionDays.value = days
        purgeExpiredRecordsInternal()
    }

    suspend fun purgeExpiredRecords() = withContext(Dispatchers.IO) {
        purgeExpiredRecordsInternal()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _interactions.value = emptyList()
            saveToDisk(emptyList())
            AppLogger.i(TAG, "Cleared all LLM interaction records.")
        }
    }

    private suspend fun purgeExpiredRecordsInternal() {
        val days = _retentionDays.value
        if (days >= FOREVER_DAYS) return // Never flush

        val cutoffTimestamp = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
        mutex.withLock {
            val current = _interactions.value
            val retained = current.filter { it.timestamp >= cutoffTimestamp }
            if (retained.size != current.size) {
                _interactions.value = retained
                saveToDisk(retained)
                AppLogger.i(TAG, "Purged ${current.size - retained.size} expired records older than $days day(s).")
            }
        }
    }

    private suspend fun loadRecordsInternal() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!storageFile.exists()) {
                    _interactions.value = emptyList()
                    return@withContext
                }
                val text = storageFile.readText()
                if (text.isBlank()) {
                    _interactions.value = emptyList()
                    return@withContext
                }
                val parsed = json.decodeFromString<List<LlmInteractionRecord>>(text)
                _interactions.value = parsed.sortedByDescending { it.timestamp }
                AppLogger.d(TAG, "Loaded ${_interactions.value.size} interaction records from storage.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed loading interaction records: ${e.message}", e)
                _interactions.value = emptyList()
            }
        }
    }

    private fun saveToDisk(records: List<LlmInteractionRecord>) {
        try {
            val atomicFile = AtomicFile(storageFile)
            val stream = atomicFile.startWrite()
            try {
                stream.write(json.encodeToString(records).toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                atomicFile.failWrite(stream)
                throw e
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed writing interaction records to disk: ${e.message}", e)
        }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 1
        const val FOREVER_DAYS = 9999
        private const val KEY_RETENTION_DAYS = "retention_days"

        @Volatile
        private var instance: LlmInteractionRepository? = null

        fun getInstance(context: Context): LlmInteractionRepository {
            return instance ?: synchronized(this) {
                instance ?: LlmInteractionRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
