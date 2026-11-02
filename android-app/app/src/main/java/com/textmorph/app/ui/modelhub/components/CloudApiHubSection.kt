package com.textmorph.app.ui.modelhub.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.CloudApiPreset
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.EngineActivationCoordinator
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.chat.components.ApiPresetEditDialog
import com.textmorph.app.ui.settings.sections.CloudModelSearchDialog
import kotlinx.coroutines.launch

/**
 * Centered Cloud API Configuration section in Model Hub.
 * Features preset selection chips with reliable per-preset token persistence,
 * model exploration from remote endpoints, and instant activation controls.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CloudApiHubSection(
    settings: AppSettings,
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isCloudActive = settings.selectedEngine == EngineType.CLOUD_OPENAI

    var cloudUrl by remember(settings.cloudBaseUrl) { mutableStateOf(settings.cloudBaseUrl) }
    var cloudKey by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }
    var cloudModel by remember(settings.cloudModelId) { mutableStateOf(settings.cloudModelId) }
    var isKeyVisible by remember { mutableStateOf(false) }

    val cloudEngine = remember { OpenAiCloudEngine() }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModelsList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelSearchDialog by remember { mutableStateOf(false) }

    var editingPreset by remember { mutableStateOf<CloudApiPreset?>(null) }
    var showPresetDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        border = BorderStroke(1.dp, if (isCloudActive) Color(0xFF2563EB) else Color(0xFF1F2937))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Title & Active Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (isCloudActive) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Cloud API Providers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF9FAFB)
                        )
                        Text(
                            text = "Connect OpenAI, OpenRouter, Groq, Ollama or custom endpoints",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }

                if (isCloudActive) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF16A34A).copy(alpha = 0.25f)
                    ) {
                        Text(
                            text = "ACTIVE",
                            color = Color(0xFF22C55E),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Presets Header & "+ New Preset" Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "API PRESETS",
                    color = Color(0xFF60A5FA),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1F2937),
                    border = BorderStroke(1.dp, Color(0xFF374151)),
                    modifier = Modifier.clickable {
                        editingPreset = null
                        showPresetDialog = true
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Preset",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "New Preset",
                            fontSize = 11.sp,
                            color = Color(0xFF60A5FA),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Preset Selector Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                settings.apiPresets.forEach { preset ->
                    val isSelected = settings.activePresetId == preset.id ||
                            (cloudUrl == preset.baseUrl && cloudModel == preset.defaultModelId)

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF2563EB) else Color(0xFF1F2937),
                        border = BorderStroke(1.dp, if (isSelected) Color(0xFF60A5FA) else Color(0xFF374151)),
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                scope.launch {
                                    settingsRepository.selectApiPreset(preset.id)
                                    cloudUrl = preset.baseUrl
                                    cloudKey = preset.apiKey
                                    cloudModel = preset.defaultModelId
                                }
                            },
                            onLongClick = {
                                editingPreset = preset
                                showPresetDialog = true
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = preset.name,
                                fontSize = 12.sp,
                                color = if (isSelected) Color.White else Color(0xFFE5E7EB),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )

                            if (isSelected) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Preset",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clickable {
                                            editingPreset = preset
                                            showPresetDialog = true
                                        }
                                )
                            }
                        }
                    }
                }
            }

            // Base URL Field
            OutlinedTextField(
                value = cloudUrl,
                onValueChange = {
                    cloudUrl = it
                    scope.launch { settingsRepository.setCloudConfig(it, cloudKey, cloudModel) }
                },
                label = { Text("Base URL", color = Color(0xFF9CA3AF)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            // API Key / Token Field with Show/Hide toggle
            OutlinedTextField(
                value = cloudKey,
                onValueChange = {
                    cloudKey = it
                    scope.launch { settingsRepository.setCloudConfig(cloudUrl, it, cloudModel) }
                },
                label = { Text("API Key / Token", color = Color(0xFF9CA3AF)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                        Icon(
                            imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isKeyVisible) "Hide Token" else "Show Token",
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )

            // Model ID Field with Remote Explorer
            OutlinedTextField(
                value = cloudModel,
                onValueChange = {
                    cloudModel = it
                    scope.launch { settingsRepository.setCloudConfig(cloudUrl, cloudKey, it) }
                },
                label = { Text("Model ID", color = Color(0xFF9CA3AF)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                trailingIcon = {
                    if (isFetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF3B82F6)
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (cloudUrl.isBlank()) {
                                    Toast.makeText(context, "Please enter a Base URL first", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                scope.launch {
                                    isFetchingModels = true
                                    val res = cloudEngine.fetchAvailableModels(cloudUrl, cloudKey)
                                    isFetchingModels = false
                                    res.onSuccess { models ->
                                        if (models.isEmpty()) {
                                            Toast.makeText(context, "No models returned by this API.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            fetchedModelsList = models
                                            showModelSearchDialog = true
                                        }
                                    }.onFailure { err ->
                                        Toast.makeText(context, "Failed to fetch models: ${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.TravelExplore,
                                contentDescription = "Browse Models from API",
                                tint = Color(0xFF3B82F6)
                            )
                        }
                    }
                }
            )

            // Action Buttons: Save & Activate Controls
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isCloudActive) {
                    Button(
                        onClick = {
                            scope.launch {
                                settingsRepository.setCloudConfig(cloudUrl, cloudKey, cloudModel)
                                val coordinator = EngineActivationCoordinator.getInstance(context)
                                val res = coordinator.activateEngine(
                                    targetEngine = EngineType.CLOUD_OPENAI,
                                    targetCloudModelId = cloudModel
                                )
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Cloud API activated ($cloudModel)", Toast.LENGTH_SHORT).show()
                                } else {
                                    val err = res.exceptionOrNull()?.message ?: "Unknown error"
                                    Toast.makeText(context, "Activation failed: $err", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Activate Cloud Engine")
                    }
                } else {
                    Button(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = Color(0xFF16A34A),
                            disabledContentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Active ✓", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val coordinator = EngineActivationCoordinator.getInstance(context)
                                coordinator.offloadAll()
                                Toast.makeText(context, "Cloud engine disconnected.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Disconnect")
                    }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settingsRepository.setCloudConfig(cloudUrl, cloudKey, cloudModel)
                            Toast.makeText(context, "Cloud configuration & token saved.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save Config", color = Color(0xFFE5E7EB))
                }
            }
        }
    }

    if (showPresetDialog) {
        ApiPresetEditDialog(
            preset = editingPreset,
            onSave = { saved ->
                scope.launch {
                    settingsRepository.saveApiPreset(saved)
                    cloudUrl = saved.baseUrl
                    cloudKey = saved.apiKey
                    cloudModel = saved.defaultModelId
                    showPresetDialog = false
                    Toast.makeText(context, "Preset '${saved.name}' saved.", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { id ->
                scope.launch {
                    settingsRepository.deleteApiPreset(id)
                    showPresetDialog = false
                    Toast.makeText(context, "Preset deleted.", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showPresetDialog = false }
        )
    }

    if (showModelSearchDialog) {
        CloudModelSearchDialog(
            models = fetchedModelsList,
            currentModel = cloudModel,
            onSelectModel = { selected ->
                cloudModel = selected
                scope.launch { settingsRepository.setCloudConfig(cloudUrl, cloudKey, selected) }
                showModelSearchDialog = false
            },
            onDismiss = { showModelSearchDialog = false }
        )
    }
}
