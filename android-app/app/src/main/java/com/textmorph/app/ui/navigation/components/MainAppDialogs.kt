package com.textmorph.app.ui.navigation.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

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
    showLlmDumpDialog: Boolean = false,
    onDismissLlmDumpDialog: () -> Unit = {},
    chatRepository: com.textmorph.app.data.repository.ChatSessionRepository? = null
) {
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

    if (showUpdateDialog && activeReleaseInfo != null) {
        UpdateDialog(
            releaseInfo = activeReleaseInfo,
            downloadState = downloadState,
            onDismiss = onDismissUpdateDialog,
            onStartDownload = {
                scope.launch {
                    updateRepository.downloadApk(activeReleaseInfo.downloadUrl).collect { state ->
                        onDownloadStateChanged(state)
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

    if (showLlmDumpDialog && chatRepository != null) {
        com.textmorph.app.ui.modelhub.components.LlmConversationDumpDialog(
            chatRepository = chatRepository,
            onDismiss = onDismissLlmDumpDialog
        )
    }
}
