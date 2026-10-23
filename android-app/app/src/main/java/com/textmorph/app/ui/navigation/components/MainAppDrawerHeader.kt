package com.textmorph.app.ui.navigation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.util.SystemTelemetryManager

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainAppDrawerHeader(
    settings: AppSettings,
    isDrawerOpen: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var telemetry by remember { mutableStateOf(SystemTelemetryManager.getTelemetry(context)) }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            telemetry = SystemTelemetryManager.getTelemetry(context)
        }
    }

    val activeModelTitle = when (settings.selectedEngine) {
        EngineType.CLOUD_OPENAI -> {
            "Cloud: ${settings.cloudModelId}"
        }
        EngineType.ON_DEVICE_LLAMA_CPP -> {
            val cleanName = settings.activeModelName.removeSuffix(".gguf")
            val visionSuffix = if (!settings.activeMmprojName.isNullOrBlank()) " + Vision" else ""
            "GGUF: $cleanName$visionSuffix (${settings.preferredAccelerator})"
        }
        else -> {
            val cleanName = settings.activeModelName.removeSuffix(".litertlm")
            "LiteRT: $cleanName (${settings.preferredAccelerator})"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "TextMorph Telemetry",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = activeModelTitle,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Hardware Telemetry Badges
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Text(
                    text = "RAM: ${telemetry.ramUsedPercent}% (${telemetry.ramUsedMb}MB)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    fontSize = 10.sp
                )
            }

            val cpuStr = telemetry.cpuPercent?.let { "CPU: $it%" } ?: "CPU: Active"
            val tempStr = telemetry.cpuTempCelsius?.let { " • ${it.toInt()}°C" } ?: ""
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Text(
                    text = "$cpuStr$tempStr",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    fontSize = 10.sp
                )
            }

            telemetry.gpuTempCelsius?.let { gpuTemp ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "GPU: ${gpuTemp.toInt()}°C",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
