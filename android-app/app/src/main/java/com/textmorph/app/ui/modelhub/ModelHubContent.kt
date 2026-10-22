package com.textmorph.app.ui.modelhub

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.work.WorkInfo
import com.textmorph.app.worker.DownloadWorker
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
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
import com.textmorph.app.llm.ModelLoadingProgress
import com.textmorph.app.llm.ModelSessionState
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
    val consoleTitle = if (isLlamaActive || llamaLoadingProgress.isLoading) "LLAMA.CPP GGUF INFERENCE CONSOLE" else "LITERT-LM INFERENCE CONSOLE"

    var pendingMmprojModelId by remember { mutableStateOf<String?>(null) }

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
                    settingsRepository.setActiveModel(registered.name, registered.path, registered.uriString)
                    if (isGguf) {
                        settingsRepository.setSelectedEngine(EngineType.ON_DEVICE_LLAMA_CPP)
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
                        settingsRepository.setSelectedEngine(EngineType.ON_DEVICE_LITERT)
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
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Zero-Copy Model Loader",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { scope.launch { modelRepository.refreshModels() } },
                            enabled = !isScanning
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Scan")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Load multi-gigabyte .litertlm and .gguf models directly from your Downloads folder with zero file copying and instant mmap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Hardware Accelerator:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("AUTO" to "Auto (GPU/CPU)", "GPU" to "Force GPU", "CPU" to "Force CPU").forEach { (mode, label) ->
                            val selected = settings.preferredAccelerator.equals(mode, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    scope.launch {
                                        settingsRepository.setPreferredAccelerator(mode)
                                    }
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                safLauncher.launch(arrayOf("*/*"))
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Browse Model (.gguf / .litertlm)")
                        }

                        OutlinedButton(
                            onClick = { scope.launch { modelRepository.refreshModels() } },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan Device Storage")
                        }
                    }
                }
            }
        }

        // Live Model Loading Progress & Dark Monospace Console Card
        item {
            ModelLoadingConsoleCard(
                loadingProgress = activeLoadingProgress,
                sessionState = activeSessionState,
                consoleTitle = consoleTitle
            )
        }

        item {
            Text(
                text = "Available Models (${availableModels.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        items(availableModels, key = { it.id }) { model ->
            val isGguf = model.modelFileType == ModelFileType.GGUF || model.name.endsWith(".gguf", ignoreCase = true)
            val isCurrentActive = if (isGguf) {
                llamaSessionState.isLoaded && (llamaSessionState.modelName == model.name || settings.activeModelName == model.name)
            } else {
                liteRtSessionState.isLoaded && (liteRtSessionState.modelName == model.name || settings.activeModelName == model.name)
            }
            val isLoadingThis = if (isGguf) llamaLoadingProgress.isLoading else liteRtLoadingProgress.isLoading

            ModelCardItem(
                model = model,
                isCurrentActive = isCurrentActive,
                isLoadingEngine = isLoadingThis,
                onActivate = {
                    scope.launch {
                        val uri = model.uriString?.let { Uri.parse(it) }
                        val mmprojUri = model.mmprojUriString?.let { Uri.parse(it) }
                        settingsRepository.setActiveModel(model.name, model.path, model.uriString)
                        if (isGguf) {
                            settingsRepository.setSelectedEngine(EngineType.ON_DEVICE_LLAMA_CPP)
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
                            settingsRepository.setSelectedEngine(EngineType.ON_DEVICE_LITERT)
                            liteRtEngine.initialize(model.path, model.name, uri, settings.preferredAccelerator, settings.probeTimeoutMs)
                        }
                        Toast.makeText(context, "Loading: ${model.name}...", Toast.LENGTH_SHORT).show()
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
                onDownload = {
                    modelRepository.startDownload(model)
                    Toast.makeText(context, "Download started for ${model.name}", Toast.LENGTH_SHORT).show()
                },
                onCancelDownload = {
                    modelRepository.cancelDownload(model.id)
                    Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    scope.launch {
                        val deleted = modelRepository.deleteModel(model)
                        if (deleted) {
                            Toast.makeText(context, "Deleted ${model.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onImportToPrivateStorage = {
                    scope.launch {
                        val imported = modelRepository.importToPrivateStorage(model)
                        if (imported) {
                            Toast.makeText(context, "Imported ${model.name} to private storage", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onNavigateToChat = onNavigateToChat,
                modelRepository = modelRepository
            )
        }
    }
}

@Composable
private fun ModelCardItem(
    model: ModelInfo,
    isCurrentActive: Boolean,
    isLoadingEngine: Boolean,
    onActivate: () -> Unit,
    onAttachMmproj: () -> Unit = {},
    onDetachMmproj: () -> Unit = {},
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onImportToPrivateStorage: () -> Unit,
    onNavigateToChat: () -> Unit,
    modelRepository: ModelStorageRepository,
    modifier: Modifier = Modifier
) {
    val workInfo by modelRepository.getDownloadWorkInfo(model.id).collectAsState(initial = null)
    val isDownloading = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    val isGguf = model.modelFileType == ModelFileType.GGUF || model.name.endsWith(".gguf", ignoreCase = true)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrentActive) 4.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SuggestionChip(
                            onClick = {},
                            label = { Text(if (isGguf) "GGUF" else "LiteRT-LM", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        )
                        if (isCurrentActive) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Active", fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (model.isDownloaded && model.path != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Model",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Multimodal Vision Projector (Optional) for GGUF models
            if (isGguf && model.isDownloaded) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Multimodal Vision Projector",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (!model.mmprojName.isNullOrBlank()) "Projector: ${model.mmprojName}" else "No mmproj attached (Text Only)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (!model.mmprojName.isNullOrBlank()) {
                            IconButton(
                                onClick = onDetachMmproj,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Detach Vision Projector",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = onAttachMmproj,
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add mmproj", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Download Progress Bar & Speed / ETA
            if (isDownloading) {
                val progress = workInfo?.progress?.getInt(DownloadWorker.KEY_PROGRESS_PERCENT, 0) ?: 0
                val receivedBytes = workInfo?.progress?.getLong(DownloadWorker.KEY_RECEIVED_BYTES, 0L) ?: 0L
                val totalBytes = workInfo?.progress?.getLong(DownloadWorker.KEY_TOTAL_BYTES, model.sizeBytes) ?: model.sizeBytes
                val rate = workInfo?.progress?.getLong(DownloadWorker.KEY_DOWNLOAD_RATE, 0L) ?: 0L
                val remainingMs = workInfo?.progress?.getLong(DownloadWorker.KEY_REMAINING_MS, 0L) ?: 0L

                val speedMb = rate / (1024.0 * 1024.0)
                val etaSec = remainingMs / 1000

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$progress% • ${receivedBytes / (1024 * 1024)} / ${totalBytes / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "%.1f MB/s • %ds left".format(speedMb, etaSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            // Bottom action row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (model.sizeBytes > 0) "${model.sizeBytes / (1024 * 1024)} MB" else "Catalog Entry",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isDownloading) {
                        OutlinedButton(
                            onClick = onCancelDownload,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    } else if (!model.isDownloaded) {
                        if (model.downloadUrl != null) {
                            Button(
                                onClick = onDownload,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download")
                            }
                        }
                    } else {
                        // Model is downloaded
                        if (model.isExternalStorage) {
                            OutlinedButton(
                                onClick = onImportToPrivateStorage,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import", fontSize = 12.sp)
                            }
                        }

                        if (!isCurrentActive) {
                            Button(
                                onClick = onActivate,
                                enabled = !isLoadingEngine,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (isLoadingEngine) "Loading..." else "Activate Model")
                            }
                        } else {
                            Button(
                                onClick = onNavigateToChat,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test in Playground")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelLoadingConsoleCard(
    loadingProgress: ModelLoadingProgress,
    sessionState: ModelSessionState,
    consoleTitle: String = "LITERT-LM INFERENCE CONSOLE",
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (loadingProgress.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (sessionState.isLoaded) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF48BB78),
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (loadingProgress.error != null) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (loadingProgress.isLoading) "Loading: ${loadingProgress.statusText}"
                        else if (sessionState.isLoaded) "Session Active: ${sessionState.modelName}"
                        else if (loadingProgress.error != null) "Loading Failed"
                        else "Inference Console",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                if (sessionState.isLoaded) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF276749)
                    ) {
                        Text(
                            text = sessionState.backend,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (loadingProgress.isLoading) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { loadingProgress.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dark Monospace Console Log Box
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E1E1E),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = consoleTitle,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color(0xFFA0AEC0)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (loadingProgress.isLoading) Color(0xFFECC94B)
                                        else if (sessionState.isLoaded) Color(0xFF48BB78)
                                        else Color(0xFF718096),
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (loadingProgress.isLoading) "INITIALIZING"
                                else if (sessionState.isLoaded) "ONLINE"
                                else "STANDBY",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFFA0AEC0)
                            )
                        }
                    }

                    HorizontalDivider(
                        color = Color(0xFF2D3748),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val lines = loadingProgress.logLines.takeLast(12)
                            if (lines.isEmpty()) {
                                Text(
                                    text = "> System ready. Select or scan a .litertlm or .gguf model to begin live zero-copy execution.",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF718096)
                                )
                            } else {
                                lines.forEach { line ->
                                    val textColor = when {
                                        line.contains("[SUCCESS]") -> Color(0xFF68D391)
                                        line.contains("[ERROR]") || line.contains("[FATAL]") || line.contains("[FAILED]") -> Color(0xFFFC8181)
                                        line.contains("[WARN]") -> Color(0xFFF6E05E)
                                        line.contains("[INFO]") -> Color(0xFF63B3ED)
                                        else -> Color(0xFFE2E8F0)
                                    }
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = textColor,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
