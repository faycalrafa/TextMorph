package com.textmorph.app.service.overlay

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
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
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.llm.context.ChatCompactor
import com.textmorph.app.ui.chat.components.ActionExecutionCard
import com.textmorph.app.ui.chat.components.AttachedMediaItem
import com.textmorph.app.ui.chat.components.AttachmentPickerHelper
import com.textmorph.app.ui.chat.components.ChatInputBar
import com.textmorph.app.ui.chat.components.ContextCapacityMeterPill
import com.textmorph.app.ui.chat.components.HtmlSandboxPreviewCard
import com.textmorph.app.ui.chat.components.ThinkingAccordionCard
import com.textmorph.app.ui.chat.components.VoiceInputHelper
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Messenger-style floating chat window for the TextMorph companion overlay.
 * Features real-time voice, multi-image/file attachments, HTML iframe sandboxes, and thinking accordions.
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

    // File / Multi-Image picker launcher
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

    // Scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF2C3930), // Forest clay dark surface
        border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFF3F4F44)),
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
                    .background(Color(0xFF232D26))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFA27B5C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✦", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "TextMorph Agent",
                            color = Color(0xFFDCD7C9),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = activeModelName,
                            color = Color(0xFFA27B5C),
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
                        onClick = onSwitchToToolbar,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ViewStream,
                            contentDescription = "Toolbar",
                            tint = Color(0xFFDCD7C9),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onMinimizeToBubble,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Minimize",
                            tint = Color(0xFFDCD7C9),
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
                    .background(Color(0xFF1E2822))
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
                                color = Color(0xFF9EABA2),
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    if (msg.role == "user") {
                        // User message bubble (Right aligned)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(
                                shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                                color = Color(0xFFA27B5C),
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                Text(
                                    text = msg.content,
                                    color = Color(0xFFFBFBFB),
                                    fontSize = 12.5.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    } else {
                        // Assistant / Action message bubble
                        Column(modifier = Modifier.fillMaxWidth(0.92f)) {
                            // Thinking Accordion if thoughts present
                            msg.thoughtContent?.let { thoughts ->
                                if (thoughts.isNotBlank()) {
                                    ThinkingAccordionCard(
                                        thoughtContent = thoughts,
                                        isThinking = isGenerating && msg.content.isBlank()
                                    )
                                }
                            }

                            // Action card if action payload present
                            msg.actionPayloadJson?.let { payload ->
                                ActionExecutionCard(
                                    actionName = "Automation Action",
                                    reasoning = msg.content,
                                    status = msg.actionStatus ?: "success"
                                )
                            } ?: run {
                                // HTML detection check
                                val isHtml = msg.content.contains("<!DOCTYPE html", true) ||
                                    msg.content.contains("<html", true) ||
                                    msg.content.contains("```html", true)

                                if (isHtml) {
                                    val cleanHtml = msg.content
                                        .substringAfter("```html", msg.content)
                                        .substringBeforeLast("```")
                                    HtmlSandboxPreviewCard(
                                        htmlContent = cleanHtml,
                                        isDone = !isGenerating
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                                        color = Color(0xFF2C3930),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3F4F44))
                                    ) {
                                        Text(
                                            text = msg.content.ifEmpty { "Thinking…" },
                                            color = Color(0xFFDCD7C9),
                                            fontSize = 12.5.sp,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                onPickMedia = { mediaPickerLauncher.launch("*/*") },
                onToggleVoice = {
                    if (isListening) {
                        voiceHelper.stopListening()
                    } else {
                        voiceHelper.startListening { transcript ->
                            inputText = transcript
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
                        onAutomateAction(promptText)
                        return@ChatInputBar
                    }

                    val activeId = currentSessionId ?: return@ChatInputBar
                    val userMsg = ChatMessageEntity(
                        sessionId = activeId,
                        role = "user",
                        content = promptText
                    )
                    inputText = ""
                    attachments.clear()

                    scope.launch {
                        val activeSession = chatSessionRepository.getActiveSession()
                        val assembledPrompt = ChatCompactor.assembleInferencePrompt(
                            systemPrompt = ChatCompactor.DEFAULT_SYSTEM_PROMPT,
                            sessionSummary = activeSession?.summary,
                            sessionState = activeSession?.sessionState,
                            historyMessages = messages,
                            currentPrompt = promptText
                        )

                        chatSessionRepository.appendMessage(userMsg)
                        isGenerating = true

                        val assistantMsg = ChatMessageEntity(
                            sessionId = activeId,
                            role = "assistant",
                            content = ""
                        )
                        chatSessionRepository.appendMessage(assistantMsg)

                        generationJob = launch {
                            try {
                                val flow = when {
                                    liteRtEngine.sessionState.value.isLoaded -> liteRtEngine.generateStream(assembledPrompt)
                                    llamaCppEngine.sessionState.value.isLoaded -> llamaCppEngine.generateStream(assembledPrompt)
                                    else -> cloudEngine.generateStream(assembledPrompt)
                                }
                                val accumulated = StringBuilder()
                                flow.collect { token ->
                                    accumulated.append(token)
                                    chatSessionRepository.updateLastAssistantMessage(activeId, accumulated.toString())
                                }

                                val allMessages = chatSessionRepository.currentMessages.value
                                if (allMessages.size >= ChatCompactor.SUMMARY_TRIGGER_MESSAGE_COUNT) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val (older, _) = ChatCompactor.partitionWindow(allMessages)
                                            if (older.isNotEmpty()) {
                                                val activeEngine = if (liteRtEngine.sessionState.value.isLoaded) liteRtEngine else llamaCppEngine
                                                val newSummary = ChatCompactor.summarizeTurns(activeEngine, activeSession?.summary, older)
                                                val updatedFacts = ChatCompactor.extractSessionFacts(activeEngine, activeSession?.sessionState, allMessages)
                                                chatSessionRepository.updateSessionCompactionState(activeId, newSummary, updatedFacts)
                                            }
                                        } catch (e: Exception) {
                                            AppLogger.e(TAG, "Background compaction failed: ${e.message}", e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Chat generation error: ${e.message}", e)
                            } finally {
                                isGenerating = false
                            }
                        }
                    }
                },
                onStop = {
                    generationJob?.cancel()
                    isGenerating = false
                }
            )
        }
    }
}
