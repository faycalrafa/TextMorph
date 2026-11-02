package com.textmorph.app.agent.benchmark

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Persistent repository managing Agentic Readiness Suite evaluation reports.
 * Stores historical and latest benchmark scores keyed by model identifier or name.
 */
class BenchmarkRepository private constructor(private val context: Context) {

    private val mutex = Mutex()
    private val TAG = "BenchmarkRepository"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val storageFile: File
        get() = File(context.filesDir, "benchmark_reports_v1.json")

    private val _reportsState = MutableStateFlow<Map<String, AgenticReadinessReport>>(emptyMap())
    val reportsState: StateFlow<Map<String, AgenticReadinessReport>> = _reportsState.asStateFlow()

    init {
        loadReports()
    }

    private fun loadReports() {
        try {
            if (storageFile.exists()) {
                val content = storageFile.readText()
                val parsed = json.decodeFromString<Map<String, AgenticReadinessReport>>(content)
                _reportsState.value = parsed
                AppLogger.d(TAG, "Loaded ${parsed.size} benchmark reports from disk.")
            } else {
                _reportsState.value = emptyMap()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading benchmark reports: ${e.message}", e)
            _reportsState.value = emptyMap()
        }
    }

    suspend fun saveReport(report: AgenticReadinessReport) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = normalizeKey(report.modelName)
            val updated = _reportsState.value.toMutableMap()
            updated[key] = report
            _reportsState.value = updated
            saveToDisk(updated)
            AppLogger.i(TAG, "Saved benchmark report for '$key' (Score: ${report.totalScore}/100)")
        }
    }

    fun getReport(modelName: String): AgenticReadinessReport? {
        val key = normalizeKey(modelName)
        return _reportsState.value[key]
    }

    fun getScore(modelName: String): Int? {
        val key = normalizeKey(modelName)
        return _reportsState.value[key]?.totalScore
    }

    private fun normalizeKey(raw: String): String {
        return raw.trim().lowercase().removePrefix("cloud: ").trim()
    }

    private fun saveToDisk(reports: Map<String, AgenticReadinessReport>) {
        val atomicFile = AtomicFile(storageFile)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            val content = json.encodeToString(reports)
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed writing benchmark reports to disk: ${e.message}", e)
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: BenchmarkRepository? = null

        fun getInstance(context: Context): BenchmarkRepository {
            return instance ?: synchronized(this) {
                instance ?: BenchmarkRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
