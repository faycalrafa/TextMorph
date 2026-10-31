package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.SystemTelemetry
import com.textmorph.app.util.SystemTelemetryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TAG = "LocalHardwareTelemetryBar"

/**
 * Compact, single-line telemetry banner displayed beneath the Chat top bar for local inference models.
 * Shows live CPU usage, RAM utilization, thermal state, and token generation speed (tps).
 */
@Composable
fun LocalHardwareTelemetryBar(
    isGenerating: Boolean,
    currentTps: Float? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var telemetry by remember { mutableStateOf<SystemTelemetry?>(null) }

    LaunchedEffect(isGenerating) {
        val intervalMs = if (isGenerating) 1200L else 3000L
        while (isActive) {
            try {
                val data = withContext(Dispatchers.IO) {
                    SystemTelemetryManager.getTelemetry(context)
                }
                telemetry = data
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to read system telemetry", t)
            }
            delay(intervalMs)
        }
    }

    val currentData = telemetry ?: return

    val cpuText = currentData.cpuPercent?.let { "$it%" } ?: "--"
    val ramText = "${currentData.ramUsedMb}MB (${currentData.ramUsedPercent}%)"
    val tempCelsius = currentData.cpuTempCelsius ?: currentData.gpuTempCelsius

    val tempColor = when {
        tempCelsius == null -> Color(0xFF9CA3AF)
        tempCelsius > 65f -> Color(0xFFEF4444) // Hot
        tempCelsius > 48f -> Color(0xFFF59E0B) // Warm
        else -> Color(0xFF10B981) // Normal
    }

    val tempText = tempCelsius?.let { String.format(Locale.US, "%.0f°C", it) } ?: "--"

    val speedText = when {
        isGenerating && currentTps != null && currentTps > 0f ->
            String.format(Locale.US, "%.1f tok/s", currentTps)
        isGenerating -> "Active"
        else -> "Idle"
    }

    val speedColor = if (isGenerating) Color(0xFF60A5FA) else Color(0xFF9CA3AF)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1020))
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // CPU
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "CPU",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF6B7280),
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = cpuText,
                fontSize = 10.sp,
                color = Color(0xFFD1D5DB),
                fontFamily = FontFamily.Monospace
            )
        }

        // RAM
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "RAM",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF6B7280),
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = ramText,
                fontSize = 10.sp,
                color = Color(0xFFD1D5DB),
                fontFamily = FontFamily.Monospace
            )
        }

        // Thermal
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "TEMP",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF6B7280),
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = tempText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = tempColor,
                fontFamily = FontFamily.Monospace
            )
        }

        // Speed / Status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(speedColor, CircleShape)
            )
            Text(
                text = speedText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = speedColor,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
