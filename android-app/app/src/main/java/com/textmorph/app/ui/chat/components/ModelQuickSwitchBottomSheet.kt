package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.ModelInfo

/**
 * Bottom sheet for rapidly switching the active model from locally detected models
 * and fine-tuning runtime inference settings (temperature, max tokens, top-p, thinking).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelQuickSwitchBottomSheet(
    settings: AppSettings,
    availableModels: List<ModelInfo>,
    onDismiss: () -> Unit,
    onSelectModel: (ModelInfo) -> Unit,
    onSelectEngine: (EngineType) -> Unit,
    onSaveConfig: (maxTokens: Int, temperature: Float, topP: Float, thinking: Boolean, accelerator: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var maxTokens by remember(settings.maxTokens) { mutableIntStateOf(settings.maxTokens) }
    var topP by remember(settings.topP) { mutableFloatStateOf(settings.topP) }
    var thinkingEnabled by remember(settings.enableThinking) { mutableStateOf(settings.enableThinking) }
    var preferredAccelerator by remember(settings.preferredAccelerator) { mutableStateOf(settings.preferredAccelerator) }

    val downloadedModels = availableModels.filter { it.isDownloaded }

    ModalBottomSheet(
        onDismissRequest = {
            onSaveConfig(maxTokens, temperature, topP, thinkingEnabled, preferredAccelerator)
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color(0xFF111827),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Model & Playground Settings",
                        color = Color(0xFFF9FAFB),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = {
                    onSaveConfig(maxTokens, temperature, topP, thinkingEnabled, preferredAccelerator)
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFFE5E7EB).copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Detected Local Models
                item {
                    Text(
                        text = "SWITCH ACTIVE MODEL",
                        color = Color(0xFF60A5FA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (downloadedModels.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1F2937), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0xFF374151), RoundedCornerShape(10.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "No local models detected in storage. Download a model in Model Hub or use Cloud inference.",
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(downloadedModels) { model ->
                        val isSelected = settings.activeModelName.equals(model.name, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFF1F2937) else Color(0x401F2937))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF3B82F6) else Color(0xFF374151),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onSelectModel(model) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = if (isSelected) Color(0xFF3B82F6) else Color(0xFF9CA3AF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.name,
                                    color = if (isSelected) Color(0xFFF9FAFB) else Color(0xFFE5E7EB),
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = "${model.modelFileType.name} • ${String.format("%.1f GB", model.sizeBytes / 1_073_741_824.0)}",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 11.sp
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Cloud Engine Option
                item {
                    val isCloudSelected = settings.selectedEngine == EngineType.CLOUD_OPENAI
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCloudSelected) Color(0xFF1F2937) else Color(0x401F2937))
                            .border(
                                width = if (isCloudSelected) 1.5.dp else 1.dp,
                                color = if (isCloudSelected) Color(0xFF3B82F6) else Color(0xFF374151),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectEngine(EngineType.CLOUD_OPENAI) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "☁️ Cloud OpenAI Compatible (${settings.cloudModelId})",
                            color = if (isCloudSelected) Color(0xFFF9FAFB) else Color(0xFFE5E7EB),
                            fontSize = 14.sp,
                            fontWeight = if (isCloudSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (isCloudSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Section 2: Generation Parameters
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "GENERATION PARAMETERS",
                        color = Color(0xFF60A5FA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                }

                // Temperature Slider
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Temperature", color = Color(0xFFE5E7EB), fontSize = 13.sp)
                            Text(String.format("%.2f", temperature), color = Color(0xFF3B82F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0.0f..1.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF3B82F6),
                                activeTrackColor = Color(0xFF3B82F6),
                                inactiveTrackColor = Color(0xFF374151)
                            )
                        )
                    }
                }

                // Max Tokens Slider
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Max Tokens", color = Color(0xFFE5E7EB), fontSize = 13.sp)
                            Text("$maxTokens", color = Color(0xFF3B82F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = maxTokens.toFloat(),
                            onValueChange = { maxTokens = it.toInt() },
                            valueRange = 128f..4096f,
                            steps = 31,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF3B82F6),
                                activeTrackColor = Color(0xFF3B82F6),
                                inactiveTrackColor = Color(0xFF374151)
                            )
                        )
                    }
                }

                // Top-P Slider
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Top-P", color = Color(0xFFE5E7EB), fontSize = 13.sp)
                            Text(String.format("%.2f", topP), color = Color(0xFF3B82F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = topP,
                            onValueChange = { topP = it },
                            valueRange = 0.1f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF3B82F6),
                                activeTrackColor = Color(0xFF3B82F6),
                                inactiveTrackColor = Color(0xFF374151)
                            )
                        )
                    }
                }

                // Thinking Mode Toggle
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1F2937))
                            .border(1.dp, Color(0xFF374151), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Thinking Traces", color = Color(0xFFF9FAFB), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Show collapsible reasoning process before answer", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                        }
                        Switch(
                            checked = thinkingEnabled,
                            onCheckedChange = { thinkingEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF2563EB),
                                uncheckedThumbColor = Color(0xFF9CA3AF),
                                uncheckedTrackColor = Color(0xFF374151)
                            )
                        )
                    }
                }
            }
        }
    }
}
