package com.textmorph.app.ui.settings.sections

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.EngineType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineSettingsSection(
    settings: AppSettings,
    onSelectEngine: (EngineType) -> Unit,
    onSetPreferredAccelerator: (String) -> Unit,
    onSetProbeTimeout: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var probeTimeoutInput by remember(settings.probeTimeoutMs) { mutableStateOf(settings.probeTimeoutMs.toString()) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "Inference Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = settings.selectedEngine == EngineType.ON_DEVICE_LITERT,
                    onClick = { onSelectEngine(EngineType.ON_DEVICE_LITERT) },
                    label = { Text("LiteRT-LM") }
                )
                FilterChip(
                    selected = settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP,
                    onClick = { onSelectEngine(EngineType.ON_DEVICE_LLAMA_CPP) },
                    label = { Text("llama.cpp (GGUF)") }
                )
                FilterChip(
                    selected = settings.selectedEngine == EngineType.CLOUD_OPENAI,
                    onClick = { onSelectEngine(EngineType.CLOUD_OPENAI) },
                    label = { Text("Cloud OpenAI") }
                )
            }

            if (settings.selectedEngine == EngineType.ON_DEVICE_LITERT || settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Hardware Acceleration",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("AUTO" to "Auto (GPU/CPU)", "GPU" to "Force GPU", "CPU" to "Force CPU").forEach { (mode, label) ->
                        val selected = settings.preferredAccelerator.equals(mode, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = { onSetPreferredAccelerator(mode) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Hardware Liveliness Probe Timeout",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Timeout limit in milliseconds for hardware liveliness verification (e.g. 30000 ms for 30s). Prevents false timeouts during slow CPU/GPU tensor compilation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = probeTimeoutInput,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 7) {
                                probeTimeoutInput = input
                            }
                        },
                        label = { Text("Probe Timeout (ms)") },
                        placeholder = { Text("30000") },
                        trailingIcon = {
                            Text(
                                "ms",
                                modifier = Modifier.padding(end = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(0.68f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val ms = probeTimeoutInput.toLongOrNull() ?: 30000L
                            onSetProbeTimeout(ms)
                            Toast.makeText(context, "Probe timeout saved: ${ms}ms", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(5000L to "5s", 15000L to "15s", 30000L to "30s (Default)", 60000L to "60s").forEach { (presetMs, label) ->
                        FilterChip(
                            selected = settings.probeTimeoutMs == presetMs,
                            onClick = {
                                probeTimeoutInput = presetMs.toString()
                                onSetProbeTimeout(presetMs)
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                if (settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "GGUF Multimodal Projector (mmproj)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (!settings.activeMmprojName.isNullOrBlank()) {
                            "Active vision projector: ${settings.activeMmprojName}"
                        } else {
                            "No vision projector attached (Text inference only). You can attach an mmproj-*.gguf projector in the Models tab."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
