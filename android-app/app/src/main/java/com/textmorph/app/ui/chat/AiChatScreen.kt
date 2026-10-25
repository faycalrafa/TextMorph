package com.textmorph.app.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.llm.context.ChatCompactor
import com.textmorph.app.ui.chat.components.AiChatInferenceExecutor
import com.textmorph.app.ui.chat.components.AttachedMediaItem
import com.textmorph.app.ui.chat.components.AttachmentPickerHelper
import com.textmorph.app.ui.chat.components.ChatInputBar
import com.textmorph.app.ui.chat.components.ChatMessageItemView
import com.textmorph.app.ui.chat.components.ChatSessionsDrawerContent
import com.textmorph.app.ui.chat.components.ContextCapacityBottomSheet
import com.textmorph.app.ui.chat.components.ContextCapacityMeterPill
import com.textmorph.app.ui.chat.components.ModelQuickSwitchBottomSheet
import com.textmorph.app.ui.chat.components.VoiceInputHelper
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
    val messages by chatRepository.currentMessages.collectAsState()
    val activeGenerationStatus by chatRepository.activeGenerationStatus.collectAsState()
    val currentSession = sessions.find { it.id == activeSessionId }
    val isAgentMode = currentSession?.isAgentMode == true

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
    var inputText by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<AttachedMediaItem>() }
    var isGenerating by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var showModelSwitchSheet by remember { mutableStateOf(false) }
    var showCapacitySheet by remember { mutableStateOf(false) }

    val voiceHelper = remember { VoiceInputHelper(context) }
    val isListening by voiceHelper.isListening.collectAsState()

    val contextMetrics = remember(messages, inputText, attachments.size, activeSessionId, sessions) {
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
                    attachments.add(item)
                }
            }
        }
    }

    LaunchedEffect(messages.lastOrNull()?.content, messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun handleSend() {
        if ((inputText.isNotBlank() || attachments.isNotEmpty()) && !isGenerating) {
            val prompt = inputText.trim()

            // Validate attachments against model vision capabilities
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
                agentEngine = agentEngine
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1020)) // Charcoal Slate deep midnight
            .imePadding()
    ) {
        // Top Bar
        Surface(
            color = Color(0xFF111827), // Elevated midnight
            border = BorderStroke(1.dp, Color(0xFF1F2937)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color(0xFFE5E7EB))
                    }

                    // Quick Model Switcher Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1F2937))
                            .border(1.dp, Color(0xFF374151), RoundedCornerShape(8.dp))
                            .clickable { showModelSwitchSheet = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF3B82F6), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = activeModelName,
                            color = Color(0xFFF9FAFB),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Context Capacity Gauge Ring Icon (No cluttered text in top bar)
                    com.textmorph.app.ui.chat.components.ContextCapacityRingIcon(
                        metrics = contextMetrics,
                        onClick = { showCapacitySheet = true }
                    )

                    if (messages.isNotEmpty()) {
                        IconButton(onClick = {
                            currentJob?.cancel()
                            isGenerating = false
                            scope.launch { chatRepository.clearSessionMessages(activeSessionId ?: "") }
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Chat", tint = Color(0xFFE5E7EB).copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }

        // Floating Dismissible Permissions Tooltip
        com.textmorph.app.ui.chat.components.MissingPermissionsTooltip(
            onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure
        )

        // Messages View
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Interactive AI Playground & Chat\nStart typing below to converse with $activeModelName",
                        color = Color(0xFF9CA3AF),
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageItemView(
                            message = msg,
                            isGenerating = isGenerating && msg.id == messages.lastOrNull()?.id,
                            generationStatus = activeGenerationStatus,
                            onEditPrompt = { userMsg ->
                                val targetId = activeSessionId ?: return@ChatMessageItemView
                                inputText = userMsg.content
                                scope.launch {
                                    chatRepository.truncateMessages(targetId, userMsg.id, inclusive = true)
                                }
                            },
                            onRetryPrompt = { userMsg ->
                                val targetId = activeSessionId ?: return@ChatMessageItemView
                                scope.launch {
                                    chatRepository.truncateMessages(targetId, userMsg.id, inclusive = true)
                                    inputText = userMsg.content
                                    handleSend()
                                }
                            }
                        )
                    }
                }
            }
        }

        // Chat / Agent Mode Pill Switcher (Gemini Style)
        com.textmorph.app.ui.chat.components.ChatAgentModeToggleRow(
            isAgentMode = isAgentMode,
            onModeChanged = { newAgentMode ->
                val targetId = activeSessionId ?: return@ChatAgentModeToggleRow
                scope.launch { chatRepository.setAgentMode(targetId, newAgentMode) }
            }
        )

        // Detached Rounded Input Bar
        ChatInputBar(
            inputText = inputText,
            onInputTextChanged = { inputText = it },
            isGenerating = isGenerating,
            isListening = isListening,
            attachments = attachments,
            onRemoveAttachment = { attachments.remove(it) },
            onPickMedia = { mediaPickerLauncher.launch("*/*") },
            onToggleVoice = {
                if (isListening) {
                    voiceHelper.stopListening()
                } else {
                    voiceHelper.startListening { transcript ->
                        val newText = if (inputText.isBlank()) transcript else "$inputText $transcript"
                        inputText = newText
                        if (settings.autoSendVoiceInput && newText.isNotBlank()) {
                            handleSend()
                        }
                    }
                }
            },
            capacityFraction = contextMetrics.usageFraction,
            onCapacityClick = { showCapacitySheet = true },
            onSend = ::handleSend,
            onStop = {
                currentJob?.cancel()
                isGenerating = false
            }
        )
    }

    if (showModelSwitchSheet) {
        ModelQuickSwitchBottomSheet(
            settings = settings,
            availableModels = availableModels,
            onDismiss = { showModelSwitchSheet = false },
            onSelectModel = { model ->
                scope.launch {
                    val targetEngine = when (model.modelFileType) {
                        com.textmorph.app.data.model.ModelFileType.GGUF -> com.textmorph.app.data.model.EngineType.ON_DEVICE_LLAMA_CPP
                        else -> com.textmorph.app.data.model.EngineType.ON_DEVICE_LITERT
                    }
                    settingsRepository.setActiveModel(model.name, model.path, model.uriString, targetEngine)
                    settingsRepository.setEngine(targetEngine)
                    showModelSwitchSheet = false
                    Toast.makeText(context, "Switched active model to: ${model.name}", Toast.LENGTH_SHORT).show()
                }
            },
            onSelectEngine = { engine ->
                scope.launch {
                    settingsRepository.setEngine(engine)
                    showModelSwitchSheet = false
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
                    val newSummary = ChatCompactor.summarizeTurns(getActiveEngine(), activeSession?.summary, targetMessages)
                    val updatedFacts = ChatCompactor.extractSessionFacts(getActiveEngine(), activeSession?.sessionState, allMessages)
                    chatRepository.updateSessionCompactionState(activeId, newSummary, updatedFacts)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Compacted history into summary", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onPruneContext = {
                val activeId = activeSessionId ?: return@ContextCapacityBottomSheet
                scope.launch(Dispatchers.IO) {
                    chatRepository.pruneSessionArtifacts(activeId)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Pruned context artifacts", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showCapacitySheet = false }
        )
    }
}
