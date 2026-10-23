package com.textmorph.app.util

import android.content.Context
import android.os.Build
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashLogManager(private val context: Context) {

    private val TAG = "CrashLogManager"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val maxCrashReports = 10

    private val crashesDir: File
        get() = File(context.filesDir, "crashes").apply {
            if (!exists()) {
                mkdirs()
            }
        }

    @Synchronized
    fun recordCrash(
        thread: Thread,
        throwable: Throwable,
        crashType: String = "UNCAUGHT_EXCEPTION",
        activeModelName: String? = null,
        activeBackend: String? = null
    ): CrashReport {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)
        val memString = "${usedMem}MB / ${maxMem}MB"

        val now = System.currentTimeMillis()
        val timeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))

        val recentLogStrings = try {
            AppLogger.logsState.value.takeLast(40).map { item ->
                "[${item.formattedTime}] ${item.level.name.first()}/${item.tag}: ${item.message}"
            }
        } catch (e: Exception) {
            emptyList()
        }

        val report = CrashReport(
            timestamp = now,
            crashTimeFormatted = timeFormatted,
            crashType = crashType,
            exceptionClass = throwable.javaClass.name,
            message = throwable.message ?: "No error message provided",
            stackTrace = stackTrace,
            threadName = thread.name,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            memoryUsageMb = memString,
            activeModelName = activeModelName,
            activeBackend = activeBackend,
            recentLogs = recentLogStrings
        )

        saveCrashReport(report)
        pruneOldReports()
        return report
    }

    @Synchronized
    fun recordSyntheticCrash(
        title: String,
        message: String,
        crashType: String = "NATIVE_CRASH_OR_KILL",
        activeModelName: String? = null,
        activeBackend: String? = null
    ): CrashReport {
        val now = System.currentTimeMillis()
        val timeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))

        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)

        val report = CrashReport(
            timestamp = now,
            crashTimeFormatted = timeFormatted,
            crashType = crashType,
            exceptionClass = title,
            message = message,
            stackTrace = "Synthetic crash captured from native flight recorder:\n$message",
            threadName = "NativeEngineThread",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            memoryUsageMb = "${usedMem}MB / ${maxMem}MB",
            activeModelName = activeModelName,
            activeBackend = activeBackend,
            recentLogs = emptyList()
        )

        saveCrashReport(report)
        pruneOldReports()
        return report
    }

    private fun saveCrashReport(report: CrashReport) {
        try {
            val file = File(crashesDir, "crash_${report.timestamp}.json")
            val content = json.encodeToString(report)
            FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync() // Guarantee physical flush to flash storage before process termination
            }
            AppLogger.i(TAG, "Crash report recorded successfully to: ${file.name}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to persist crash report to disk: ${e.message}", e)
        }
    }

    @Synchronized
    fun getCrashReports(): List<CrashReport> {
        val reports = mutableListOf<CrashReport>()
        try {
            val files = crashesDir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".json") }
                ?: return emptyList()

            files.sortedByDescending { it.lastModified() }.forEach { file ->
                try {
                    val content = file.readText()
                    val report = json.decodeFromString<CrashReport>(content)
                    reports.add(report)
                } catch (parseEx: Exception) {
                    AppLogger.w(TAG, "Failed parsing crash file ${file.name}: ${parseEx.message}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error listing crash reports: ${e.message}", e)
        }
        return reports
    }

    @Synchronized
    fun clearCrashes() {
        try {
            val files = crashesDir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".json") }
            files?.forEach { it.delete() }
            AppLogger.i(TAG, "All stored crash reports cleared.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed clearing crash reports: ${e.message}", e)
        }
    }

    private fun pruneOldReports() {
        try {
            val files = crashesDir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".json") }
                ?: return
            if (files.size > maxCrashReports) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - maxCrashReports)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed pruning old crash reports: ${e.message}")
        }
    }

    fun formatCrashForExport(report: CrashReport): String {
        val sb = StringBuilder()
        sb.append("=== TextMorph Crash Report ===\n")
        sb.append("Crash ID: ${report.id}\n")
        sb.append("Time: ${report.crashTimeFormatted}\n")
        sb.append("Crash Type: ${report.crashType}\n")
        sb.append("Exception: ${report.exceptionClass}\n")
        sb.append("Message: ${report.message}\n")
        sb.append("Thread: ${report.threadName}\n")
        sb.append("Device: ${report.deviceModel} (${report.androidVersion})\n")
        sb.append("Memory at Crash: ${report.memoryUsageMb}\n")
        if (!report.activeModelName.isNullOrBlank()) {
            sb.append("Active Model: ${report.activeModelName} (Backend: ${report.activeBackend ?: "Unknown"})\n")
        }
        sb.append("\n--- Stack Trace ---\n")
        sb.append(report.stackTrace)
        if (report.recentLogs.isNotEmpty()) {
            sb.append("\n--- Recent Logs Leading to Crash ---\n")
            report.recentLogs.forEach { logLine ->
                sb.append(logLine).append("\n")
            }
        }
        sb.append("==============================\n")
        return sb.toString()
    }

    companion object {
        @Volatile
        private var instance: CrashLogManager? = null

        fun getInstance(context: Context): CrashLogManager {
            return instance ?: synchronized(this) {
                instance ?: CrashLogManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
