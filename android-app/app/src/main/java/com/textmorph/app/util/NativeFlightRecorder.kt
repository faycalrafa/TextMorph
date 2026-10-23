package com.textmorph.app.util

import android.content.Context
import android.os.Process
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable
data class FlightMarker(
    val pid: Int,
    val timestamp: Long,
    val operation: String, // e.g. "LITERT_GPU_INFERENCE", "LLAMA_CPP_INIT", "MODEL_MMAP"
    val modelName: String?,
    val backend: String?,
    val details: String? = null
)

object NativeFlightRecorder {

    private const val TAG = "NativeFlightRecorder"
    private const val FLIGHT_FILE = "active_native_flight.json"
    private val json = Json { ignoreUnknownKeys = true }

    private fun getMarkerFile(context: Context): File {
        val dir = File(context.filesDir, "crashes")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FLIGHT_FILE)
    }

    @Synchronized
    fun recordFlightStart(
        context: Context,
        operation: String,
        modelName: String? = null,
        backend: String? = null,
        details: String? = null
    ) {
        try {
            val file = getMarkerFile(context)
            val marker = FlightMarker(
                pid = Process.myPid(),
                timestamp = System.currentTimeMillis(),
                operation = operation,
                modelName = modelName,
                backend = backend,
                details = details
            )
            val content = json.encodeToString(marker)
            FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            AppLogger.d(TAG, "Flight start marker written for $operation on $backend ($modelName)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed writing flight start marker: ${e.message}")
        }
    }

    @Synchronized
    fun recordFlightEnd(context: Context) {
        try {
            val file = getMarkerFile(context)
            if (file.exists()) {
                file.delete()
                AppLogger.d(TAG, "Flight end marker cleared successfully.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed clearing flight marker: ${e.message}")
        }
    }

    @Synchronized
    fun checkAndRecoverPreviousCrash(context: Context, crashLogManager: CrashLogManager) {
        try {
            val file = getMarkerFile(context)
            if (!file.exists()) return

            val content = file.readText()
            val marker = json.decodeFromString<FlightMarker>(content)
            val currentPid = Process.myPid()

            // If the marker belongs to a previous PID that terminated without clearing the marker
            if (marker.pid != currentPid) {
                AppLogger.e(
                    TAG,
                    "Detected abrupt process termination during native operation: ${marker.operation} on ${marker.backend} (model: ${marker.modelName}, PID: ${marker.pid})"
                )
                crashLogManager.recordSyntheticCrash(
                    title = "NativeAbortOrSignalKill",
                    message = "Process ${marker.pid} terminated abruptly during native operation '${marker.operation}'. Possible Vulkan/OpenCL driver SIGSEGV or out-of-memory kill.",
                    crashType = "NATIVE_CRASH_OR_KILL",
                    activeModelName = marker.modelName,
                    activeBackend = marker.backend
                )
            }
            // Clear the old marker
            file.delete()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed checking previous flight marker: ${e.message}")
        }
    }
}
