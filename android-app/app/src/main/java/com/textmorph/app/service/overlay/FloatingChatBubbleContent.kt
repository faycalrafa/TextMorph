package com.textmorph.app.service.overlay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.llm.context.ChatCompactor
import com.textmorph.app.ui.chat.components.AiChatInferenceExecutor
import com.textmorph.app.ui.chat.components.AttachedMediaItem
import com.textmorph.app.ui.chat.components.AttachmentPickerHelper
import com.textmorph.app.ui.chat.components.ChatInputBar
import com.textmorph.app.ui.chat.components.ChatMessageItemView
import com.textmorph.app.util.PermissionUtils
import com.textmorph.app.ui.chat.components.ContextCapacityRingIcon
import com.textmorph.app.ui.chat.components.VoiceInputHelper
import com.textmorph.app.util.AppLogger
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Messenger-style floating chat window for the TextMorph companion overlay.
 * Follows Charcoal Slate theme design language with voice dictation, media picker trampoline,
 * modular HTML isolation, and speed metrics.
 */
@Composable
fun FloatingChatBubbleContent(
    chatSessionRepository: ChatSessionRepository,
    liteRtEngine: LiteRtLmEngine,
    llamaCppEngine: LlamaCppEngine,
    cloudEngine: OpenAiCloudEngine,
    activeModelName: String,
    onMinimizeToBubble: () -> Unit,
    onSwitchToToolbar: () -> Unit,
    onAutomateAction: (goal: String) -> Unit,
    onFocusRequested: (Boolean) -> Unit,
    autoSendVoiceInput: Boolean = true,
    onTakeScreenshotContext: (((ByteArray) -> Unit) -> Unit)? = null,
    engineProvider: (() -> com.textmorph.app.llm.LlmEngine)? = null,
    modifier: Modifier = Modifier
) {
    val TAG = "FloatingChatBubble"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val currentSessionId by chatSessionRepository.activeSessionId.collectAsState()
    val messages by chatSessionRepository.currentMessages.collectAsState()
    val activeGenerationStatus by chatSessionRepository.activeGenerationStatus.collectAsState()
    val sessions by chatSessionRepository.sessionsState.collectAsState()
    val activeSession = sessions.find { it.id == currentSessionId }
    val isAgentMode = activeSession?.isAgentMode == true

    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var voiceDebounceJob by remember { mutableStateOf<Job?>(null) }
    val attachments = remember { mutableStateListOf<AttachedMediaItem>() }

    fun resolveActiveEngine(): com.textmorph.app.llm.LlmEngine {
        return engineProvider?.invoke() ?: when {
            liteRtEngine.sessionState.value.isLoaded -> liteRtEngine
            llamaCppEngine.sessionState.value.isLoaded -> llamaCppEngine
            else -> cloudEngine
        }
    }

    val agentEngine = remember {
        com.textmorph.app.agent.AgentEngine(
            context = context,
            engineProvider = ::resolveActiveEngine
        )
    }

    val voiceHelper = remember { VoiceInputHelper(context) }
    val isListening by voiceHelper.isListening.collectAsState()

    fun sendPrompt(promptText: String) {
        val activeId = currentSessionId ?: return
        val isVisionCapable = liteRtEngine.sessionState.value.isVisionCapable ||
            llamaCppEngine.sessionState.value.isVisionCapable
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

        val activeEngine = resolveActiveEngine()

        AiChatInferenceExecutor.executeTurn(
            sessionId = activeId,
            prompt = promptText,
            imageBytes = imgBytes,
            documentText = docText,
            engine = activeEngine,
            chatRepository = chatSessionRepository,
            scope = scope,
            onStart = { isGenerating = true },
            onComplete = { isGenerating = false },
            setJob = { generationJob = it },
            agentEngine = agentEngine
        )
    }

    val contextMetrics = remember(messages, inputText, attachments.size, currentSessionId) {
        chatSessionRepository.computeActiveContextMetrics(
            currentInput = inputText,
            currentImageCount = attachments.count { it.isImage }
        )
    }

    // Safely collect media from translucent trampoline activity without ActivityResultRegistry crash
    LaunchedEffect(Unit) {
        OverlayMediaPickerBus.pickedMediaFlow.collect { uris ->
            AppLogger.d(TAG, "Received ${uris.size} media URIs from trampoline picker")
            for (uri in uris) {
                AttachmentPickerHelper.resolveMediaItem(context, uri)?.let { item ->
                    attachments.add(item)
                }
            }
        }
    }

    // Scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xF2111827), // Charcoal Slate dark midnight
        border = BorderStroke(1.2.dp, Color(0x663B82F6)), // Subtle electric blue accent
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(440.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Dock
            com.textmorph.app.service.overlay.components.FloatingChatBubbleHeader(
                context = context,
                isAgentMode = isAgentMode,
                contextMetrics = contextMetrics,
                onToggleAgentMode = {
                    val currentId = currentSessionId ?: return@FloatingChatBubbleHeader
                    scope.launch { chatSessionRepository.setAgentMode(currentId, !isAgentMode) }
                },
                onContextClick = {
                    val activeId = currentSessionId ?: return@FloatingChatBubbleHeader
                    val activeSession = chatSessionRepository.getActiveSession()
                    Toast.makeText(
                        context,
                        "Context: ${contextMetrics.totalUsedTokens}/${contextMetrics.maxContextTokens} tok (${contextMetrics.colorState.label})",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (contextMetrics.usageFraction >= 0.6f) {
                        scope.launch(Dispatchers.IO) {
                            val (older, _) = ChatCompactor.partitionWindow(messages)
                            if (older.isNotEmpty()) {
                                val activeEngine = if (liteRtEngine.sessionState.value.isLoaded) liteRtEngine else llamaCppEngine
                                val newSummary = ChatCompactor.summarizeTurns(activeEngine, activeSession?.summary, older)
                                val updatedFacts = ChatCompactor.extractSessionFacts(activeEngine, activeSession?.sessionState, messages)
                                chatSessionRepository.updateSessionCompactionState(activeId, newSummary, updatedFacts)
                            }
                        }
                    }
                },
                onOpenFullChat = {
                    onFocusRequested(false)
                    onMinimizeToBubble()
                    try {
                        val fullChatIntent = Intent(context, com.textmorph.app.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(fullChatIntent)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed launching full chat: ${e.message}", e)
                    }
                },
                onSwitchToToolbar = {
                    onFocusRequested(false)
                    onSwitchToToolbar()
                },
                onDismiss = {
                    onFocusRequested(false)
                    onMinimizeToBubble()
                }
            )

            // Messages Log
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0B1020))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Ask anything or command:\n\"Open YouTube and search cats\"",
                                color = Color(0xFF9CA3AF),
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    ChatMessageItemView(
                        message = msg,
                        isGenerating = isGenerating && msg.id == messages.lastOrNull()?.id,
                        generationStatus = activeGenerationStatus,
                        onEditPrompt = { userMsg ->
                            val activeId = currentSessionId ?: return@ChatMessageItemView
                            inputText = userMsg.content
                            scope.launch {
                                chatSessionRepository.truncateMessages(activeId, userMsg.id, inclusive = true)
                            }
                            onFocusRequested(true)
                        },
                        onRetryPrompt = { userMsg ->
                            val activeId = currentSessionId ?: return@ChatMessageItemView
                            scope.launch {
                                chatSessionRepository.truncateMessages(activeId, userMsg.id, inclusive = true)
                                sendPrompt(userMsg.content)
                            }
                        },
                        onInterruptAction = {
                            generationJob?.cancel()
                            agentEngine?.cancelRunningTask()
                            com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                            isGenerating = false
                            val activeId = currentSessionId ?: return@ChatMessageItemView
                            scope.launch(Dispatchers.IO) {
                                chatSessionRepository.updateLastAssistantMessage(
                                    sessionId = activeId,
                                    content = "Task stopped by user.",
                                    actionStatus = "interrupted"
                                )
                            }
                        }
                    )
                }
            }

            // Input Bar with soft keyboard focus hooks
            ChatInputBar(
                inputText = inputText,
                onInputTextChanged = { text ->
                    inputText = text
                    onFocusRequested(true)
                },
                onInputFocus = { onFocusRequested(true) },
                isGenerating = isGenerating,
                isListening = isListening,
                attachments = attachments,
                onRemoveAttachment = { attachments.remove(it) },
                onPickMedia = {
                    if (onTakeScreenshotContext != null) {
                        Toast.makeText(context, "Capturing screen underneath...", Toast.LENGTH_SHORT).show()
                        onTakeScreenshotContext { screenBytes ->
                            try {
                                val bmp = BitmapFactory.decodeByteArray(screenBytes, 0, screenBytes.size)
                                if (bmp != null) {
                                    attachments.add(
                                        AttachedMediaItem(
                                            uri = android.net.Uri.EMPTY,
                                            name = "Current Screen",
                                            mimeType = "image/png",
                                            isImage = true,
                                            bitmap = bmp
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Failed attaching current screen: ${e.message}", e)
                            }
                        }
                    } else {
                        AppLogger.d(TAG, "Launching OverlayMediaPickerActivity trampoline")
                        OverlayMediaPickerActivity.launch(context)
                    }
                },
                onToggleVoice = {
                    if (isListening) {
                        voiceDebounceJob?.cancel()
                        voiceHelper.stopListening()
                    } else {
                        if (!PermissionUtils.hasRecordAudioPermission(context)) {
                            Toast.makeText(context, "Microphone permission is required for voice input. Please grant in TextMorph settings.", Toast.LENGTH_LONG).show()
                        } else {
                            voiceHelper.startListening { transcript ->
                                val newText = if (inputText.isBlank()) transcript else "$inputText $transcript"
                                inputText = newText
                                onFocusRequested(true)
                                voiceDebounceJob?.cancel()
                                if (autoSendVoiceInput && newText.isNotBlank()) {
                                    voiceDebounceJob = scope.launch {
                                        kotlinx.coroutines.delay(1500L) // 1.5s silence breathing room
                                        val promptText = inputText.trim()
                                        if (promptText.isNotBlank()) {
                                            val isAutomationIntent = promptText.startsWith("open ", true) ||
                                                promptText.startsWith("search ", true) ||
                                                promptText.contains("and search", true) ||
                                                promptText.contains("and click", true) ||
                                                promptText.startsWith("turn on ", true)

                                            if (isAutomationIntent) {
                                                inputText = ""
                                                onFocusRequested(false)
                                                onAutomateAction(promptText)
                                            } else {
                                                sendPrompt(promptText)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                capacityFraction = contextMetrics.usageFraction,
                onSend = {
                    val promptText = inputText.trim()
                    if (promptText.isEmpty() && attachments.isEmpty()) return@ChatInputBar

                    val isAutomationIntent = promptText.startsWith("open ", true) ||
                        promptText.startsWith("search ", true) ||
                        promptText.contains("and search", true) ||
                        promptText.contains("and click", true) ||
                        promptText.startsWith("turn on ", true)

                    if (isAutomationIntent) {
                        inputText = ""
                        onFocusRequested(false)
                        onAutomateAction(promptText)
                        return@ChatInputBar
                    }

                    sendPrompt(promptText)
                },
                onStop = {
                    generationJob?.cancel()
                    agentEngine?.cancelRunningTask()
                    com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                    isGenerating = false
                    val activeId = currentSessionId
                    if (activeId != null) {
                        scope.launch(Dispatchers.IO) {
                            chatSessionRepository.updateLastAssistantMessage(
                                sessionId = activeId,
                                content = "Task stopped by user.",
                                actionStatus = "interrupted"
                            )
                        }
                    }
                }
            )
        }
    }
}
