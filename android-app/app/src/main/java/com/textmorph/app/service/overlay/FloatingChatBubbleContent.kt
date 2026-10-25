package com.textmorph.app.service.overlay

import android.graphics.Bitmap
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
import com.textmorph.app.ui.chat.components.ContextCapacityMeterPill
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
    modifier: Modifier = Modifier
) {
    val TAG = "FloatingChatBubble"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val currentSessionId by chatSessionRepository.activeSessionId.collectAsState()
    val messages by chatSessionRepository.currentMessages.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    val attachments = remember { mutableStateListOf<AttachedMediaItem>() }

    val voiceHelper = remember { VoiceInputHelper(context) }
    val isListening by voiceHelper.isListening.collectAsState()

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
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1F2937)) // Elevated Slate
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2563EB)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✦", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "TextMorph Agent",
                            color = Color(0xFFF9FAFB),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = activeModelName,
                            color = Color(0xFF60A5FA),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ContextCapacityMeterPill(
                        metrics = contextMetrics,
                        onClick = {
                            val activeId = currentSessionId ?: return@ContextCapacityMeterPill
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
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            onFocusRequested(false)
                            onSwitchToToolbar()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ViewStream,
                            contentDescription = "Toolbar",
                            tint = Color(0xFFE5E7EB),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            onFocusRequested(false)
                            onMinimizeToBubble()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Minimize",
                            tint = Color(0xFFE5E7EB),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

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
                        isGenerating = isGenerating && msg.id == messages.lastOrNull()?.id
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
                isGenerating = isGenerating,
                isListening = isListening,
                attachments = attachments,
                onRemoveAttachment = { attachments.remove(it) },
                onPickMedia = {
                    AppLogger.d(TAG, "Launching OverlayMediaPickerActivity trampoline")
                    OverlayMediaPickerActivity.launch(context)
                },
                onToggleVoice = {
                    if (isListening) {
                        voiceHelper.stopListening()
                    } else {
                        voiceHelper.startListening { transcript ->
                            inputText = if (inputText.isBlank()) transcript else "$inputText $transcript"
                            onFocusRequested(true)
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

                    val activeId = currentSessionId ?: return@ChatInputBar

                    // Validate attachments against model capabilities
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

                    val activeEngine = when {
                        liteRtEngine.sessionState.value.isLoaded -> liteRtEngine
                        llamaCppEngine.sessionState.value.isLoaded -> llamaCppEngine
                        else -> cloudEngine
                    }

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
                        setJob = { generationJob = it }
                    )
                },
                onStop = {
                    generationJob?.cancel()
                    isGenerating = false
                }
            )
        }
    }
}
