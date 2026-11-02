package com.textmorph.app.ui.modelhub

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.model.ModelInfo
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.EngineActivationCoordinator
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.ui.modelhub.components.ActiveSessionLogsCard
import com.textmorph.app.ui.modelhub.components.CloudApiHubSection
import com.textmorph.app.ui.modelhub.components.DeleteModelDialog
import com.textmorph.app.ui.modelhub.components.EditSystemPromptsDialog
import com.textmorph.app.ui.modelhub.components.ModelHubActiveBanner
import com.textmorph.app.ui.modelhub.components.ModelHubEngineSelector
import com.textmorph.app.ui.modelhub.components.ModelImportProgressCard
import com.textmorph.app.ui.modelhub.components.ModelItemCard
import com.textmorph.app.ui.modelhub.components.SystemPromptsInspectorCard
import kotlinx.coroutines.launch

/**
 * Unified Model Hub route screen serving as the single destination for:
 * - On-device LiteRT-LM & llama.cpp model management (isolated in internal storage)
 * - OpenAI-compatible Cloud API endpoints with persistent presets
 * - Runtime telemetry and start/stop controls
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelHubContent(
    modelRepository: ModelStorageRepository,
    settingsRepository: SettingsRepository,
    liteRtEngine: LiteRtLmEngine = LiteRtLmEngineManager.getInstance(LocalContext.current),
    llamaCppEngine: LlamaCppEngine = LlamaCppEngineManager.getInstance(LocalContext.current),
    onNavigateToChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availableModels by modelRepository.availableModels.collectAsState()
    val isScanning by modelRepository.isScanning.collectAsState()
    val importProgress by modelRepository.importProgress.collectAsState()
    val settings by settingsRepository.settingsState.collectAsState()

    val liteRtLoadingProgress by liteRtEngine.loadingProgress.collectAsState()
    val liteRtSessionState by liteRtEngine.sessionState.collectAsState()
    val llamaLoadingProgress by llamaCppEngine.loadingProgress.collectAsState()
    val llamaSessionState by llamaCppEngine.sessionState.collectAsState()

    var selectedEngineTab by remember(settings.selectedEngine) { mutableStateOf(settings.selectedEngine) }
    var pendingMmprojModelId by remember { mutableStateOf<String?>(null) }
    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }
    var showEditPromptsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        modelRepository.refreshModels()
    }

    // SAF Picker Launcher for mmproj Multimodal Projector
    val mmprojSafLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val targetModelId = pendingMmprojModelId
        if (uri != null && targetModelId != null) {
            scope.launch {
                val (name, path) = modelRepository.registerSafMmprojUri(uri)
                val updatedModel = modelRepository.attachMmprojToModel(targetModelId, name, path, uri.toString())
                if (updatedModel != null && (settings.activeModelPath == updatedModel.path || settings.activeModelName == updatedModel.name)) {
                    settingsRepository.setActiveMmproj(name, path, uri.toString())
                }
                Toast.makeText(context, "Attached vision extension: $name", Toast.LENGTH_SHORT).show()
            }
        }
        pendingMmprojModelId = null
    }

    // SAF Picker Launcher for Models (copies cleanly to internal filesDir/models)
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val registered = modelRepository.registerSafModelUri(uri)
                if (registered != null) {
                    val isGguf = registered.modelFileType == ModelFileType.GGUF || registered.name.endsWith(".gguf", ignoreCase = true)
                    val targetEngine = if (isGguf) EngineType.ON_DEVICE_LLAMA_CPP else EngineType.ON_DEVICE_LITERT
                    val coordinator = EngineActivationCoordinator.getInstance(context)
                    val res = coordinator.activateEngine(targetEngine = targetEngine, targetModel = registered)
                    if (res.isSuccess) {
                        Toast.makeText(context, "Copied & activated: ${registered.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        val err = res.exceptionOrNull()?.message ?: "Unknown error"
                        Toast.makeText(context, "Activation failed: $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Unified Engine Selector at the Top
        item {
            ModelHubEngineSelector(
                selectedEngine = selectedEngineTab,
                onSelectEngine = { selectedEngineTab = it }
            )
        }

        // 2. Engine-specific Content
        if (selectedEngineTab == EngineType.CLOUD_OPENAI) {
            // Cloud API Providers & Presets Section
            item {
                CloudApiHubSection(
                    settings = settings,
                    settingsRepository = settingsRepository
                )
            }
        } else {
            // Local On-Device Engines (LiteRT / llama.cpp)
            val isLlamaTab = selectedEngineTab == EngineType.ON_DEVICE_LLAMA_CPP
            val isEngineActive = settings.selectedEngine == selectedEngineTab
            val activeSessionState = if (isLlamaTab) llamaSessionState else liteRtSessionState
            val activeLoading = if (isLlamaTab) llamaLoadingProgress else liteRtLoadingProgress

            val targetFileType = if (isLlamaTab) ModelFileType.GGUF else ModelFileType.LITERT_LM
            val filteredModels = availableModels.filter {
                if (isLlamaTab) {
                    it.modelFileType == ModelFileType.GGUF || it.name.endsWith(".gguf", ignoreCase = true)
                } else {
                    it.modelFileType == ModelFileType.LITERT_LM || it.name.endsWith(".litertlm", ignoreCase = true)
                }
            }

            // Top Active Engine Banner with Start/Stop Controls
            item {
                ModelHubActiveBanner(
                    engineType = selectedEngineTab,
                    activeModelName = if (isEngineActive) activeSessionState.modelName.ifBlank { settings.activeModelName } else "",
                    isLoaded = if (isEngineActive) activeSessionState.isLoaded else false,
                    isLoading = if (isEngineActive) activeLoading.isLoading else false,
                    activeBackend = if (isEngineActive) activeSessionState.backend else "",
                    onToggleEngine = {
                        scope.launch {
                            val coordinator = EngineActivationCoordinator.getInstance(context)
                            if (activeSessionState.isLoaded) {
                                coordinator.offloadAll()
                                Toast.makeText(context, "Engine offloaded from memory", Toast.LENGTH_SHORT).show()
                            } else {
                                val targetModel = filteredModels.find { it.name.equals(settings.activeModelName, ignoreCase = true) }
                                    ?: filteredModels.firstOrNull()
                                if (targetModel != null) {
                                    val res = coordinator.activateEngine(targetEngine = selectedEngineTab, targetModel = targetModel)
                                    if (res.isSuccess) {
                                        Toast.makeText(context, "Starting engine with ${targetModel.name}...", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val err = res.exceptionOrNull()?.message ?: "Unknown error"
                                        Toast.makeText(context, "Start failed: $err", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    safLauncher.launch(arrayOf("*/*", "application/octet-stream"))
                                }
                            }
                        }
                    }
                )
            }

            // Model Import Progress Card (when copying external model to internal filesDir/models)
            item {
                ModelImportProgressCard(importProgress = importProgress)
            }

            // Available Models Section Header with single primary Import action
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isLlamaTab) "Local GGUF Models (${filteredModels.size})" else "Local LiteRT Models (${filteredModels.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = { safLauncher.launch(arrayOf("*/*", "application/octet-stream")) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Model", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (filteredModels.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Inbox,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = if (isLlamaTab) "No GGUF Models Installed" else "No LiteRT Models Installed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tap 'Import Model' above to select a ${if (isLlamaTab) ".gguf" else ".litertlm"} model file to copy into app storage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(filteredModels, key = { it.id }) { model ->
                    val isCurrentActive = isEngineActive && activeSessionState.isLoaded &&
                            (activeSessionState.modelName == model.name || settings.activeModelName == model.name)
                    val isLoadingThis = isEngineActive && activeLoading.isLoading

                    ModelItemCard(
                        model = model,
                        isCurrentActive = isCurrentActive,
                        isLoadingEngine = isLoadingThis,
                        onActivate = {
                            scope.launch {
                                val coordinator = EngineActivationCoordinator.getInstance(context)
                                val res = coordinator.activateEngine(targetEngine = selectedEngineTab, targetModel = model)
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Activating: ${model.name}...", Toast.LENGTH_SHORT).show()
                                } else {
                                    val err = res.exceptionOrNull()?.message ?: "Unknown error"
                                    Toast.makeText(context, "Activation failed: $err", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onOffload = {
                            scope.launch {
                                val coordinator = EngineActivationCoordinator.getInstance(context)
                                coordinator.offloadAll()
                                Toast.makeText(context, "Model offloaded from memory.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onAttachMmproj = {
                            pendingMmprojModelId = model.id
                            mmprojSafLauncher.launch(arrayOf("*/*"))
                        },
                        onDetachMmproj = {
                            scope.launch {
                                modelRepository.detachMmprojFromModel(model.id)
                                if (settings.activeModelName == model.name || settings.activeModelPath == model.path) {
                                    settingsRepository.setActiveMmproj(null, null, null)
                                }
                                Toast.makeText(context, "Detached vision projector", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { modelToDelete = model },
                        onNavigateToChat = onNavigateToChat
                    )
                }
            }

            // Active Session Logs Card
            item {
                ActiveSessionLogsCard(
                    isLoadingEngine = activeLoading.isLoading,
                    loadingProgress = activeLoading,
                    sessionState = activeSessionState
                )
            }
        }

        // 3. System Prompts Inspector Card
        item {
            SystemPromptsInspectorCard(
                settings = settings,
                onOpenEditPrompts = { showEditPromptsDialog = true }
            )
        }
    }

    // Delete Confirmation Dialog
    if (modelToDelete != null) {
        DeleteModelDialog(
            model = modelToDelete!!,
            onConfirm = { permanently ->
                val target = modelToDelete!!
                modelToDelete = null
                scope.launch {
                    modelRepository.deleteModel(target, permanentlyDeleteFile = permanently)
                    Toast.makeText(
                        context,
                        if (permanently) "Permanently deleted ${target.name}" else "Removed ${target.name} from catalog",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { modelToDelete = null }
        )
    }

    // System Prompts Editor Dialog
    if (showEditPromptsDialog) {
        EditSystemPromptsDialog(
            settings = settings,
            onSave = { chatPrompt, agentPrompt ->
                scope.launch {
                    settingsRepository.updateSystemPrompts(chatPrompt, agentPrompt)
                    showEditPromptsDialog = false
                    Toast.makeText(context, "System prompts updated.", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showEditPromptsDialog = false }
        )
    }
}
