package com.textmorph.app.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.dashboard.components.ReadinessBanner
import com.textmorph.app.ui.settings.sections.AppearanceDiagnosticsSection
import com.textmorph.app.ui.settings.sections.CloudModelSearchDialog
import com.textmorph.app.ui.settings.sections.EngineSettingsSection
import com.textmorph.app.ui.settings.sections.LocalServerSettingsSection
import com.textmorph.app.ui.settings.sections.ToolbarSettingsSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    settingsRepository: SettingsRepository,
    updateRepository: UpdateRepository,
    onOpenDevLogs: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenAccessibilityDisclosure: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settingsState.collectAsState()

    var cloudUrl by remember(settings.cloudBaseUrl) { mutableStateOf(settings.cloudBaseUrl) }
    var cloudKey by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }
    var cloudModel by remember(settings.cloudModelId) { mutableStateOf(settings.cloudModelId) }

    val cloudEngine = remember { OpenAiCloudEngine() }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModelsList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelSearchDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // System Readiness for AI Checklist
        ReadinessBanner(
            onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure
        )

        // Engine Selection & Hardware Acceleration
        EngineSettingsSection(
            settings = settings,
            onSelectEngine = { engine -> scope.launch { settingsRepository.setEngine(engine) } },
            onSetPreferredAccelerator = { acc -> scope.launch { settingsRepository.setPreferredAccelerator(acc) } },
            onSetProbeTimeout = { ms -> scope.launch { settingsRepository.setProbeTimeout(ms) } }
        )

        // Localhost HTTP Server
        LocalServerSettingsSection(
            settings = settings,
            onSaveServerConfig = { enabled, port, token ->
                scope.launch { settingsRepository.setLocalServer(enabled, port, token) }
            }
        )

        // Cloud OpenAI Config Card (Shown when selected)
        AnimatedVisibility(visible = settings.selectedEngine == EngineType.CLOUD_OPENAI) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = "Cloud API Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

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

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                settingsRepository.setCloudConfig(cloudUrl, cloudKey, cloudModel)
                                Toast.makeText(context, "Cloud configuration saved.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save Cloud Config")
                    }
                }
            }
        }

        // Overlay & Assistance Preferences
        ToolbarSettingsSection(
            settings = settings,
            onSetFloatingToolbarEnabled = { enabled -> scope.launch { settingsRepository.setFloatingToolbarEnabled(enabled) } },
            onSetAlwaysShowFloatingToolbar = { enabled -> scope.launch { settingsRepository.setAlwaysShowFloatingToolbar(enabled) } },
            onSetAlwaysOpenBarWithKeyboard = { enabled -> scope.launch { settingsRepository.setAlwaysOpenBarWithKeyboard(enabled) } },
            onSetCompactChipsMode = { enabled -> scope.launch { settingsRepository.setCompactChipsMode(enabled) } },
            onSetQuickFixEnabled = { enabled -> scope.launch { settingsRepository.setQuickFixEnabled(enabled) } },
            onSetAutoSendVoiceInput = { enabled -> scope.launch { settingsRepository.setAutoSendVoiceInput(enabled) } }
        )

        // Appearance & Diagnostics
        AppearanceDiagnosticsSection(
            theme = settings.theme,
            onSetTheme = { mode -> scope.launch { settingsRepository.setTheme(mode) } },
            onOpenDevLogs = onOpenDevLogs,
            onCheckUpdate = onCheckUpdate,
            versionName = updateRepository.getCurrentVersionName()
        )
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
