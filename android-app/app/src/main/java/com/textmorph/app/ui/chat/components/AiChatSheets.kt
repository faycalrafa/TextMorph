package com.textmorph.app.ui.chat.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.ContextUsageMetrics
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.model.ModelInfo
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.context.ChatCompactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encapsulates modal bottom sheets for AI Chat (attachment picker, model switcher, context capacity inspector).
 */
@Composable
fun AiChatSheets(
    scope: CoroutineScope,
    settings: AppSettings,
    availableModels: List<ModelInfo>,
    settingsRepository: SettingsRepository,
    chatRepository: ChatSessionRepository,
    activeSessionId: String?,
    activeEngine: LlmEngine,
    contextMetrics: ContextUsageMetrics,
    showAttachmentSheet: Boolean,
    onDismissAttachmentSheet: () -> Unit,
    onPhotoCaptured: (AttachedMediaItem) -> Unit,
    onPickFiles: () -> Unit,
    showModelSwitchSheet: Boolean,
    onDismissModelSwitchSheet: () -> Unit,
    showCapacitySheet: Boolean,
    onDismissCapacitySheet: () -> Unit
) {
    val context = LocalContext.current

    if (showAttachmentSheet) {
        ChatAttachmentBottomSheet(
            onDismiss = onDismissAttachmentSheet,
            onPhotoCaptured = onPhotoCaptured,
            onPickFiles = onPickFiles
        )
    }

    if (showModelSwitchSheet) {
        ModelQuickSwitchBottomSheet(
            settings = settings,
            availableModels = availableModels,
            onDismiss = onDismissModelSwitchSheet,
            onSelectModel = { model ->
                scope.launch {
                    val targetEngine = when (model.modelFileType) {
                        ModelFileType.GGUF -> EngineType.ON_DEVICE_LLAMA_CPP
                        else -> EngineType.ON_DEVICE_LITERT
                    }
                    settingsRepository.setActiveModel(model.name, model.path, model.uriString, targetEngine)
                    settingsRepository.setEngine(targetEngine)
                    onDismissModelSwitchSheet()
                    Toast.makeText(context, "Switched active model to: ${model.name}", Toast.LENGTH_SHORT).show()
                }
            },
            onSelectEngine = { engine ->
                scope.launch {
                    settingsRepository.setEngine(engine)
                    onDismissModelSwitchSheet()
                    Toast.makeText(context, "Switched engine to: ${engine.displayName}", Toast.LENGTH_SHORT).show()
                }
            },
            onSaveConfig = { maxTokens, temp, topP, thinking, accel ->
                scope.launch {
                    settingsRepository.updateAiModelConfig(
                        maxTokens = maxTokens,
                        temperature = temp,
                        topK = settings.topK,
                        topP = topP,
                        enableThinking = thinking,
                        speculativeDecoding = settings.speculativeDecoding,
                        defaultSystemPrompt = settings.defaultSystemPrompt
                    )
                    settingsRepository.setPreferredAccelerator(accel)
                }
            }
        )
    }

    if (showCapacitySheet) {
        ContextCapacityBottomSheet(
            metrics = contextMetrics,
            onCompressHistory = {
                val activeId = activeSessionId ?: return@ContextCapacityBottomSheet
                val activeSession = chatRepository.getActiveSession()
                val allMessages = chatRepository.currentMessages.value
                scope.launch(Dispatchers.IO) {
                    val (older, _) = ChatCompactor.partitionWindow(allMessages)
                    val targetMessages = if (older.isNotEmpty()) older else allMessages
                    val newSummary = ChatCompactor.summarizeTurns(activeEngine, activeSession?.summary, targetMessages)
                    val updatedFacts = ChatCompactor.extractSessionFacts(activeEngine, activeSession?.sessionState, allMessages)
                    chatRepository.updateSessionCompactionState(activeId, newSummary, updatedFacts)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Compacted history into summary", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onPruneContext = {
                val activeId = activeSessionId ?: return@ContextCapacityBottomSheet
                scope.launch(Dispatchers.IO) {
                    chatRepository.pruneSessionArtifacts(activeId)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Pruned context artifacts", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = onDismissCapacitySheet
        )
    }
}
