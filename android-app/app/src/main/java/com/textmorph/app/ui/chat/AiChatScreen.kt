package com.textmorph.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.InferenceMetrics
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val imageBitmap: Bitmap? = null,
    val metrics: InferenceMetrics? = null,
    val isStreaming: Boolean = false
)

@Composable
fun AiChatContent(
    liteRtEngine: LiteRtLmEngine,
    settingsRepository: SettingsRepository,
    modelRepository: ModelStorageRepository,
    onNavigateToModelHub: () -> Unit
) {
    val TAG = "AiChatContent"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }

    val sessionState by liteRtEngine.sessionState.collectAsState()
    val loadingProgress by liteRtEngine.loadingProgress.collectAsState()

    val isModelLoaded = sessionState.isLoaded
    val activeModelName = sessionState.modelName
    val activeBackend = sessionState.backend

    // Photo picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val rawBitmap = BitmapFactory.decodeStream(stream)
                    if (rawBitmap != null) {
                        // Scale to max 1024x1024 to preserve GPU memory
                        val maxDim = 1024
                        val scaled = if (rawBitmap.width > maxDim || rawBitmap.height > maxDim) {
                            val ratio = minOf(maxDim.toFloat() / rawBitmap.width, maxDim.toFloat() / rawBitmap.height)
                            Bitmap.createScaledBitmap(rawBitmap, (rawBitmap.width * ratio).toInt(), (rawBitmap.height * ratio).toInt(), true)
                        } else rawBitmap

                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                        selectedImageBytes = bos.toByteArray()
                        selectedBitmap = scaled
                        AppLogger.i(TAG, "Selected image attached: ${selectedImageBytes?.size} bytes")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load selected image: ${e.message}", e)
            }
        }
    }

    // Auto-scroll when messages update
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Top Model & Hardware Acceleration Status Bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(12.dp)
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
                    modifier = Modifier.weight(1f)
                ) {
                    if (loadingProgress.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = "Model",
                            tint = if (isModelLoaded) Color(0xFF68D391) else Color(0xFFA27B5C),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (loadingProgress.isLoading) "Loading: ${sessionState.modelName}"
                            else if (isModelLoaded) activeModelName
                            else "No Local Model Loaded",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = if (loadingProgress.isLoading) loadingProgress.statusText
                            else if (isModelLoaded) "Backend: $activeBackend"
                            else "Tap here to pick model in Hub",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isModelLoaded) MaterialTheme.colorScheme.primary else Color(0xFFA27B5C)
                            ),
                            modifier = Modifier.then(
                                if (!isModelLoaded && !loadingProgress.isLoading) Modifier.clickable { onNavigateToModelHub() } else Modifier
                            )
                        )
                    }
                }

                if (messages.isNotEmpty()) {
                    IconButton(
                        onClick = { messages.clear() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear Chat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Message List
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
                        tint = Color(0xFFA27B5C).copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "AI Model Playground",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Test real local weights on your Adreno/Mali GPU or CPU with live tokens/sec speed telemetry and multimodal image input.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(message = message)
                    }
                }
            }
        }

        // Selected Image Preview Thumbnail
        AnimatedVisibility(
            visible = selectedBitmap != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            selectedBitmap?.let { bmp ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .size(72.dp)
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Attached Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = {
                            selectedBitmap = null
                            selectedImageBytes = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove image",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Bottom Chat Input Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Insert Image Button (Photo Picker)
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Insert Image",
                        tint = if (selectedBitmap != null) Color(0xFF68D391) else MaterialTheme.colorScheme.primary
                    )
                }

                // Text Input Field
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask or instruct local AI...", fontSize = 14.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank() && !isGenerating) {
                                executePrompt(
                                    prompt = inputText,
                                    image = selectedImageBytes,
                                    imageBmp = selectedBitmap,
                                    liteRtEngine = liteRtEngine,
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
                    )
                )

                // Send or Stop Button
                if (isGenerating) {
                    IconButton(
                        onClick = {
                            currentJob?.cancel()
                            isGenerating = false
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0xFFE53E3E), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = Color.White
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                executePrompt(
                                    prompt = inputText,
                                    image = selectedImageBytes,
                                    imageBmp = selectedBitmap,
                                    liteRtEngine = liteRtEngine,
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
                        },
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

private fun executePrompt(
    prompt: String,
    image: ByteArray?,
    imageBmp: Bitmap?,
    liteRtEngine: LiteRtLmEngine,
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
            ).collect { chunk ->
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

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Optional attached image preview in user message
        if (message.imageBitmap != null) {
            Image(
                bitmap = message.imageBitmap.asImageBitmap(),
                contentDescription = "User Attachment",
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .size(width = 160.dp, height = 120.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }

        // Message Bubble
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (message.text.isEmpty() && message.isStreaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Initializing native tensor forward-pass...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // Speed & Performance Metrics Badge
                if (!isUser && message.metrics != null) {
                    val m = message.metrics
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Speed",
                                tint = Color(0xFFA27B5C),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = buildString {
                                    append(String.format("%.1f", m.tokensPerSecond))
                                    append(" tok/s")
                                    if (m.timeToFirstTokenMs > 0) {
                                        append(" • TTFT: ")
                                        append(m.timeToFirstTokenMs)
                                        append("ms")
                                    }
                                    if (m.totalDurationMs > 0) {
                                        append(" • ")
                                        append(String.format("%.1fs", m.totalDurationMs / 1000f))
                                    }
                                    if (m.activeBackend.isNotEmpty()) {
                                        append(" • ")
                                        append(m.activeBackend.substringBefore(" "))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFA27B5C)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
