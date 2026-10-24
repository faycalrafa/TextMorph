package com.textmorph.app.ui.chat

import android.graphics.BitmapFactory
import android.widget.Toast
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
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.chat.components.ActionExecutionCard
import com.textmorph.app.ui.chat.components.AiChatInferenceExecutor
import com.textmorph.app.ui.chat.components.ChatInputBar
import com.textmorph.app.ui.chat.components.ChatMessage
import com.textmorph.app.ui.chat.components.ChatSessionsDrawerContent
import com.textmorph.app.ui.chat.components.HtmlSandboxPreviewCard
import com.textmorph.app.ui.chat.components.ModelQuickSwitchBottomSheet
import com.textmorph.app.ui.chat.components.ThinkingAccordionCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

typealias ChatMessage = com.textmorph.app.ui.chat.components.ChatMessage

@Composable
fun AiChatContent(
    liteRtEngine: LiteRtLmEngine,
    settingsRepository: SettingsRepository,
    modelRepository: ModelStorageRepository,
    llamaCppEngine: LlamaCppEngine = LlamaCppEngineManager.getInstance(LocalContext.current),
    chatRepository: ChatSessionRepository = ChatSessionRepository.getInstance(LocalContext.current),
    onNavigateToModelHub: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val settings by settingsRepository.settingsState.collectAsState()
    val availableModels by modelRepository.availableModels.collectAsState()
    val sessions by chatRepository.sessions.collectAsState()
    val activeSessionId by chatRepository.activeSessionId.collectAsState()
    val messages by chatRepository.currentMessages.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var attachedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var attachedDocText by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var showModelSwitchSheet by remember { mutableStateOf(false) }

    val isLlamaActive = settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP
    val liteRtSessionState by liteRtEngine.sessionState.collectAsState()
    val llamaSessionState by llamaCppEngine.sessionState.collectAsState()
    val isVisionCapable = if (isLlamaActive) llamaSessionState.isVisionCapable else liteRtSessionState.isVisionCapable

    val activeModelName = if (settings.selectedEngine == EngineType.CLOUD_OPENAI) {
        "Cloud: ${settings.cloudModelId}"
    } else {
        settings.activeModelName.ifEmpty { "No Model Active" }
    }

    LaunchedEffect(messages.lastOrNull()?.content, messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
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

    fun handleSend() {
        if (inputText.isNotBlank() && !isGenerating) {
            val prompt = inputText
            val img = attachedImageBytes
            val doc = attachedDocText
            inputText = ""
            attachedImageBytes = null
            attachedDocText = null
            keyboardController?.hide()

            val targetSessionId = activeSessionId ?: "default_session"

            AiChatInferenceExecutor.executeTurn(
                sessionId = targetSessionId,
                prompt = prompt,
                imageBytes = img,
                documentText = doc,
                engine = getActiveEngine(),
                chatRepository = chatRepository,
                scope = scope,
                onStart = { isGenerating = true },
                onComplete = { isGenerating = false },
                setJob = { currentJob = it }
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatSessionsDrawerContent(
                sessions = sessions,
                activeSessionId = activeSessionId ?: "",
                onSelectSession = { id ->
                    scope.launch {
                        chatRepository.switchSession(id)
                        drawerState.close()
                    }
                },
                onNewChat = {
                    scope.launch {
                        chatRepository.createSession()
                        drawerState.close()
                    }
                },
                onDeleteSession = { id ->
                    scope.launch { chatRepository.deleteSession(id) }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2C3930))
                .imePadding()
        ) {
            // Top Bar
            Surface(
                color = Color(0xFF3F4F44),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Drawer", tint = Color(0xFFDCD7C9))
                        }

                        // Quick Model Switcher Pill
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF2C3930))
                                .border(1.dp, Color(0xFFA27B5C), RoundedCornerShape(8.dp))
                                .clickable { showModelSwitchSheet = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFFA27B5C), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = activeModelName,
                                color = Color(0xFFDCD7C9),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (messages.isNotEmpty()) {
                            IconButton(onClick = {
                                currentJob?.cancel()
                                isGenerating = false
                                scope.launch { chatRepository.clearSessionMessages(activeSessionId ?: "") }
                            }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Chat", tint = Color(0xFFDCD7C9).copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }

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
                            color = Color(0xFFDCD7C9).copy(alpha = 0.6f),
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
                            RenderChatMessageItem(msg = msg)
                        }
                    }
                }
            }

            // Input Bar
            ChatInputBar(
                inputText = inputText,
                onInputTextChanged = { inputText = it },
                isGenerating = isGenerating,
                onSend = ::handleSend,
                onStop = {
                    currentJob?.cancel()
                    isGenerating = false
                }
            )
        }
    }

    if (showModelSwitchSheet) {
        ModelQuickSwitchBottomSheet(
            settings = settings,
            availableModels = availableModels,
            onDismiss = { showModelSwitchSheet = false },
            onSelectModel = { model ->
                scope.launch {
                    settingsRepository.setActiveModel(model.name, model.path, model.uriString)
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
}

@Composable
private fun RenderChatMessageItem(msg: ChatMessageEntity) {
    val isUser = msg.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        if (isUser) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(Color(0xFFA27B5C))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(text = msg.content, color = Color(0xFF2C3930), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        } else {
            val content = msg.content
            val isHtml = content.contains("<!DOCTYPE html>", ignoreCase = true) || content.contains("<html", ignoreCase = true)
            val isAction = content.trim().startsWith("{\"action\":") || content.trim().startsWith("{\"target\":") || msg.role == "action"

            if (isHtml) {
                HtmlSandboxPreviewCard(htmlContent = content, isDone = content.isNotEmpty())
            } else if (isAction) {
                ActionExecutionCard(actionName = "Autonomous Action", reasoning = content, status = msg.actionStatus ?: "success")
            } else {
                if (!msg.thoughtContent.isNullOrBlank()) {
                    ThinkingAccordionCard(thoughtContent = msg.thoughtContent, isThinking = false)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (content.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                            .background(Color(0xFF3F4F44))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(text = content, color = Color(0xFFDCD7C9), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
