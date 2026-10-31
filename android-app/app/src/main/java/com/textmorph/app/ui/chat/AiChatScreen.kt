package com.textmorph.app.ui.chat

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.textmorph.app.data.model.ArtifactMetadata
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.chat.components.AiChatInferenceExecutor
import com.textmorph.app.ui.chat.components.AiChatSheets
import com.textmorph.app.ui.chat.components.AiChatTopBar
import com.textmorph.app.ui.chat.components.ArtifactReaderScreen
import com.textmorph.app.ui.chat.components.AttachedMediaItem
import com.textmorph.app.ui.chat.components.AttachmentPickerHelper
import com.textmorph.app.ui.chat.components.ChatInputBar
import com.textmorph.app.ui.chat.components.ChatMessagesList
import com.textmorph.app.ui.chat.components.ChatRenameSessionDialog
import com.textmorph.app.ui.chat.components.MissingPermissionsTooltip
import com.textmorph.app.ui.chat.components.VoiceInputHelper
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.PermissionUtils
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AiChatContent(
    liteRtEngine: LiteRtLmEngine,
    settingsRepository: SettingsRepository,
    modelRepository: ModelStorageRepository,
    llamaCppEngine: LlamaCppEngine = LlamaCppEngineManager.getInstance(LocalContext.current),
    chatRepository: ChatSessionRepository = ChatSessionRepository.getInstance(LocalContext.current),
    onNavigateToModelHub: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    onOpenAccessibilityDisclosure: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val settings by settingsRepository.settingsState.collectAsState()
    val availableModels by modelRepository.availableModels.collectAsState()
    val sessions by chatRepository.sessions.collectAsState()
    val activeSessionId by chatRepository.activeSessionId.collectAsState()
    val currentSession = remember(sessions, activeSessionId) {
        sessions.firstOrNull { it.id == activeSessionId }
    }
    val visibleMessages by chatRepository.currentMessages.collectAsState()
    val activeGenerationStatus by chatRepository.activeGenerationStatus.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<AttachedMediaItem>() }
    var isGenerating by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var showModelSwitchSheet by remember { mutableStateOf(false) }
    var showCapacitySheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showSessionsSheet by remember { mutableStateOf(false) }
    var showRenameSessionDialog by remember { mutableStateOf(false) }
    var viewingArtifact by remember { mutableStateOf<ArtifactMetadata?>(null) }

    if (viewingArtifact != null) {
        BackHandler { viewingArtifact = null }
        ArtifactReaderScreen(
            artifact = viewingArtifact!!,
            onBack = { viewingArtifact = null }
        )
        return
    }

    fun getActiveEngine(): com.textmorph.app.llm.LlmEngine {
        return when (settings.selectedEngine) {
            EngineType.CLOUD_OPENAI -> {
                val cloudEngine = OpenAiCloudEngine()
                cloudEngine.updateConfig(settings.cloudBaseUrl, settings.cloudApiKey, settings.cloudModelId)
                cloudEngine
            }
            EngineType.ON_DEVICE_LLAMA_CPP -> llamaCppEngine
            else -> liteRtEngine
        }
    }

    val agentEngine = remember {
        com.textmorph.app.agent.AgentEngine(
            context = context,
            engineProvider = ::getActiveEngine
        )
    }

    val voiceHelper = remember { VoiceInputHelper(context) }
    val isListening by voiceHelper.isListening.collectAsState()

    val contextMetrics = remember(visibleMessages, inputText, attachments.size, activeSessionId, sessions) {
        chatRepository.computeActiveContextMetrics(
            currentInput = inputText,
            currentImageCount = attachments.count { it.isImage }
        )
    }

    val isLlamaActive = settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP
    val liteRtSessionState by liteRtEngine.sessionState.collectAsState()
    val llamaSessionState by llamaCppEngine.sessionState.collectAsState()
    val isVisionCapable = if (isLlamaActive) llamaSessionState.isVisionCapable else liteRtSessionState.isVisionCapable

    val activeModelName = if (settings.selectedEngine == EngineType.CLOUD_OPENAI) {
        "Cloud: ${settings.cloudModelId}"
    } else {
        settings.activeModelName.ifEmpty { "No Model Active" }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        scope.launch {
            for (uri in uris) {
                AttachmentPickerHelper.resolveMediaItem(context, uri)?.let { item ->
                    val persisted = AttachmentPickerHelper.persistAttachmentLocally(context, item)
                    attachments.add(persisted)
                }
            }
        }
    }

    LaunchedEffect(visibleMessages.lastOrNull()?.content, visibleMessages.size) {
        if (visibleMessages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(visibleMessages.size - 1)
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            keyboardController?.hide()
        }
    }

    fun handleSend(overridePrompt: String? = null) {
        val effectiveText = overridePrompt ?: inputText
        if ((effectiveText.isNotBlank() || attachments.isNotEmpty()) && !isGenerating) {
            val prompt = effectiveText.trim()

            val (validatedAttachments, warning) = AttachmentPickerHelper.validateAttachments(
                modelName = activeModelName,
                isVisionSupported = isVisionCapable,
                items = attachments.toList()
            )
            warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }

            val primaryImage = validatedAttachments.firstOrNull { it.isImage }
            val imgBytes = primaryImage?.bitmap?.let { bmp ->
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            }
            val docText = validatedAttachments.firstOrNull { !it.isImage }?.textContent

            val chatAttachments = validatedAttachments.map { item ->
                com.textmorph.app.data.model.ChatAttachment(
                    name = item.name,
                    mimeType = item.mimeType,
                    contentSnippet = item.textContent,
                    localUri = item.uri.toString()
                )
            }

            inputText = ""
            attachments.clear()
            keyboardController?.hide()

            val targetSessionId = activeSessionId ?: "default_session"

            AiChatInferenceExecutor.executeTurn(
                sessionId = targetSessionId,
                prompt = prompt,
                imageBytes = imgBytes,
                documentText = docText,
                engine = getActiveEngine(),
                chatRepository = chatRepository,
                scope = scope,
                onStart = { isGenerating = true },
                onComplete = { isGenerating = false },
                setJob = { currentJob = it },
                agentEngine = agentEngine,
                attachments = chatAttachments,
                settings = settings,
                context = context
            )
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLogger.i("AiChatScreen", "Mic permission result: granted=$granted")
        if (granted) {
            voiceHelper.startListening { transcript ->
                val newText = if (inputText.isBlank()) transcript else "$inputText $transcript"
                inputText = newText
                if (settings.autoSendVoiceInput && newText.isNotBlank()) {
                    handleSend()
                }
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for voice dictation.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1020))
            .imePadding()
    ) {
        AiChatTopBar(
            activeModelName = activeModelName,
            contextMetrics = contextMetrics,
            hasMessages = visibleMessages.isNotEmpty(),
            onOpenDrawer = onOpenDrawer,
            onOpenModelSwitch = { showModelSwitchSheet = true },
            onOpenCapacitySheet = { showCapacitySheet = true },
            onNewChat = {
                currentJob?.cancel()
                agentEngine.cancelRunningTask()
                isGenerating = false
                scope.launch { chatRepository.createSession() }
            },
            onOpenSessions = { showSessionsSheet = true },
            onRenameSession = { showRenameSessionDialog = true },
            onClearChat = {
                currentJob?.cancel()
                agentEngine.cancelRunningTask()
                isGenerating = false
                scope.launch { chatRepository.clearSessionMessages(activeSessionId ?: "") }
            }
        )

        MissingPermissionsTooltip(onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure)

        ChatMessagesList(
            messages = visibleMessages,
            listState = listState,
            isGenerating = isGenerating,
            activeGenerationStatus = activeGenerationStatus,
            activeModelName = activeModelName,
            onSelectStarter = { handleSend(it) },
            onEditPrompt = { userMsg ->
                val targetId = activeSessionId ?: return@ChatMessagesList
                inputText = userMsg.content
                attachments.clear()
                userMsg.getAttachments().forEach { att ->
                    AttachmentPickerHelper.restoreAttachedMediaItem(context, att)?.let { attachments.add(it) }
                }
                scope.launch { chatRepository.truncateMessages(targetId, userMsg.id, inclusive = true) }
            },
            onRetryPrompt = { userMsg ->
                val targetId = activeSessionId ?: return@ChatMessagesList
                attachments.clear()
                userMsg.getAttachments().forEach { att ->
                    AttachmentPickerHelper.restoreAttachedMediaItem(context, att)?.let { attachments.add(it) }
                }
                scope.launch {
                    chatRepository.truncateMessages(targetId, userMsg.id, inclusive = true)
                    handleSend(userMsg.content)
                }
            },
            onInterruptAction = {
                currentJob?.cancel()
                agentEngine.cancelRunningTask()
                com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                isGenerating = false
                val targetId = activeSessionId ?: return@ChatMessagesList
                scope.launch(Dispatchers.IO) {
                    chatRepository.updateLastAssistantMessage(sessionId = targetId, content = "Task stopped by user.", actionStatus = "interrupted")
                }
            },
            onSelectOption = { handleSend(it) },
            onSelectSuggestion = { handleSend(it) },
            onViewArtifact = { viewingArtifact = it },
            onExecutePlan = { handleSend(it) },
            modifier = Modifier.weight(1f)
        )

        ChatInputBar(
            inputText = inputText,
            onInputTextChanged = { inputText = it },
            isGenerating = isGenerating,
            isListening = isListening,
            attachments = attachments,
            onRemoveAttachment = { attachments.remove(it) },
            onPickMedia = { showAttachmentSheet = true },
            onToggleVoice = {
                if (isListening) {
                    voiceHelper.stopListening()
                } else {
                    if (PermissionUtils.hasRecordAudioPermission(context)) {
                        voiceHelper.startListening { transcript ->
                            val newText = if (inputText.isBlank()) transcript else "$inputText $transcript"
                            inputText = newText
                            if (settings.autoSendVoiceInput && newText.isNotBlank()) handleSend()
                        }
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            capacityFraction = contextMetrics.usageFraction,
            onCapacityClick = { showCapacitySheet = true },
            isVisionSupported = isVisionCapable,
            onSend = ::handleSend,
            onStop = {
                currentJob?.cancel()
                agentEngine.cancelRunningTask()
                com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                isGenerating = false
                val targetId = activeSessionId
                if (targetId != null) {
                    scope.launch(Dispatchers.IO) {
                        chatRepository.updateLastAssistantMessage(sessionId = targetId, content = "Task stopped by user.", actionStatus = "interrupted")
                    }
                }
            }
        )
    }

    AiChatSheets(
        scope = scope,
        settings = settings,
        availableModels = availableModels,
        settingsRepository = settingsRepository,
        chatRepository = chatRepository,
        activeSessionId = activeSessionId,
        activeEngine = getActiveEngine(),
        contextMetrics = contextMetrics,
        showAttachmentSheet = showAttachmentSheet,
        onDismissAttachmentSheet = { showAttachmentSheet = false },
        onPhotoCaptured = { attachments.add(it) },
        onPickFiles = { mediaPickerLauncher.launch("*/*") },
        showModelSwitchSheet = showModelSwitchSheet,
        onDismissModelSwitchSheet = { showModelSwitchSheet = false },
        showCapacitySheet = showCapacitySheet,
        onDismissCapacitySheet = { showCapacitySheet = false },
        showSessionsSheet = showSessionsSheet,
        onDismissSessionsSheet = { showSessionsSheet = false },
        sessions = sessions,
        onSelectSession = { sessId -> scope.launch { chatRepository.switchSession(sessId) } },
        onCreateNewSession = { scope.launch { chatRepository.createSession() } },
        onRenameSession = { sessId, title -> scope.launch { chatRepository.renameSession(sessId, title) } },
        onDeleteSession = { sessId -> scope.launch { chatRepository.deleteSession(sessId) } }
    )

    if (showRenameSessionDialog && currentSession != null) {
        ChatRenameSessionDialog(
            initialTitle = currentSession.title,
            onConfirm = { newTitle ->
                scope.launch { chatRepository.renameSession(currentSession.id, newTitle) }
            },
            onDismiss = { showRenameSessionDialog = false }
        )
    }
}
