package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.model.ModelInfo

/**
 * Bottom sheet for rapidly switching the active model from locally detected models or Cloud API
 * and fine-tuning runtime inference settings.
 * Gated behind an explicit 'Activate' button to offload the previous engine and prevent premature switches.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelQuickSwitchBottomSheet(
    settings: AppSettings,
    availableModels: List<ModelInfo>,
    onDismiss: () -> Unit,
    onActivateModel: (EngineType, ModelInfo?, String?) -> Unit,
    onSaveConfig: (maxTokens: Int, temperature: Float, topP: Float, thinking: Boolean, accelerator: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var maxTokens by remember(settings.maxTokens) { mutableIntStateOf(settings.maxTokens) }
    var topP by remember(settings.topP) { mutableFloatStateOf(settings.topP) }
    var thinkingEnabled by remember(settings.enableThinking) { mutableStateOf(settings.enableThinking) }
    val preferredAccelerator by remember(settings.preferredAccelerator) { mutableStateOf(settings.preferredAccelerator) }

    val downloadedModels = remember(availableModels) { availableModels.filter { it.isDownloaded } }

    var selectedEngineTab by remember { mutableStateOf(settings.selectedEngine) }
    var stagedModel by remember(downloadedModels, settings.activeModelName) {
        mutableStateOf(downloadedModels.firstOrNull { it.name.equals(settings.activeModelName, ignoreCase = true) })
    }
    var stagedCloudModelId by remember(settings.cloudModelId) { mutableStateOf(settings.cloudModelId) }

    val apiPresets = listOf(
        "gemini-2.0-flash", "gemini-1.5-flash", "gpt-4o", "gpt-4o-mini", "deepseek-chat", "claude-3-5-sonnet"
    )

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

            Spacer(modifier = Modifier.height(14.dp))

            // Engine Selection Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1F2937))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EngineTabItem(
                    label = "LiteRT",
                    sub = ".litertlm",
                    isSelected = selectedEngineTab == EngineType.ON_DEVICE_LITERT,
                    onClick = {
                        selectedEngineTab = EngineType.ON_DEVICE_LITERT
                        if (stagedModel?.modelFileType != ModelFileType.LITERT_LM) {
                            stagedModel = downloadedModels.firstOrNull { it.modelFileType == ModelFileType.LITERT_LM }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                EngineTabItem(
                    label = "llama.cpp",
                    sub = ".gguf",
                    isSelected = selectedEngineTab == EngineType.ON_DEVICE_LLAMA_CPP,
                    onClick = {
                        selectedEngineTab = EngineType.ON_DEVICE_LLAMA_CPP
                        if (stagedModel?.modelFileType != ModelFileType.GGUF) {
                            stagedModel = downloadedModels.firstOrNull { it.modelFileType == ModelFileType.GGUF }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                EngineTabItem(
                    label = "Cloud API",
                    sub = "OpenAI",
                    isSelected = selectedEngineTab == EngineType.CLOUD_OPENAI,
                    onClick = { selectedEngineTab = EngineType.CLOUD_OPENAI },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Section 1: Engine Models / Cloud API Models
                item {
                    Text(
                        text = when (selectedEngineTab) {
                            EngineType.ON_DEVICE_LITERT -> "AVAILABLE LITERT-LM MODELS"
                            EngineType.ON_DEVICE_LLAMA_CPP -> "AVAILABLE LLAMA.CPP GGUF MODELS"
                            EngineType.CLOUD_OPENAI -> "CLOUD API MODEL SELECTION"
                        },
                        color = Color(0xFF60A5FA),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                }

                if (selectedEngineTab == EngineType.CLOUD_OPENAI) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Quick Presets:",
                                color = Color(0xFF9CA3AF),
                                fontSize = 12.sp
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                apiPresets.forEach { preset ->
                                    val isSelected = stagedCloudModelId.equals(preset, ignoreCase = true)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) Color(0xFF2563EB) else Color(0xFF1F2937),
                                        border = BorderStroke(1.dp, if (isSelected) Color(0xFF60A5FA) else Color(0xFF374151)),
                                        modifier = Modifier.clickable { stagedCloudModelId = preset }
                                    ) {
                                        Text(
                                            text = preset,
                                            fontSize = 12.sp,
                                            color = if (isSelected) Color.White else Color(0xFFE5E7EB),
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Model ID:",
                                color = Color(0xFF9CA3AF),
                                fontSize = 12.sp
                            )
                            OutlinedTextField(
                                value = stagedCloudModelId,
                                onValueChange = { stagedCloudModelId = it },
                                textStyle = TextStyle(color = Color.White, fontSize = 13.5.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF374151),
                                    focusedContainerColor = Color(0xFF1F2937),
                                    unfocusedContainerColor = Color(0xFF1F2937)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                } else {
                    val targetFileType = if (selectedEngineTab == EngineType.ON_DEVICE_LITERT) {
                        ModelFileType.LITERT_LM
                    } else {
                        ModelFileType.GGUF
                    }
                    val filteredModels = downloadedModels.filter { it.modelFileType == targetFileType }

                    if (filteredModels.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1F2937), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFF374151), RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = if (targetFileType == ModelFileType.LITERT_LM) {
                                        "No .litertlm models found in storage. Download one in Model Hub."
                                    } else {
                                        "No .gguf models found in storage. Download one in Model Hub."
                                    },
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        items(filteredModels) { model ->
                            val isStaged = stagedModel?.id == model.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isStaged) Color(0xFF1F2937) else Color(0x401F2937))
                                    .border(
                                        width = if (isStaged) 1.5.dp else 1.dp,
                                        color = if (isStaged) Color(0xFF3B82F6) else Color(0xFF374151),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { stagedModel = model }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = if (isStaged) Color(0xFF3B82F6) else Color(0xFF9CA3AF),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.name,
                                        color = if (isStaged) Color(0xFFF9FAFB) else Color(0xFFE5E7EB),
                                        fontSize = 14.sp,
                                        fontWeight = if (isStaged) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        text = "${model.modelFileType.name} • ${String.format("%.1f GB", model.sizeBytes / 1_073_741_824.0)}",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 11.sp
                                    )
                                }
                                if (isStaged) {
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
                }

                // Section 2: Generation Parameters (Extracted component)
                modelGenerationConfigSection(
                    temperature = temperature,
                    onTemperatureChanged = { temperature = it },
                    maxTokens = maxTokens,
                    onMaxTokensChanged = { maxTokens = it },
                    topP = topP,
                    onTopPChanged = { topP = it },
                    thinkingEnabled = thinkingEnabled,
                    onThinkingEnabledChanged = { thinkingEnabled = it }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Prominent Activate Button
            val canActivate = when (selectedEngineTab) {
                EngineType.CLOUD_OPENAI -> stagedCloudModelId.isNotBlank()
                else -> stagedModel != null
            }
            val activeLabel = when (selectedEngineTab) {
                EngineType.CLOUD_OPENAI -> "Activate Cloud: $stagedCloudModelId"
                else -> "Activate ${stagedModel?.name ?: "Model"}"
            }

            Button(
                onClick = {
                    onActivateModel(selectedEngineTab, stagedModel, stagedCloudModelId)
                    onSaveConfig(maxTokens, temperature, topP, thinkingEnabled, preferredAccelerator)
                    onDismiss()
                },
                enabled = canActivate,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                    disabledContainerColor = Color(0xFF374151)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = activeLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun EngineTabItem(
    label: String,
    sub: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (isSelected) Color(0xFF2563EB) else Color.Transparent,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color(0xFFE5E7EB)
            )
            Text(
                text = sub,
                fontSize = 10.sp,
                color = if (isSelected) Color(0xFFBFDBFE) else Color(0xFF9CA3AF)
            )
        }
    }
}
