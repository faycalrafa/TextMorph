package com.textmorph.app.util

import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogItem(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val stackTrace: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
}

object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_LOGS = 1000
    private const val MAX_LOG_FILE_SIZE = 1024 * 1024L // 1 MB cap
    private val idCounter = AtomicLong(0)
    private val logs = mutableListOf<LogItem>()
    private val _logsState = MutableStateFlow<List<LogItem>>(emptyList())
    val logsState: StateFlow<List<LogItem>> = _logsState.asStateFlow()

    private var logFile: java.io.File? = null
    private val diskExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger-DiskWriter").apply { isDaemon = true }
    }

    /**
     * Initializes disk-buffered logging so logs survive app exits and crashes.
     */
    fun init(context: android.content.Context) {
        try {
            val logsDir = java.io.File(context.filesDir, "logs").apply {
                if (!exists()) mkdirs()
            }
            val file = java.io.File(logsDir, "app_logs.log")
            logFile = file

            if (file.exists() && file.length() > 0) {
                // Re-hydrate recent log lines from disk
                try {
                    val lines = file.readLines().takeLast(100)
                    val restoredItems = mutableListOf<LogItem>()
                    val now = System.currentTimeMillis()
                    for (line in lines) {
                        if (line.isBlank()) continue
                        restoredItems.add(
                            LogItem(
                                id = idCounter.incrementAndGet(),
                                timestamp = now,
                                level = when {
                                    line.contains(" E/") -> LogLevel.ERROR
                                    line.contains(" W/") -> LogLevel.WARN
                                    line.contains(" I/") -> LogLevel.INFO
                                    else -> LogLevel.DEBUG
                                },
                                tag = "SavedLog",
                                message = line
                            )
                        )
                    }
                    synchronized(this) {
                        logs.addAll(restoredItems)
                        _logsState.value = logs.toList()
                    }
                } catch (readEx: Exception) {
                    android.util.Log.w(TAG, "Failed reading previous logs from disk: ${readEx.message}")
                }
            }
            i(TAG, "Persistent disk logger initialized: ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed initializing persistent logger: ${e.message}", e)
        }
    }

    @Synchronized
    private fun addLog(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val stackTrace = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        val threadName = Thread.currentThread().name
        val enrichedMessage = if (threadName != "main") "[$threadName] $message" else message

        val item = LogItem(
            id = idCounter.incrementAndGet(),
            level = level,
            tag = tag,
            message = enrichedMessage,
            stackTrace = stackTrace
        )
        logs.add(item)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        _logsState.value = logs.toList()

        // Asynchronously persist to disk
        val currentFile = logFile
        if (currentFile != null) {
            diskExecutor.execute {
                try {
                    if (currentFile.exists() && currentFile.length() > MAX_LOG_FILE_SIZE) {
                        val backup = java.io.File(currentFile.parentFile, "app_logs.1.log")
                        if (backup.exists()) backup.delete()
                        currentFile.renameTo(backup)
                    }
                    val line = buildString {
                        append("[${item.formattedTime}] ${item.level.name.first()}/${item.tag}: ${item.message}\n")
                        if (!item.stackTrace.isNullOrBlank()) {
                            append(item.stackTrace).append("\n")
                        }
                    }
                    java.io.FileOutputStream(currentFile, true).use { fos ->
                        fos.write(line.toByteArray(Charsets.UTF_8))
                    }
                } catch (diskEx: Exception) {
                    android.util.Log.w(TAG, "Failed appending log to disk: ${diskEx.message}")
                }
            }
        }
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        Log.d(tag, message, throwable)
        addLog(LogLevel.DEBUG, tag, message, throwable)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addLog(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        addLog(LogLevel.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        addLog(LogLevel.ERROR, tag, message, throwable)
    }

    @Synchronized
    fun clear() {
        logs.clear()
        _logsState.value = emptyList()
    }

    /**
     * Dumps the device system logcat entries for the current app PID.
     * Useful for diagnosing native LiteRT-LM JNI, OpenCL/Vulkan drivers, and Android OS events.
     */
    suspend fun dumpSystemLogcat(): Int = withContext(Dispatchers.IO) {
        var addedCount = 0
        try {
            val pid = Process.myPid().toString()
            val command = arrayOf("logcat", "-d", "-v", "time", "--pid=$pid")
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.destroy()

            val now = System.currentTimeMillis()
            val newItems = mutableListOf<LogItem>()

            for (line in lines.takeLast(300)) {
                if (line.isBlank()) continue
                val level = when {
                    line.contains(" E/") || line.contains(" E ") -> LogLevel.ERROR
                    line.contains(" W/") || line.contains(" W ") -> LogLevel.WARN
                    line.contains(" I/") || line.contains(" I ") -> LogLevel.INFO
                    else -> LogLevel.DEBUG
                }
                newItems.add(
                    LogItem(
                        id = idCounter.incrementAndGet(),
                        timestamp = now,
                        level = level,
                        tag = "SystemLogcat",
                        message = line
                    )
                )
                addedCount++
            }

            synchronized(this@AppLogger) {
                logs.addAll(newItems)
                while (logs.size > MAX_LOGS) {
                    logs.removeAt(0)
                }
                _logsState.value = logs.toList()
            }
            i(TAG, "Dumped $addedCount system logcat lines for PID $pid")
        } catch (e: Exception) {
            e(TAG, "Failed to dump system logcat: ${e.message}", e)
        }
        addedCount
    }

    fun formatForExport(versionName: String): String {
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemMb = runtime.maxMemory() / (1024 * 1024)

        val sb = StringBuilder()
        sb.append("=== TextMorph Diagnostic Logs ===\n")
        sb.append("App Version: $versionName\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
        sb.append("Memory Usage: ${usedMemMb}MB / ${maxMemMb}MB\n")
        sb.append("Export Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        sb.append("Total Entries: ${_logsState.value.size}\n")
        sb.append("------------------------------------\n\n")

        _logsState.value.forEach { item ->
            val levelChar = item.level.name.first()
            sb.append("[${item.formattedTime}] $levelChar/${item.tag}: ${item.message}\n")
            if (!item.stackTrace.isNullOrBlank()) {
                sb.append("${item.stackTrace}\n")
            }
        }
        return sb.toString()
    }
}
