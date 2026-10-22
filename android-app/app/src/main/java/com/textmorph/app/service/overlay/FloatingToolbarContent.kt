package com.textmorph.app.service.overlay

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.ui.camera.CameraCaptureActivity
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class FloatingButtonState {
    IDLE,
    GENERATING,
    REVERT_WINDOW
}

/**
 * Modern TextMorph Companion:
 * - Collapsed: Minimalist 36dp magnetic Edge Pill with Forest Clay styling.
 * - Expanded: Ultra-low footprint 44dp horizontal Companion Ribbon with smart reply chips & transform actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingToolbarContent(
    getLiveInputText: () -> String,
    getLatestIncomingMessage: () -> String?,
    getConversationTranscript: () -> String,
    prompts: List<PromptEntity>,
    isVisionCapable: () -> Boolean,
    onInsertText: (text: String, isIntermediate: Boolean) -> Unit,
    onSnapshotOriginalText: (original: String) -> Unit,
    onRevertText: () -> Unit,
    hasUndo: () -> Boolean,
    isPlacedOnRight: () -> Boolean,
    isDragging: Boolean = false,
    onRegisterSingleTapHandler: (((() -> Unit)) -> Unit)? = null,
    onRegisterCollapseHandler: (((() -> Unit)) -> Unit)? = null,
    onExpandedStateChanged: (isExpanded: Boolean) -> Unit,
    onBusyStateChanged: (isBusy: Boolean) -> Unit,
    onTakeScreenshotContext: (((ByteArray) -> Unit) -> Unit)? = null,
    autoSuggestReplies: Boolean = true,
    onToggleAutoSuggestReplies: (Boolean) -> Unit,
    isConversationContext: () -> Boolean = { false },
    getScreenVisibleText: () -> String = { "" },
    onQuickEditSave: (PromptEntity) -> Unit = {},
    onQuickEditDelete: (String) -> Unit = {},
    onEditorFocusMode: (Boolean) -> Unit = {},
    compactChipsMode: Boolean = false,
    onToggleCompactMode: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    engineProvider: () -> LlmEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val TAG = "FloatingCompanion"

    var isExpanded by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(FloatingButtonState.IDLE) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Quick Prompt Inline Editor state & Compact Chips mode
    var editingPromptInline by remember { mutableStateOf<PromptEntity?>(null) }
    var isCompactMode by remember(compactChipsMode) { mutableStateOf(compactChipsMode) }

    // Smart reply state
    var smartReplies by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingSmartReplies by remember { mutableStateOf(false) }
    var lastExtractedIncoming by remember { mutableStateOf<String?>(null) }

    // Countdown Animation for 5s REVERT_WINDOW state
    val revertCountdown = remember { Animatable(1f) }

    LaunchedEffect(state) {
        val isBusy = state == FloatingButtonState.GENERATING || state == FloatingButtonState.REVERT_WINDOW
        onBusyStateChanged(isBusy)

        if (state == FloatingButtonState.REVERT_WINDOW) {
            revertCountdown.snapTo(1f)
            revertCountdown.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 5000, easing = LinearEasing)
            )
            if (state == FloatingButtonState.REVERT_WINDOW) {
                state = FloatingButtonState.IDLE
            }
        }
    }

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        if (!expanded && editingPromptInline != null) {
            editingPromptInline = null
            onEditorFocusMode(false)
        }
        onExpandedStateChanged(expanded)
    }

    // Connect handlers from WindowManager synchronously on every recomposition commit
    SideEffect {
        onRegisterSingleTapHandler?.invoke {
            AppLogger.d(TAG, "singleTapHandler executed in Compose. state=$state, isExpanded=$isExpanded")
            when (state) {
                FloatingButtonState.IDLE -> {
                    setExpanded(!isExpanded)
                }
                FloatingButtonState.GENERATING -> {
                    activeJob?.cancel()
                    activeJob = null
                    state = FloatingButtonState.REVERT_WINDOW
                    Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                }
                FloatingButtonState.REVERT_WINDOW -> {
                    onRevertText()
                    state = FloatingButtonState.IDLE
                    Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        onRegisterCollapseHandler?.invoke {
            AppLogger.d(TAG, "collapseHandler executed in Compose. isExpanded=$isExpanded")
            if (isExpanded) {
                setExpanded(false)
            }
        }
    }

    // Smart reply automatic generator with conversation detection & visible screen context
    fun fetchSmartReplies(contextText: String) {
        if (isLoadingSmartReplies) return
        isLoadingSmartReplies = true
        scope.launch {
            try {
                AppLogger.d(TAG, "Generating smart replies for context: \"$contextText\"")
                val engine = engineProvider()
                val isChat = isConversationContext()
                val prompt = if (isChat) {
                    "Based on this received chat message, generate exactly 3 short, natural, human reply options (under 8 words each). Separate the 3 options with ' ||| '. Output ONLY the 3 options separated by ' ||| ', with zero commentary or quotes.\nMessage: \"$contextText\""
                } else {
                    "Based on this screen context, generate 3 short, relevant quick action or response options (under 8 words each). Separate the 3 options with ' ||| '. Output ONLY the 3 options separated by ' ||| '.\nScreen Context: \"${contextText.take(500)}\""
                }

                val sb = StringBuilder()
                engine.generateStream(prompt, "You are a concise smart reply assistant.").collect { token ->
                    sb.append(token)
                }
                val raw = sb.toString().trim()
                val parsed = raw.split("|||")
                    .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                    .filter { it.isNotBlank() && !it.startsWith("Error") && it.length < 60 }
                    .take(3)

                if (parsed.isNotEmpty()) {
                    smartReplies = parsed
                    lastExtractedIncoming = contextText
                    AppLogger.i(TAG, "Generated ${parsed.size} smart reply chips successfully")
                } else {
                    smartReplies = listOf("Sounds good! 👍", "Let's do that! 🚀", "Can't right now, will follow up 🙏")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed generating smart replies: ${e.message}", e)
                smartReplies = listOf("Sounds good! 👍", "Let's do that! 🚀", "Can't right now, will follow up 🙏")
            } finally {
                isLoadingSmartReplies = false
            }
        }
    }

    // Auto-fetch replies when ribbon opens in a chat or screen context
    LaunchedEffect(isExpanded, autoSuggestReplies) {
        if (isExpanded && autoSuggestReplies && state == FloatingButtonState.IDLE) {
            val liveText = getLiveInputText()
            val incoming = getLatestIncomingMessage()
            val screenText = getScreenVisibleText()
            val isChat = isConversationContext()
            if (liveText.isBlank() && ((isChat && !incoming.isNullOrBlank()) || screenText.isNotBlank())) {
                val candidate = if (isChat && !incoming.isNullOrBlank()) incoming else screenText.take(200)
                if (candidate != lastExtractedIncoming || smartReplies.isEmpty()) {
                    fetchSmartReplies(candidate)
                }
            }
        }
    }

    // Core Generation Pipelines with Non-Destructive Token Streaming
    fun startGeneration(systemInstruction: String, directExecute: Boolean = false, contextInput: String? = null) {
        val rawInput = contextInput ?: getLiveInputText()
        if (rawInput.isBlank()) {
            Toast.makeText(context, "Please type or place cursor in a text field first.", Toast.LENGTH_SHORT).show()
            return
        }

        // Snapshot original text upfront before streaming begins to ensure safe revert
        onSnapshotOriginalText(rawInput)

        state = FloatingButtonState.GENERATING
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val engine = engineProvider()
                val effectiveInstruction = if (directExecute) {
                    "You are an on-device writing assistant. Execute the request or instruction provided in the user's text. Return ONLY the final output with zero conversational commentary, zero intros/outros, zero quotes, and zero reasoning traces."
                } else {
                    "$systemInstruction. Strict rule: Output ONLY the transformed result directly. Do not include commentary, explanations, or thinking blocks."
                }

                val sb = StringBuilder()
                var lastStreamFlushTime = 0L

                engine.generateStream(rawInput, effectiveInstruction).collect { token ->
                    sb.append(token)
                    val now = System.currentTimeMillis()
                    // Flush intermediate chunks with isIntermediate = true to protect undo history
                    if (now - lastStreamFlushTime >= 80L || token.contains("\n") || token.contains(".")) {
                        lastStreamFlushTime = now
                        val intermediate = sb.toString()
                        if (intermediate.isNotEmpty()) {
                            onInsertText(intermediate, true)
                        }
                    }
                }

                val finalOutput = sb.toString().trim()
                if (finalOutput.startsWith("Error:") || finalOutput.startsWith("Error (")) {
                    AppLogger.w(TAG, "Generation returned error: $finalOutput")
                    Toast.makeText(context, finalOutput, Toast.LENGTH_LONG).show()
                    state = FloatingButtonState.IDLE
                    return@launch
                }

                if (finalOutput.isNotEmpty()) {
                    onInsertText(finalOutput, false)
                    state = FloatingButtonState.REVERT_WINDOW
                } else {
                    state = FloatingButtonState.IDLE
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Inference error during generation: ${e.message}", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                state = FloatingButtonState.IDLE
            }
        }
    }

    fun startSmartReplyGeneration(instruction: String) {
        val transcript = getConversationTranscript()
        val latest = getLatestIncomingMessage() ?: ""
        val screenContext = getScreenVisibleText()
        val isChat = isConversationContext()
        val contextPrompt = buildString {
            if (isChat && transcript.isNotBlank()) {
                append("Conversation Context:\n$transcript\n\n")
            } else if (latest.isNotBlank()) {
                append("Received Message: $latest\n\n")
            }
            if (screenContext.isNotBlank()) {
                append("Screen Visible Text:\n${screenContext.take(1000)}\n\n")
            }
            append("Instruction: $instruction")
        }
        startGeneration(
            systemInstruction = "You are a smart context-aware assistant. Reply or generate text based on the conversation and screen context according to the instruction. Output ONLY the final text.",
            directExecute = false,
            contextInput = contextPrompt
        )
    }

    fun startVisionGenerationWithBytes(instruction: String, imageBytes: ByteArray) {
        val liveText = getLiveInputText().ifBlank { "Screen Context" }
        onSnapshotOriginalText(liveText)

        state = FloatingButtonState.GENERATING
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val engine = engineProvider()
                val sb = StringBuilder()
                var lastStreamFlushTime = 0L

                engine.generateStream(liveText, instruction, imageBytes).collect { token ->
                    sb.append(token)
                    val now = System.currentTimeMillis()
                    if (now - lastStreamFlushTime >= 80L || token.contains("\n") || token.contains(".")) {
                        lastStreamFlushTime = now
                        val intermediate = sb.toString()
                        if (intermediate.isNotEmpty()) {
                            onInsertText(intermediate, true)
                        }
                    }
                }

                val finalOutput = sb.toString().trim()
                if (finalOutput.isNotEmpty()) {
                    onInsertText(finalOutput, false)
                    state = FloatingButtonState.REVERT_WINDOW
                } else {
                    state = FloatingButtonState.IDLE
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Vision inference error: ${e.message}", e)
                Toast.makeText(context, "Vision error: ${e.message}", Toast.LENGTH_SHORT).show()
                state = FloatingButtonState.IDLE
            }
        }
    }

    fun startVisionGeneration(instruction: String) {
        if (!isVisionCapable()) {
            Toast.makeText(context, "Active model is text-only (vision not supported).", Toast.LENGTH_LONG).show()
            return
        }
        if (onTakeScreenshotContext == null) {
            Toast.makeText(context, "Screenshot capture not available.", Toast.LENGTH_SHORT).show()
            return
        }
        onTakeScreenshotContext.invoke { imageBytes ->
            startVisionGenerationWithBytes(instruction, imageBytes)
        }
    }

    fun startOcrGeneration(instruction: String) {
        if (!isVisionCapable()) {
            Toast.makeText(context, "Active model is text-only (OCR vision not supported).", Toast.LENGTH_LONG).show()
            return
        }
        if (onTakeScreenshotContext == null) {
            Toast.makeText(context, "Screen capture not available for OCR.", Toast.LENGTH_SHORT).show()
            return
        }
        onTakeScreenshotContext.invoke { imageBytes ->
            val ocrInstruction = "$instruction. Transcribe all readable text from this screen clearly and format it cleanly."
            startVisionGenerationWithBytes(ocrInstruction, imageBytes)
        }
    }

    fun executePrompt(prompt: PromptEntity) {
        when (prompt.contextAction) {
            ContextAction.SCREENSHOT -> startVisionGeneration(prompt.systemInstruction)
            ContextAction.CAMERA -> {
                if (!isVisionCapable()) {
                    Toast.makeText(context, "Active model is text-only (camera vision not supported).", Toast.LENGTH_LONG).show()
                    return
                }
                Toast.makeText(context, "Opening camera...", Toast.LENGTH_SHORT).show()
                CameraCaptureActivity.launch(context) { bytes ->
                    if (bytes != null) {
                        startVisionGenerationWithBytes(prompt.systemInstruction, bytes)
                    } else {
                        AppLogger.d(TAG, "Camera capture returned null bytes.")
                    }
                }
            }
            ContextAction.OCR -> startOcrGeneration(prompt.systemInstruction)
            ContextAction.ACCESSIBILITY_TEXT -> {
                val liveText = getLiveInputText()
                if (liveText.isNotBlank()) {
                    startGeneration(prompt.systemInstruction, directExecute = false)
                } else {
                    startSmartReplyGeneration(prompt.systemInstruction)
                }
            }
            ContextAction.CLIPBOARD -> {
                val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clipData = clipboardManager?.primaryClip
                val clipText = if (clipData != null && clipData.itemCount > 0) {
                    clipData.getItemAt(0)?.text?.toString()
                } else null

                if (clipText.isNullOrBlank()) {
                    Toast.makeText(context, "Clipboard is empty. Copy text first.", Toast.LENGTH_SHORT).show()
                    return
                }

                val liveText = getLiveInputText()
                val combinedInput = if (liveText.isNotBlank()) {
                    "Clipboard Content:\n$clipText\n\nUser Input:\n$liveText"
                } else {
                    "Clipboard Content:\n$clipText"
                }

                startGeneration(prompt.systemInstruction, directExecute = false, contextInput = combinedInput)
            }
            ContextAction.NONE -> startGeneration(prompt.systemInstruction, directExecute = false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // UI RENDERING: COLLAPSED EDGE PILL vs EXPANDED COMPANION RIBBON
    // ─────────────────────────────────────────────────────────────────────────────

    if (!isExpanded) {
        // COLLAPSED EDGE PILL (36dp)
        val dragScale by animateFloatAsState(
            targetValue = if (isDragging) 1.15f else 1.0f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            label = "dragScale"
        )

        Box(
            modifier = Modifier
                .size(36.dp)
                .scale(dragScale),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = when (state) {
                    FloatingButtonState.GENERATING -> Color(0xFF2C3930)
                    FloatingButtonState.REVERT_WINDOW -> Color(0xFF3F4F44)
                    else -> if (isDragging) Color(0xFF3F4F44) else Color(0xFF2C3930)
                },
                shadowElevation = if (isDragging) 12.dp else 6.dp,
                modifier = Modifier
                    .size(36.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        AppLogger.d(TAG, "Collapsed companion pill clicked directly in Compose: state=$state")
                        when (state) {
                            FloatingButtonState.IDLE -> setExpanded(!isExpanded)
                            FloatingButtonState.GENERATING -> {
                                activeJob?.cancel()
                                activeJob = null
                                state = FloatingButtonState.REVERT_WINDOW
                                Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                            }
                            FloatingButtonState.REVERT_WINDOW -> {
                                onRevertText()
                                state = FloatingButtonState.IDLE
                                Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            if (isDragging) listOf(Color(0xFFFFF8E7), Color(0xFFA27B5C))
                            else listOf(Color(0xFFA27B5C), Color(0xFFC59B76))
                        ),
                        shape = CircleShape
                    )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (state) {
                        FloatingButtonState.GENERATING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFFFD54F)
                            )
                        }
                        FloatingButtonState.REVERT_WINDOW -> {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo Text",
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "TextMorph Companion",
                                tint = Color(0xFFDCD7C9),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Countdown ring when in Revert Window
            if (state == FloatingButtonState.REVERT_WINDOW) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = Color(0xFFFFD54F),
                        startAngle = -90f,
                        sweepAngle = 360f * revertCountdown.value,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    } else {
        if (editingPromptInline != null) {
            // Inline Quick Edit Prompt Card taking up the upper half of the screen
            val currentPrompt = editingPromptInline!!
            var editTitle by remember(currentPrompt.id) { mutableStateOf(currentPrompt.title) }
            var editInstruction by remember(currentPrompt.id) { mutableStateOf(currentPrompt.systemInstruction) }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xF22C3930)),
                border = BorderStroke(1.5.dp, Color(0xFFA27B5C)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✏️ Quick Edit Prompt",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFF8E7)
                        )
                        IconButton(
                            onClick = {
                                editingPromptInline = null
                                onEditorFocusMode(false)
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Edit",
                                tint = Color(0xFFDCD7C9),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Prompt Title", color = Color(0xFFDCD7C9)) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color(0xFFFFF8E7), fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = editInstruction,
                        onValueChange = { editInstruction = it },
                        label = { Text("System Instruction", color = Color(0xFFDCD7C9)) },
                        maxLines = 4,
                        textStyle = TextStyle(color = Color(0xFFFFF8E7), fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                onQuickEditDelete(currentPrompt.id)
                                editingPromptInline = null
                                onEditorFocusMode(false)
                                Toast.makeText(context, "Prompt deleted", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE57373)),
                            border = BorderStroke(1.dp, Color(0x66E57373)),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Delete", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                if (editTitle.isNotBlank() && editInstruction.isNotBlank()) {
                                    val updated = currentPrompt.copy(
                                        title = editTitle.trim(),
                                        systemInstruction = editInstruction.trim()
                                    )
                                    onQuickEditSave(updated)
                                    editingPromptInline = null
                                    onEditorFocusMode(false)
                                    Toast.makeText(context, "Prompt updated!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFA27B5C),
                                contentColor = Color(0xFFFFF8E7)
                            )
                        ) {
                            Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // EXPANDED COMPANION RIBBON (44dp single-row dock)
            val liveText = getLiveInputText()
            val latestIncoming = getLatestIncomingMessage()
            val isChat = isConversationContext()
            val screenText = getScreenVisibleText()
            val isChatContext = liveText.isBlank() && ((isChat && !latestIncoming.isNullOrBlank()) || screenText.isNotBlank())

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xF22C3930),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(1.5.dp, Color(0x88A27B5C), RoundedCornerShape(22.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Status / Leading Action Icon
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(Color(0x33A27B5C), CircleShape)
                            .clickable {
                                if (state == FloatingButtonState.GENERATING) {
                                    activeJob?.cancel()
                                    activeJob = null
                                    state = FloatingButtonState.REVERT_WINDOW
                                    Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                                } else if (state == FloatingButtonState.REVERT_WINDOW) {
                                    onRevertText()
                                    state = FloatingButtonState.IDLE
                                    Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                                } else {
                                    setExpanded(false)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when (state) {
                            FloatingButtonState.GENERATING -> {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = Color(0xFFE57373),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            FloatingButtonState.REVERT_WINDOW -> {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = "Revert",
                                    tint = Color(0xFFFFD54F),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Companion",
                                    tint = Color(0xFFFFF8E7),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Center Scrollable Action Chips
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (state) {
                            FloatingButtonState.REVERT_WINDOW -> {
                                item {
                                    CompanionActionChip(
                                        label = "↩️ Revert Text",
                                        accent = true,
                                        onClick = {
                                            onRevertText()
                                            state = FloatingButtonState.IDLE
                                            Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                                item {
                                    CompanionActionChip(
                                        label = "✓ Keep Output",
                                        accent = false,
                                        onClick = {
                                            state = FloatingButtonState.IDLE
                                        }
                                    )
                                }
                            }
                            FloatingButtonState.GENERATING -> {
                                item {
                                    CompanionActionChip(
                                        label = "⏹ Stop Generating",
                                        accent = true,
                                        onClick = {
                                            activeJob?.cancel()
                                            activeJob = null
                                            state = FloatingButtonState.REVERT_WINDOW
                                            Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                            FloatingButtonState.IDLE -> {
                                // Smart Reply Chips (when in chat context with empty input & auto-replies enabled)
                                if (isChatContext && autoSuggestReplies) {
                                    if (isLoadingSmartReplies) {
                                        item {
                                            CompanionActionChip(
                                                label = "✨ Generating replies...",
                                                accent = true,
                                                onClick = {}
                                            )
                                        }
                                    } else {
                                        items(smartReplies) { reply ->
                                            CompanionActionChip(
                                                label = reply,
                                                accent = true,
                                                onClick = {
                                                    val original = getLiveInputText()
                                                    onSnapshotOriginalText(original)
                                                    onInsertText(reply, false)
                                                    state = FloatingButtonState.REVERT_WINDOW
                                                    setExpanded(false)
                                                }
                                            )
                                        }
                                    }

                                    // Quick Smart Style Archetypes
                                    item {
                                        CompanionActionChip(
                                            label = "👍 Agree",
                                            accent = false,
                                            onClick = {
                                                startSmartReplyGeneration("Generate a polite, enthusiastic affirmative response to the incoming message.")
                                            }
                                        )
                                    }
                                    item {
                                        CompanionActionChip(
                                            label = "🙅 Decline",
                                            accent = false,
                                            onClick = {
                                                startSmartReplyGeneration("Generate a polite and thoughtful decline response to the incoming message.")
                                            }
                                        )
                                    }
                                    item {
                                        CompanionActionChip(
                                            label = "🤔 Inquire",
                                            accent = false,
                                            onClick = {
                                                startSmartReplyGeneration("Generate a friendly follow-up question asking for more details or clarification.")
                                            }
                                        )
                                    }
                                    if (smartReplies.isNotEmpty()) {
                                        item {
                                            IconButton(
                                                onClick = {
                                                    val target = if (isChat && !latestIncoming.isNullOrBlank()) latestIncoming else screenText.take(200)
                                                    fetchSmartReplies(target)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "Refresh",
                                                    tint = Color(0xFFDCD7C9),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // AI Smart Reply Toggle Chip inside the bar
                                item {
                                    CompanionActionChip(
                                        label = if (autoSuggestReplies) "✨ AI ON" else "⚪ AI OFF",
                                        accent = autoSuggestReplies,
                                        onClick = {
                                            val next = !autoSuggestReplies
                                            onToggleAutoSuggestReplies(next)
                                            Toast.makeText(
                                                context,
                                                if (next) "Smart Replies: ON" else "Smart Replies: OFF",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }

                                // Standard Transform Prompts
                                items(prompts) { prompt ->
                                    val requiresInput = prompt.contextAction == ContextAction.NONE ||
                                            (prompt.category == PromptCategory.TEXT_TRANSFORM && prompt.contextAction != ContextAction.CLIPBOARD)
                                    val isGrayed = requiresInput && liveText.isBlank()

                                    CompanionActionChip(
                                        label = if (isCompactMode) prompt.iconEmoji else "${prompt.iconEmoji} ${prompt.title}",
                                        accent = false,
                                        grayedOut = isGrayed,
                                        onClick = {
                                            if (isGrayed) {
                                                Toast.makeText(
                                                    context,
                                                    "No text entered or selected. Please type or place cursor in a text field first.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                executePrompt(prompt)
                                            }
                                        },
                                        onLongClick = {
                                            editingPromptInline = prompt
                                            onEditorFocusMode(true)
                                        }
                                    )
                                }

                                // Compact / Expand Toggle Chip at the end of prompt options
                                item {
                                    CompanionActionChip(
                                        label = if (isCompactMode) "📖 Expand" else "🗜️ Compact",
                                        accent = false,
                                        onClick = {
                                            val nextMode = !isCompactMode
                                            isCompactMode = nextMode
                                            onToggleCompactMode(nextMode)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Right Controls: Permanent Undo, Quick Execute, and Collapse ('x') Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 1. Fixed Permanent Undo Button
                        val canUndo = hasUndo() || state == FloatingButtonState.REVERT_WINDOW
                        IconButton(
                            onClick = {
                                onRevertText()
                                state = FloatingButtonState.IDLE
                                Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                            },
                            enabled = canUndo,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Permanent Undo",
                                tint = if (canUndo) Color(0xFFFFD54F) else Color(0x44DCD7C9),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 2. Quick Execute / Run Button (to the left of 'x')
                        val hasInput = liveText.isNotBlank()
                        IconButton(
                            onClick = {
                                if (!hasInput) {
                                    Toast.makeText(context, "No text entered or selected to execute.", Toast.LENGTH_SHORT).show()
                                } else {
                                    startGeneration("", directExecute = true)
                                }
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .background(
                                    if (hasInput) Color(0x44A27B5C) else Color(0x1A3F4F44),
                                    CircleShape
                                )
                                .border(
                                    1.dp,
                                    if (hasInput) Color(0xFFA27B5C) else Color(0x22DCD7C9),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Quick Execute",
                                tint = if (hasInput) Color(0xFFFFD54F) else Color(0x44DCD7C9),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 3. Collapse ('x') Button
                        IconButton(
                            onClick = {
                                if (editingPromptInline != null) {
                                    editingPromptInline = null
                                    onEditorFocusMode(false)
                                }
                                setExpanded(false)
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Collapse",
                                tint = Color(0xFFDCD7C9),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modern, low-footprint chip for the TextMorph Companion Ribbon with optional long-press support.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompanionActionChip(
    label: String,
    accent: Boolean = false,
    grayedOut: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val backgroundColor = when {
        grayedOut -> Color(0x1A3F4F44)
        accent -> Color(0x44A27B5C)
        else -> Color(0x333F4F44)
    }
    val borderColor = when {
        grayedOut -> Color(0x22DCD7C9)
        accent -> Color(0xFFA27B5C)
        else -> Color(0x66A27B5C)
    }
    val textColor = when {
        grayedOut -> Color(0x44DCD7C9)
        accent -> Color(0xFFFFF8E7)
        else -> Color(0xFFDCD7C9)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (accent && !grayedOut) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
