package com.textmorph.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private const val MAX_LOGS = 1000
    private val logs = mutableListOf<LogItem>()
    private val _logsState = MutableStateFlow<List<LogItem>>(emptyList())
    val logsState: StateFlow<List<LogItem>> = _logsState.asStateFlow()

    @Synchronized
    private fun addLog(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val stackTrace = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        val item = LogItem(
            level = level,
            tag = tag,
            message = message,
            stackTrace = stackTrace
        )
        logs.add(item)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        _logsState.value = logs.toList()
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addLog(LogLevel.DEBUG, tag, message)
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

    fun formatForExport(versionName: String): String {
        val sb = StringBuilder()
        sb.append("=== TextMorph Diagnostic Logs ===\n")
        sb.append("App Version: $versionName\n")
        sb.append("Export Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")
        logs.forEach { item ->
            sb.append("[${item.formattedTime}] [${item.level}] [${item.tag}] ${item.message}\n")
            if (!item.stackTrace.isNullOrBlank()) {
                sb.append("${item.stackTrace}\n")
            }
        }
        return sb.toString()
    }
}
