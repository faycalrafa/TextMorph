package com.textmorph.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.HardwarePropertiesManager
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile

data class SystemTelemetry(
    val ramUsedPercent: Int = 0,
    val ramUsedMb: Long = 0,
    val ramTotalMb: Long = 0,
    val cpuPercent: Int? = null,
    val cpuTempCelsius: Float? = null,
    val gpuTempCelsius: Float? = null
)

/**
 * Utility to sample system hardware telemetry (RAM %, CPU %, and device temperature)
 * with robust fallbacks and proximity logging.
 */
object SystemTelemetryManager {

    private const val TAG = "SystemTelemetry"
    private var lastCpuSampleTime = 0L
    private var lastIdleTime = 0L
    private var lastTotalTime = 0L
    private var cachedCpuPercent: Int? = null

    fun getTelemetry(context: Context): SystemTelemetry {
        val (ramPercent, usedMb, totalMb) = getRamUsage(context)
        val cpuUsage = getCpuUsage()
        val (cpuTemp, gpuTemp) = getTemperatures(context)

        return SystemTelemetry(
            ramUsedPercent = ramPercent,
            ramUsedMb = usedMb,
            ramTotalMb = totalMb,
            cpuPercent = cpuUsage,
            cpuTempCelsius = cpuTemp,
            gpuTempCelsius = gpuTemp
        )
    }

    private fun getRamUsage(context: Context): Triple<Int, Long, Long> {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)

            val totalBytes = memInfo.totalMem
            val availBytes = memInfo.availMem
            val usedBytes = totalBytes - availBytes

            val totalMb = totalBytes / (1024 * 1024)
            val usedMb = usedBytes / (1024 * 1024)
            val percent = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0

            Triple(percent, usedMb, totalMb)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed retrieving RAM usage: ${e.message}")
            Triple(0, 0L, 0L)
        }
    }

    private fun getCpuUsage(): Int? {
        val now = SystemClock.uptimeMillis()
        if (now - lastCpuSampleTime < 1000L && cachedCpuPercent != null) {
            return cachedCpuPercent
        }

        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine() ?: ""
            reader.close()

            val tokens = line.split("\\s+".toRegex()).drop(1)
            if (tokens.size >= 7) {
                val user = tokens[0].toLongOrNull() ?: 0L
                val nice = tokens[1].toLongOrNull() ?: 0L
                val system = tokens[2].toLongOrNull() ?: 0L
                val idle = tokens[3].toLongOrNull() ?: 0L
                val iowait = tokens[4].toLongOrNull() ?: 0L
                val irq = tokens[5].toLongOrNull() ?: 0L
                val softirq = tokens[6].toLongOrNull() ?: 0L

                val currentIdle = idle + iowait
                val currentTotal = user + nice + system + idle + iowait + irq + softirq

                val totalDiff = currentTotal - lastTotalTime
                val idleDiff = currentIdle - lastIdleTime

                lastTotalTime = currentTotal
                lastIdleTime = currentIdle
                lastCpuSampleTime = now

                if (totalDiff > 0) {
                    val usage = (((totalDiff - idleDiff) * 100) / totalDiff).toInt().coerceIn(0, 100)
                    cachedCpuPercent = usage
                    usage
                } else {
                    cachedCpuPercent
                }
            } else {
                null
            }
        } catch (e: Exception) {
            // /proc/stat can be restricted by SELinux on Android 8+
            AppLogger.d(TAG, "Cannot read /proc/stat (SELinux restricted): ${e.message}")
            null
        }
    }

    private fun getTemperatures(context: Context): Pair<Float?, Float?> {
        var cpuTemp: Float? = null
        var gpuTemp: Float? = null

        // Try official HardwarePropertiesManager if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val hardwareProps = context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as? HardwarePropertiesManager
                val cpuTemps = hardwareProps?.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT
                )
                if (cpuTemps != null && cpuTemps.isNotEmpty() && cpuTemps[0] > 0 && cpuTemps[0] < 120) {
                    cpuTemp = cpuTemps[0]
                }

                val gpuTemps = hardwareProps?.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT
                )
                if (gpuTemps != null && gpuTemps.isNotEmpty() && gpuTemps[0] > 0 && gpuTemps[0] < 120) {
                    gpuTemp = gpuTemps[0]
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "HardwarePropertiesManager temperature unavailable: ${e.message}")
            }
        }

        // Fallback: Read thermal zones from sysfs
        if (cpuTemp == null) {
            try {
                val thermalDir = File("/sys/class/thermal")
                if (thermalDir.exists() && thermalDir.isDirectory) {
                    val zones = thermalDir.listFiles { file -> file.name.startsWith("thermal_zone") }
                    zones?.sortedBy { it.name }?.forEach { zone ->
                        if (cpuTemp == null) {
                            val tempFile = File(zone, "temp")
                            val typeFile = File(zone, "type")
                            if (tempFile.canRead()) {
                                val type = if (typeFile.canRead()) typeFile.readText().trim().lowercase() else ""
                                val raw = tempFile.readText().trim().toFloatOrNull() ?: 0f
                                val tempC = if (raw > 1000f) raw / 1000f else raw
                                if (tempC in 15f..115f) {
                                    if (type.contains("cpu") || type.contains("soc") || type.contains("tsens")) {
                                        cpuTemp = tempC
                                    } else if (type.contains("gpu")) {
                                        gpuTemp = tempC
                                    } else {
                                        cpuTemp = tempC
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "sysfs thermal zone reading restricted: ${e.message}")
            }
        }

        // Fallback: Universal Battery Temperature (accessible on 100% of Android devices)
        if (cpuTemp == null) {
            try {
                val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val raw = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                if (raw > 0) {
                    val tempC = raw / 10.0f
                    if (tempC in 15f..80f) {
                        cpuTemp = tempC
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Battery temp read skipped: ${e.message}")
            }
        }

        return Pair(cpuTemp, gpuTemp)
    }

    /**
     * Returns best-effort device temperature in Celsius (CPU or Battery).
     */
    fun getDeviceTemperature(context: Context): Float? {
        val (cpu, gpu) = getTemperatures(context)
        return cpu ?: gpu
    }
}
