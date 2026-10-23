package com.textmorph.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.InferenceMetrics
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.ui.chat.components.ChatInputBar
import com.textmorph.app.ui.chat.components.ChatMessage
import com.textmorph.app.ui.chat.components.ChatMessageItem
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

// Re-export ChatMessage for backward compatibility
typealias ChatMessage = com.textmorph.app.ui.chat.components.ChatMessage

@Composable
fun AiChatContent(
    liteRtEngine: LiteRtLmEngine,
    settingsRepository: SettingsRepository,
    modelRepository: ModelStorageRepository,
    llamaCppEngine: LlamaCppEngine = LlamaCppEngineManager.getInstance(LocalContext.current),
    onNavigateToModelHub: () -> Unit
) {
    val TAG = "AiChatContent"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val settings by settingsRepository.settingsState.collectAsState()
    val isLlamaActive = settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }

    val liteRtSessionState by liteRtEngine.sessionState.collectAsState()
    val llamaSessionState by llamaCppEngine.sessionState.collectAsState()
    val sessionState = if (isLlamaActive) llamaSessionState else liteRtSessionState

    val activeModelName = if (sessionState.isLoaded) {
        sessionState.modelName
    } else {
        settings.activeModelName.ifEmpty { "No Model Active" }
    }

    // Auto-scroll to bottom as streaming tokens arrive
    LaunchedEffect(messages.lastOrNull()?.text, messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    val maxDimension = 1024
                    val scaledBitmap = if (originalBitmap.width > maxDimension || originalBitmap.height > maxDimension) {
                        val ratio = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
                        val (newW, newH) = if (ratio > 1f) {
                            maxDimension to (maxDimension / ratio).toInt()
                        } else {
                            (maxDimension * ratio).toInt() to maxDimension
                        }
                        Bitmap.createScaledBitmap(originalBitmap, newW, newH, true)
                    } else {
                        originalBitmap
                    }

                    val stream = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    selectedImageBytes = stream.toByteArray()
                    selectedBitmap = scaledBitmap

                    Toast.makeText(context, "Image attached (${selectedImageBytes!!.size / 1024} KB)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load image: ${e.message}", e)
                Toast.makeText(context, "Failed to attach image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleSend() {
        if (inputText.isNotBlank() && !isGenerating) {
            executePrompt(
                prompt = inputText,
                image = selectedImageBytes,
                imageBmp = selectedBitmap,
                isLlamaActive = isLlamaActive,
                liteRtEngine = liteRtEngine,
                llamaCppEngine = llamaCppEngine,
                messages = messages,
                scope = scope,
                onStart = {
                    isGenerating = true
                    inputText = ""
                    selectedBitmap = null
                    selectedImageBytes = null
                    keyboardController?.hide()
                },
                onComplete = { isGenerating = false },
                setJob = { currentJob = it }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Top Header Bar: Active Model Badge & Vision Indicator
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = if (sessionState.isLoaded) Color(0xFF48BB78) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = activeModelName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (sessionState.isLoaded) "${sessionState.backend} • Warm" else "Idle (Select in Model Hub)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sessionState.isLoaded) Color(0xFF48BB78) else MaterialTheme.colorScheme.error
                            )
                            if (sessionState.isVisionCapable) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF2B6CB0)
                                ) {
                                    Text(
                                        text = "Vision Enabled",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                currentJob?.cancel()
                                isGenerating = false
                                messages.clear()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Clear Chat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Selected Image Preview Banner (if attached)
        AnimatedVisibility(
            visible = selectedBitmap != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            selectedBitmap?.let { bmp ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(2.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Attached Thumbnail",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Multimodal Image Attached",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${bmp.width}x${bmp.height}px • ${selectedImageBytes?.size?.div(1024) ?: 0} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    selectedBitmap = null
                                    selectedImageBytes = null
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove Image",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Messages List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Interactive AI Playground",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (!sessionState.isLoaded) {
                            "No model is currently loaded in memory.\nVisit Model Hub to scan or activate a model."
                        } else if (sessionState.isVisionCapable) {
                            "Ask questions, benchmark native tokens/sec throughput, or attach photos to test Multimodal Vision."
                        } else {
                            "Send test prompts to benchmark real-time tokens/sec throughput and test streaming inference."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    if (!sessionState.isLoaded) {
                        Spacer(modifier = Modifier.height(16.dp))
                        androidx.compose.material3.Button(
                            onClick = onNavigateToModelHub,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Open Model Hub")
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(message = message)
                    }
                }
            }
        }

        // Bottom Chat Input Bar
        ChatInputBar(
            inputText = inputText,
            onInputTextChanged = { inputText = it },
            isGenerating = isGenerating,
            isVisionCapable = sessionState.isVisionCapable,
            activeModelName = activeModelName,
            selectedBitmap = selectedBitmap,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onSend = ::handleSend,
            onStop = {
                currentJob?.cancel()
                isGenerating = false
            }
        )
    }
}

private fun executePrompt(
    prompt: String,
    image: ByteArray?,
    imageBmp: Bitmap?,
    isLlamaActive: Boolean,
    liteRtEngine: LiteRtLmEngine,
    llamaCppEngine: LlamaCppEngine,
    messages: androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessage>,
    scope: kotlinx.coroutines.CoroutineScope,
    onStart: () -> Unit,
    onComplete: () -> Unit,
    setJob: (Job) -> Unit
) {
    onStart()

    // 1. Add user message
    val userMsg = ChatMessage(
        text = prompt,
        isUser = true,
        imageBitmap = imageBmp
    )
    messages.add(userMsg)

    // 2. Add placeholder AI message
    val aiMsgId = UUID.randomUUID().toString()
    val aiMsg = ChatMessage(
        id = aiMsgId,
        text = "",
        isUser = false,
        isStreaming = true
    )
    messages.add(aiMsg)

    // 3. Launch inference
    val job = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
        try {
            var currentText = ""
            var latestMetrics: InferenceMetrics? = null

            val stream = if (isLlamaActive) {
                llamaCppEngine.generateStreamWithMetrics(
                    prompt = prompt,
                    imageBytes = image,
                    onMetricsUpdated = { metrics ->
                        latestMetrics = metrics
                        val idx = messages.indexOfFirst { it.id == aiMsgId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(metrics = metrics)
                        }
                    }
                )
            } else {
                liteRtEngine.generateStreamWithMetrics(
                    prompt = prompt,
                    imageBytes = image,
                    onMetricsUpdated = { metrics ->
                        latestMetrics = metrics
                        val idx = messages.indexOfFirst { it.id == aiMsgId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(metrics = metrics)
                        }
                    }
                )
            }

            stream.collect { chunk ->
                currentText += chunk
                val idx = messages.indexOfFirst { it.id == aiMsgId }
                if (idx != -1) {
                    messages[idx] = messages[idx].copy(
                        text = currentText,
                        metrics = latestMetrics
                    )
                }
            }

            val finalIdx = messages.indexOfFirst { it.id == aiMsgId }
            if (finalIdx != -1) {
                messages[finalIdx] = messages[finalIdx].copy(
                    isStreaming = false,
                    metrics = latestMetrics
                )
            }
        } catch (e: Exception) {
            AppLogger.e("AiChatContent", "Inference error: ${e.message}", e)
            val idx = messages.indexOfFirst { it.id == aiMsgId }
            if (idx != -1) {
                messages[idx] = messages[idx].copy(
                    text = if (messages[idx].text.isEmpty()) "Inference stopped or encountered an error: ${e.message}" else messages[idx].text,
                    isStreaming = false
                )
            }
        } finally {
            onComplete()
        }
    }
    setJob(job)
}
