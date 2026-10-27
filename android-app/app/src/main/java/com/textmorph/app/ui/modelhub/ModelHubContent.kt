package com.textmorph.app.ui.modelhub

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.model.ModelInfo
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.ui.modelhub.components.ActiveSessionLogsCard
import com.textmorph.app.ui.modelhub.components.CloudApiConfigAccordionCard
import com.textmorph.app.ui.modelhub.components.DeleteModelDialog
import com.textmorph.app.ui.modelhub.components.EditSystemPromptsDialog
import com.textmorph.app.ui.modelhub.components.ModelItemCard
import com.textmorph.app.ui.modelhub.components.ModelLoaderCard
import com.textmorph.app.ui.modelhub.components.SystemPromptsInspectorCard
import kotlinx.coroutines.launch

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
    val settings by settingsRepository.settingsState.collectAsState()

    val liteRtLoadingProgress by liteRtEngine.loadingProgress.collectAsState()
    val liteRtSessionState by liteRtEngine.sessionState.collectAsState()
    val llamaLoadingProgress by llamaCppEngine.loadingProgress.collectAsState()
    val llamaSessionState by llamaCppEngine.sessionState.collectAsState()

    val isLlamaActive = settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP
    val activeLoadingProgress = if (isLlamaActive || llamaLoadingProgress.isLoading) llamaLoadingProgress else liteRtLoadingProgress
    val activeSessionState = if (isLlamaActive) llamaSessionState else liteRtSessionState

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

    // SAF Picker Launcher for Models
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val registered = modelRepository.registerSafModelUri(uri)
                if (registered != null) {
                    val isGguf = registered.modelFileType == ModelFileType.GGUF || registered.name.endsWith(".gguf", ignoreCase = true)
                    val targetEngine = if (isGguf) EngineType.ON_DEVICE_LLAMA_CPP else EngineType.ON_DEVICE_LITERT
                    settingsRepository.setActiveModel(registered.name, registered.path, registered.uriString, targetEngine)
                    if (isGguf) {
                        settingsRepository.setActiveMmproj(registered.mmprojName, registered.mmprojPath, registered.mmprojUriString)
                        llamaCppEngine.initialize(
                            modelPath = registered.path,
                            modelName = registered.name,
                            modelUri = uri,
                            mmprojPath = registered.mmprojPath,
                            mmprojUri = registered.mmprojUriString?.let { Uri.parse(it) },
                            preferredAccelerator = settings.preferredAccelerator,
                            probeTimeoutMs = settings.probeTimeoutMs
                        )
                    } else {
                        liteRtEngine.initialize(registered.path, registered.name, uri, settings.preferredAccelerator, settings.probeTimeoutMs)
                    }
                    Toast.makeText(context, "Loading: ${registered.name}", Toast.LENGTH_SHORT).show()
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
        // 1. Available Models List at the Top
        item {
            Text(
                text = "Available Models (${availableModels.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (availableModels.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "No Local Models Found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Browse a .litertlm or .gguf model file, or scan your device's Download storage to get started.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { safLauncher.launch(arrayOf("*/*", "application/octet-stream")) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Browse Model", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { scope.launch { modelRepository.refreshModels() } },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan Device Storage", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else {
            items(availableModels, key = { it.id }) { model ->
                val isGguf = model.modelFileType == ModelFileType.GGUF || model.name.endsWith(".gguf", ignoreCase = true)
                val isCurrentActive = if (isGguf) {
                    llamaSessionState.isLoaded && (llamaSessionState.modelName == model.name || settings.activeModelName == model.name)
                } else {
                    liteRtSessionState.isLoaded && (liteRtSessionState.modelName == model.name || settings.activeModelName == model.name)
                }
                val isLoadingThis = if (isGguf) llamaLoadingProgress.isLoading else liteRtLoadingProgress.isLoading

                ModelItemCard(
                    model = model,
                    isCurrentActive = isCurrentActive,
                    isLoadingEngine = isLoadingThis,
                    onActivate = {
                        scope.launch {
                            val uri = model.uriString?.let { Uri.parse(it) }
                            val mmprojUri = model.mmprojUriString?.let { Uri.parse(it) }
                            val targetEngine = if (isGguf) EngineType.ON_DEVICE_LLAMA_CPP else EngineType.ON_DEVICE_LITERT
                            settingsRepository.setActiveModel(model.name, model.path, model.uriString, targetEngine)
                            if (isGguf) {
                                settingsRepository.setActiveMmproj(model.mmprojName, model.mmprojPath, model.mmprojUriString)
                                llamaCppEngine.initialize(
                                    modelPath = model.path,
                                    modelName = model.name,
                                    modelUri = uri,
                                    mmprojPath = model.mmprojPath,
                                    mmprojUri = mmprojUri,
                                    preferredAccelerator = settings.preferredAccelerator,
                                    probeTimeoutMs = settings.probeTimeoutMs
                                )
                            } else {
                                liteRtEngine.initialize(model.path, model.name, uri, settings.preferredAccelerator, settings.probeTimeoutMs)
                            }
                            Toast.makeText(context, "Loading: ${model.name}...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOffload = {
                        scope.launch {
                            liteRtEngine.unload()
                            llamaCppEngine.unload()
                            settingsRepository.setActiveModel("No Model Loaded", null, null, settings.selectedEngine)
                            Toast.makeText(context, "Model session offloaded from memory.", Toast.LENGTH_SHORT).show()
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
                    onDelete = {
                        modelToDelete = model
                    },
                    onImportToPrivateStorage = {
                        scope.launch {
                            val imported = modelRepository.importToPrivateStorage(model)
                            if (imported) {
                                Toast.makeText(context, "Imported ${model.name} to private storage", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onNavigateToChat = onNavigateToChat
                )
            }
        }

        // 2. OpenAI API Compatible Config Accordion Card
        item {
            CloudApiConfigAccordionCard(
                settings = settings,
                settingsRepository = settingsRepository,
                liteRtEngine = liteRtEngine,
                llamaCppEngine = llamaCppEngine
            )
        }

        // 3. System Prompts Inspector Card
        item {
            SystemPromptsInspectorCard(
                settings = settings,
                onOpenEditPrompts = { showEditPromptsDialog = true }
            )
        }

        // 4. Model Loader Card (reduced size, anchored near end)
        item {
            ModelLoaderCard(
                settings = settings,
                isScanning = isScanning,
                onRefresh = { scope.launch { modelRepository.refreshModels() } },
                onBrowseModel = { safLauncher.launch(arrayOf("*/*", "application/octet-stream")) },
                onScanStorage = { scope.launch { modelRepository.refreshModels() } },
                onSelectAccelerator = { mode ->
                    scope.launch { settingsRepository.setPreferredAccelerator(mode) }
                }
            )
        }

        // 5. Active Session Logs Card (Always expanded and open when loaded or loading)
        item {
            ActiveSessionLogsCard(
                isLoadingEngine = activeLoadingProgress.isLoading,
                loadingProgress = activeLoadingProgress,
                sessionState = activeSessionState
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
                        if (permanently) "Permanently deleted ${target.name}" else "Removed ${target.name} from app catalog",
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
