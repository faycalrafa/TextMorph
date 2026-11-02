package com.textmorph.app.ui.navigation.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.AppReleaseInfo
import com.textmorph.app.data.repository.DownloadState
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.ui.common.ProminentDisclosureDialog
import com.textmorph.app.ui.common.UpdateDialog
import com.textmorph.app.ui.prompts.PromptEditorDialog
import com.textmorph.app.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles root modal dialogs (Prompt Editor, Accessibility Disclosure, In-App Update)
 * to maintain clean separation of concerns and keep MainAppScaffold under line limits.
 */
@Composable
fun MainAppDialogs(
    context: Context,
    scope: CoroutineScope,
    promptRepository: PromptRepository,
    settingsRepository: SettingsRepository,
    updateRepository: UpdateRepository,
    showPromptEditor: Boolean,
    editingPrompt: PromptEntity?,
    onDismissPromptEditor: () -> Unit,
    showProminentDisclosure: Boolean,
    onDismissProminentDisclosure: () -> Unit,
    showUpdateDialog: Boolean,
    activeReleaseInfo: AppReleaseInfo?,
    downloadState: DownloadState?,
    onDismissUpdateDialog: () -> Unit,
    onDownloadStateChanged: (DownloadState?) -> Unit,
    showAiConfigDialog: Boolean = false,
    onDismissAiConfigDialog: () -> Unit = {},
    pendingBenchmarkPrompt: com.textmorph.app.llm.ModelActivationEvent? = null,
    onConfirmBenchmark: () -> Unit = {},
    onDismissBenchmarkPrompt: () -> Unit = {}
) {
    val settings by settingsRepository.settingsState.collectAsState(initial = settingsRepository.settingsState.value)

    pendingBenchmarkPrompt?.let { event ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissBenchmarkPrompt,
            icon = {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Speed,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFF3B82F6)
                )
            },
            title = {
                androidx.compose.material3.Text(
                    text = "Benchmark Model?",
                    color = androidx.compose.ui.graphics.Color(0xFFF9FAFB),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            },
            text = {
                androidx.compose.material3.Text(
                    text = "You just loaded \"${event.modelName}\" (${event.backend}).\n\nWould you like to run the Agentic Readiness Suite to benchmark its tool-calling accuracy, schema strictness, and latency?",
                    color = androidx.compose.ui.graphics.Color(0xFFD1D5DB),
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = onConfirmBenchmark,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF3B82F6)
                    )
                ) {
                    androidx.compose.material3.Text("Benchmark Now")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismissBenchmarkPrompt) {
                    androidx.compose.material3.Text(
                        "Maybe Later",
                        color = androidx.compose.ui.graphics.Color(0xFF9CA3AF)
                    )
                }
            },
            containerColor = androidx.compose.ui.graphics.Color(0xFF111827),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        )
    }

    if (showAiConfigDialog) {
        com.textmorph.app.ui.modelhub.components.AiModelConfigDialog(
            settings = settings,
            onSave = { maxTokens, temp, k, p, thinking, speculative, contextWindow, thinkingMode ->
                scope.launch {
                    settingsRepository.updateAiModelConfig(
                        maxTokens = maxTokens,
                        temperature = temp,
                        topK = k,
                        topP = p,
                        enableThinking = thinking,
                        speculativeDecoding = speculative,
                        defaultSystemPrompt = settings.defaultSystemPrompt,
                        agentSystemPrompt = settings.agentSystemPrompt,
                        contextWindowTokens = contextWindow,
                        thinkingMode = thinkingMode
                    )
                    onDismissAiConfigDialog()
                    Toast.makeText(context, "AI configurations saved.", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = onDismissAiConfigDialog
        )
    }
    if (showPromptEditor) {
        PromptEditorDialog(
            prompt = editingPrompt,
            onDismiss = onDismissPromptEditor,
            onSave = { updated ->
                scope.launch {
                    if (editingPrompt == null) {
                        promptRepository.addPrompt(updated)
                    } else {
                        promptRepository.updatePrompt(updated)
                    }
                    onDismissPromptEditor()
                    Toast.makeText(context, "Prompt saved successfully.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showProminentDisclosure) {
        ProminentDisclosureDialog(
            onDismiss = onDismissProminentDisclosure,
            onAcceptAndContinue = {
                onDismissProminentDisclosure()
                scope.launch { settingsRepository.setProminentDisclosureShown(true) }
                PermissionUtils.openAccessibilitySettings(context)
            }
        )
    }

    var downloadJob by remember { mutableStateOf<Job?>(null) }

    if (showUpdateDialog && activeReleaseInfo != null) {
        UpdateDialog(
            releaseInfo = activeReleaseInfo,
            downloadState = downloadState,
            onDismiss = {
                downloadJob?.cancel()
                downloadJob = null
                onDismissUpdateDialog()
            },
            onStartDownload = {
                if (downloadJob?.isActive == true || downloadState is DownloadState.Starting || downloadState is DownloadState.Progress) {
                    return@UpdateDialog
                }
                onDownloadStateChanged(DownloadState.Starting)
                downloadJob = scope.launch(Dispatchers.IO) {
                    updateRepository.downloadApk(activeReleaseInfo.downloadUrl).collect { state ->
                        withContext(Dispatchers.Main) {
                            onDownloadStateChanged(state)
                        }
                    }
                }
            },
            onInstall = {
                if (downloadState is DownloadState.Completed) {
                    updateRepository.installApk(downloadState.file)
                } else {
                    updateRepository.getDownloadedApkFile()?.let { file ->
                        updateRepository.installApk(file)
                    }
                }
            }
        )
    }
}
