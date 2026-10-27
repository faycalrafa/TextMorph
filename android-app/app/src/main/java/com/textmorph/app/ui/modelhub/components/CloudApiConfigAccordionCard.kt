package com.textmorph.app.ui.modelhub.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TravelExplore
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.settings.sections.CloudModelSearchDialog
import kotlinx.coroutines.launch

/**
 * Collapsible accordion card in Model Hub for configuring and activating OpenAI-compatible
 * cloud endpoints (OpenRouter, OpenAI, LM Studio, Ollama).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudApiConfigAccordionCard(
    settings: AppSettings,
    settingsRepository: SettingsRepository,
    liteRtEngine: LiteRtLmEngine,
    llamaCppEngine: LlamaCppEngine,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(false) }

    var cloudUrl by remember(settings.cloudBaseUrl) { mutableStateOf(settings.cloudBaseUrl) }
    var cloudKey by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }
    var cloudModel by remember(settings.cloudModelId) { mutableStateOf(settings.cloudModelId) }

    val cloudEngine = remember { OpenAiCloudEngine() }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModelsList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelSearchDialog by remember { mutableStateOf(false) }

    val isCloudActive = settings.selectedEngine == EngineType.CLOUD_OPENAI

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Accordion Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "OpenAI Compatible API",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
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
                        Text(
                            text = "Connect to OpenAI, OpenRouter, LM Studio, or local servers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                ) {
                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = { cloudUrl = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = cloudKey,
                        onValueChange = { cloudKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = cloudModel,
                        onValueChange = { cloudModel = it },
                        label = { Text("Model ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        trailingIcon = {
                            if (isFetchingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
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
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action Buttons in FlowRow: Save Config & Active Toggle
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    settingsRepository.setCloudConfig(cloudUrl, cloudKey, cloudModel)
                                    Toast.makeText(context, "Cloud configuration saved.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save Config")
                        }

                        if (!isCloudActive) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        liteRtEngine.unload()
                                        llamaCppEngine.unload()
                                        settingsRepository.setCloudConfig(cloudUrl, cloudKey, cloudModel)
                                        settingsRepository.setEngine(EngineType.CLOUD_OPENAI)
                                        settingsRepository.setActiveModel("No Model Loaded", null, null, EngineType.CLOUD_OPENAI)
                                        Toast.makeText(context, "Cloud engine activated. Local models offloaded.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Active")
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
                        }
                    }
                }
            }
        }
    }

    if (showModelSearchDialog) {
        CloudModelSearchDialog(
            models = fetchedModelsList,
            currentModel = cloudModel,
            onSelectModel = { selected ->
                cloudModel = selected
                showModelSearchDialog = false
            },
            onDismiss = { showModelSearchDialog = false }
        )
    }
}
