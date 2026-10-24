package com.textmorph.app.service.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.model.SlashCommandEntity
import com.textmorph.app.data.repository.SlashCommandRepository
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.service.overlay.components.CompanionActionChip
import com.textmorph.app.service.overlay.components.CompanionSquareIconButton
import com.textmorph.app.service.overlay.components.FloatingEdgePill
import com.textmorph.app.service.overlay.components.FloatingInlinePromptEditor
import com.textmorph.app.ui.camera.CameraCaptureActivity
import com.textmorph.app.ui.prompts.getVectorIcon
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.EmojiTextArtHelper
import com.textmorph.app.util.KeywordContextResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Visual operational states for the floating companion overlay.
 */
enum class FloatingButtonState {
    IDLE,
    GENERATING,
    REVERT_WINDOW
}

/**
 * Modern TextMorph Companion:
 * - Collapsed: Minimalist 36dp magnetic Edge Pill with Charcoal Slate styling.
 * - Expanded: Ultra-low footprint 44dp horizontal Companion Ribbon with neat square icon buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingToolbarContent(
    getLiveInputText: () -> String,
    getConversationTranscript: () -> String = { "" },
    prompts: List<PromptEntity>,
    isVisionCapable: () -> Boolean,
    onInsertText: (text: String, isIntermediate: Boolean) -> Unit,
    onSnapshotOriginalText: (original: String) -> Unit,
    onRevertText: () -> Unit,
    hasUndo: () -> Boolean,
    getRecentClipboardText: () -> String? = { null },
    onQuickPaste: () -> Unit = {},
    isPlacedOnRight: () -> Boolean,
    isDragging: Boolean = false,
    onRegisterSingleTapHandler: (((() -> Unit)) -> Unit)? = null,
    onRegisterCollapseHandler: (((() -> Unit)) -> Unit)? = null,
    onRegisterUserTypedHandler: (((String) -> Unit) -> Unit)? = null,
    onRegisterUserNavigatedBackHandler: (((() -> Unit)) -> Unit)? = null,
    onExpandedStateChanged: (isExpanded: Boolean) -> Unit,
    onBusyStateChanged: (isBusy: Boolean) -> Unit,
    onTakeScreenshotContext: (((ByteArray) -> Unit) -> Unit)? = null,
    isConversationContext: () -> Boolean = { false },
    getScreenVisibleText: () -> String = { "" },
    onQuickEditSave: (PromptEntity) -> Unit = {},
    onQuickEditDelete: (String) -> Unit = {},
    onEditorFocusMode: (Boolean) -> Unit = {},
    compactChipsMode: Boolean = false,
    onToggleCompactMode: (Boolean) -> Unit = {},
    quickFixEnabled: Boolean = false,
    onToggleQuickFix: (Boolean) -> Unit = {},
    slashCommandRepository: SlashCommandRepository = SlashCommandRepository.getInstance(LocalContext.current),
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
    val isCompactMode = compactChipsMode

    // Reactive Quick Fix (Auto Fix) state
    var localQuickFixEnabled by remember(quickFixEnabled) { mutableStateOf(quickFixEnabled) }
    var isFixingInProgress by remember { mutableStateOf(false) }
    var pendingFixUndo by remember { mutableStateOf<String?>(null) }
    var quickFixJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(state) {
        val isBusy = state == FloatingButtonState.GENERATING
        onBusyStateChanged(isBusy)
    }

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        if (!expanded) {
            if (editingPromptInline != null) {
                editingPromptInline = null
                onEditorFocusMode(false)
            }
            if (state == FloatingButtonState.REVERT_WINDOW) {
                state = FloatingButtonState.IDLE
            }
            quickFixJob?.cancel()
            isFixingInProgress = false
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
            AppLogger.d(TAG, "collapseHandler executed in Compose. isExpanded=$isExpanded, editing=${editingPromptInline != null}")
            if (editingPromptInline != null) {
                return@invoke
            }
            if (isExpanded) {
                setExpanded(false)
            }
        }

        onRegisterUserTypedHandler?.invoke { newText ->
            if (state == FloatingButtonState.REVERT_WINDOW) {
                state = FloatingButtonState.IDLE
            }

            // If user continues typing, any pending auto-fix undo prompt is treated as accepted
            if (pendingFixUndo != null) {
                pendingFixUndo = null
            }

            // 1. Check for :(emotion): trigger
            val emojiMatch = EmojiTextArtHelper.findEmojiMatch(newText)
            if (emojiMatch != null) {
                val emotion = emojiMatch.groupValues[1]
                val fullMatch = emojiMatch.value
                scope.launch {
                    val emoji = EmojiTextArtHelper.resolveEmoji(emotion, engineProvider())
                    val current = getLiveInputText()
                    if (current.contains(fullMatch)) {
                        val replaced = current.replace(fullMatch, "$emoji ")
                        onSnapshotOriginalText(current)
                        onInsertText(replaced, false)
                        Toast.makeText(context, "Emoji: $emoji", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 2. Check for T:(emotion): trigger
            val textArtMatch = EmojiTextArtHelper.findTextArtMatch(newText)
            if (textArtMatch != null) {
                val artRequest = textArtMatch.groupValues[1]
                val fullMatch = textArtMatch.value
                scope.launch {
                    val textArt = EmojiTextArtHelper.resolveTextArt(artRequest, engineProvider())
                    val current = getLiveInputText()
                    if (current.contains(fullMatch)) {
                        val replaced = current.replace(fullMatch, "$textArt ")
                        onSnapshotOriginalText(current)
                        onInsertText(replaced, false)
                        Toast.makeText(context, "Text Art applied", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 3. Quick Fix debounced auto-replacement
            if (localQuickFixEnabled && state == FloatingButtonState.IDLE) {
                quickFixJob?.cancel()
                val trimmed = newText.trim()
                val wordCount = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                if (trimmed.length in 3..140 && wordCount <= 25) {
                    quickFixJob = scope.launch {
                        kotlinx.coroutines.delay(550L)
                        if (!isActive) return@launch
                        val current = getLiveInputText().trim()
                        if (current != trimmed || current.length < 3 || current.length > 140) return@launch
                        try {
                            isFixingInProgress = true
                            val engine = engineProvider()
                            val fixInstruction = "take this text and Fix all grammar, punctuation, and spelling errors while preserving the original Meaning and return only that fixed message without any extra comments on it"
                            val sb = StringBuilder()
                            engine.generateStream(current, "$fixInstruction. Strict rule: Output ONLY the transformed result directly. Do not include commentary, explanations, or thinking blocks.").collect { token ->
                                sb.append(token)
                            }
                            val fixed = sb.toString().trim()
                            if (fixed.isNotBlank() && !fixed.startsWith("Error") && !fixed.equals(current, ignoreCase = false)) {
                                if (getLiveInputText().trim() == current) {
                                    onSnapshotOriginalText(current)
                                    onInsertText(fixed, false)
                                    pendingFixUndo = current
                                    AppLogger.d(TAG, "Quick fix automatically replaced text with: '$fixed'")
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Quick fix background error: ${e.message}")
                        } finally {
                            isFixingInProgress = false
                        }
                    }
                }
            } else {
                quickFixJob?.cancel()
                isFixingInProgress = false
            }
        }

        onRegisterUserNavigatedBackHandler?.invoke {
            if (state == FloatingButtonState.REVERT_WINDOW) {
                state = FloatingButtonState.IDLE
            }
            quickFixJob?.cancel()
            isFixingInProgress = false
            pendingFixUndo = null
        }
    }

    fun startVisionGenerationWithBytes(instruction: String, imageBytes: ByteArray, overrideInput: String? = null) {
        val rawInput = getLiveInputText()
        val textToFeed = overrideInput ?: rawInput.ifBlank { "Analyze this image" }
        onSnapshotOriginalText(rawInput)

        state = FloatingButtonState.GENERATING
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val engine = engineProvider()
                val sb = StringBuilder()
                var lastStreamFlushTime = 0L

                engine.generateStream(textToFeed, instruction, imageBytes).collect { token ->
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
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("TextMorph Response", finalOutput))
                        Toast.makeText(context, "Response generated & copied to clipboard", Toast.LENGTH_SHORT).show()
                    } catch (clipEx: Exception) {
                        AppLogger.w(TAG, "Failed writing vision response to clipboard: ${clipEx.message}")
                    }
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

    fun executeGenerationInternal(systemInstruction: String, directExecute: Boolean, input: String) {
        onSnapshotOriginalText(input)
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

                engine.generateStream(input, effectiveInstruction).collect { token ->
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
                if (finalOutput.startsWith("Error:") || finalOutput.startsWith("Error (")) {
                    AppLogger.w(TAG, "Generation returned error: $finalOutput")
                    Toast.makeText(context, finalOutput, Toast.LENGTH_LONG).show()
                    state = FloatingButtonState.IDLE
                    return@launch
                }

                if (finalOutput.isNotEmpty()) {
                    onInsertText(finalOutput, false)
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("TextMorph Output", finalOutput))
                    } catch (e: Exception) {
                        AppLogger.d(TAG, "Secondary clipboard cache: ${e.message}")
                    }
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

    // Core Generation Pipelines with Dynamic Keyword Interpolation
    fun startGeneration(systemInstruction: String, directExecute: Boolean = false, contextInput: String? = null) {
        val rawInput = contextInput ?: getLiveInputText()
        if (rawInput.isBlank()) {
            Toast.makeText(context, "Please type or place cursor in a text field first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (KeywordContextResolver.hasKeywords(rawInput)) {
            scope.launch {
                Toast.makeText(context, "Resolving keywords...", Toast.LENGTH_SHORT).show()
                val resolved = KeywordContextResolver.resolve(context, rawInput) {
                    suspendCancellableCoroutine { continuation ->
                        if (onTakeScreenshotContext != null) {
                            onTakeScreenshotContext.invoke { bytes ->
                                if (continuation.isActive) continuation.resume(bytes)
                            }
                        } else {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                if (resolved.imageBytes != null) {
                    val effectiveInstruction = if (directExecute) {
                        "You are an on-device writing assistant. Execute the request or instruction provided in the user's text and image. Return ONLY the final output with zero conversational commentary."
                    } else {
                        systemInstruction
                    }
                    startVisionGenerationWithBytes(effectiveInstruction, resolved.imageBytes, overrideInput = resolved.processedText)
                } else {
                    executeGenerationInternal(systemInstruction, directExecute, resolved.processedText)
                }
            }
            return
        }

        executeGenerationInternal(systemInstruction, directExecute, rawInput)
    }

    fun startVisionGeneration(instruction: String, overrideInput: String? = null) {
        if (!isVisionCapable()) {
            Toast.makeText(context, "Active model is text-only (vision not supported).", Toast.LENGTH_LONG).show()
            return
        }
        if (onTakeScreenshotContext == null) {
            Toast.makeText(context, "Screenshot capture not available.", Toast.LENGTH_SHORT).show()
            return
        }
        onTakeScreenshotContext.invoke { imageBytes ->
            startVisionGenerationWithBytes(instruction, imageBytes, overrideInput)
        }
    }

    fun startOcrGeneration(instruction: String, overrideInput: String? = null) {
        if (!isVisionCapable()) {
            Toast.makeText(context, "Active model is text-only (OCR vision not supported).", Toast.LENGTH_LONG).show()
            return
        }
        if (onTakeScreenshotContext == null) {
            Toast.makeText(context, "Screenshot capture not available.", Toast.LENGTH_SHORT).show()
            return
        }
        onTakeScreenshotContext.invoke { imageBytes ->
            val ocrInstruction = "$instruction. Transcribe all readable text from this screen clearly and format it cleanly."
            startVisionGenerationWithBytes(ocrInstruction, imageBytes, overrideInput)
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
                        Toast.makeText(context, "Analyzing photo...", Toast.LENGTH_SHORT).show()
                        startVisionGenerationWithBytes(prompt.systemInstruction, bytes)
                    } else {
                        AppLogger.d(TAG, "Camera capture returned null bytes.")
                    }
                }
            }
            ContextAction.OCR -> startOcrGeneration(prompt.systemInstruction)
            ContextAction.ACCESSIBILITY_TEXT -> {
                startGeneration(prompt.systemInstruction, directExecute = false)
            }
            ContextAction.CLIPBOARD -> {
                scope.launch {
                    val clipData = KeywordContextResolver.fetchClipboardData(context)
                    if (clipData.imageBytes != null) {
                        startVisionGenerationWithBytes(prompt.systemInstruction, clipData.imageBytes)
                    } else if (!clipData.text.isNullOrBlank()) {
                        val liveText = getLiveInputText()
                        val combinedInput = if (liveText.isNotBlank()) {
                            "Clipboard Content:\n${clipData.text}\n\nUser Input:\n$liveText"
                        } else {
                            "Clipboard Content:\n${clipData.text}"
                        }
                        startGeneration(prompt.systemInstruction, directExecute = false, contextInput = combinedInput)
                    } else {
                        Toast.makeText(context, "Clipboard is empty. Copy text or image first.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ContextAction.NONE -> startGeneration(prompt.systemInstruction, directExecute = false)
        }
    }

    fun executeSlashCommand(command: SlashCommandEntity, cleanedInput: String) {
        AppLogger.i(TAG, "Executing slash command /${command.cleanTrigger} (${command.contextAction}) for input: '$cleanedInput'")
        Toast.makeText(context, "Running /${command.cleanTrigger}: ${command.title}", Toast.LENGTH_SHORT).show()

        if (KeywordContextResolver.hasKeywords(cleanedInput)) {
            scope.launch {
                val resolved = KeywordContextResolver.resolve(context, cleanedInput) {
                    suspendCancellableCoroutine { continuation ->
                        if (onTakeScreenshotContext != null) {
                            onTakeScreenshotContext.invoke { bytes ->
                                if (continuation.isActive) continuation.resume(bytes)
                            }
                        } else {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                if (resolved.imageBytes != null) {
                    startVisionGenerationWithBytes(command.systemInstruction, resolved.imageBytes, overrideInput = resolved.processedText.ifBlank { "Analyze this image" })
                } else {
                    startGeneration(command.systemInstruction, directExecute = false, contextInput = resolved.processedText)
                }
            }
            return
        }

        when (command.contextAction) {
            ContextAction.SCREENSHOT -> {
                startVisionGeneration(command.systemInstruction, overrideInput = cleanedInput.ifBlank { "Analyze this screen" })
            }
            ContextAction.CAMERA -> {
                if (!isVisionCapable()) {
                    Toast.makeText(context, "Active model is text-only (camera vision not supported).", Toast.LENGTH_LONG).show()
                    return
                }
                Toast.makeText(context, "Opening camera...", Toast.LENGTH_SHORT).show()
                CameraCaptureActivity.launch(context) { bytes ->
                    if (bytes != null) {
                        Toast.makeText(context, "Analyzing photo...", Toast.LENGTH_SHORT).show()
                        startVisionGenerationWithBytes(command.systemInstruction, bytes, overrideInput = cleanedInput.ifBlank { "Analyze this image" })
                    } else {
                        AppLogger.d(TAG, "Camera capture returned null bytes.")
                    }
                }
            }
            ContextAction.OCR -> {
                startOcrGeneration(command.systemInstruction, overrideInput = cleanedInput.ifBlank { "Transcribe and analyze this screen" })
            }
            ContextAction.ACCESSIBILITY_TEXT -> {
                startGeneration(command.systemInstruction, directExecute = false, contextInput = cleanedInput)
            }
            ContextAction.CLIPBOARD -> {
                scope.launch {
                    val clipData = KeywordContextResolver.fetchClipboardData(context)
                    if (clipData.imageBytes != null) {
                        startVisionGenerationWithBytes(command.systemInstruction, clipData.imageBytes, overrideInput = cleanedInput.ifBlank { "Analyze clipboard image" })
                    } else if (!clipData.text.isNullOrBlank()) {
                        val combinedInput = if (cleanedInput.isNotBlank()) {
                            "Clipboard Content:\n${clipData.text}\n\nUser Input:\n$cleanedInput"
                        } else {
                            "Clipboard Content:\n${clipData.text}"
                        }
                        startGeneration(command.systemInstruction, directExecute = false, contextInput = combinedInput)
                    } else {
                        Toast.makeText(context, "Clipboard is empty. Copy text or image first.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ContextAction.NONE -> {
                startGeneration(command.systemInstruction, directExecute = false, contextInput = cleanedInput)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // UI RENDERING: COLLAPSED EDGE PILL vs EXPANDED COMPANION RIBBON
    // ─────────────────────────────────────────────────────────────────────────────

    if (!isExpanded) {
        FloatingEdgePill(
            state = state,
            isDragging = isDragging,
            onClick = {
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
        )
    } else {
        if (editingPromptInline != null) {
            FloatingInlinePromptEditor(
                prompt = editingPromptInline!!,
                onSave = { updated ->
                    onQuickEditSave(updated)
                    editingPromptInline = null
                    onEditorFocusMode(false)
                },
                onDelete = { id ->
                    onQuickEditDelete(id)
                    editingPromptInline = null
                    onEditorFocusMode(false)
                },
                onClose = {
                    editingPromptInline = null
                    onEditorFocusMode(false)
                }
            )
        } else {
            // EXPANDED COMPANION RIBBON (44dp single-row dock, Charcoal Slate styled)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xF2111827),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(1.5.dp, Color(0x663B82F6), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Status / Leading Action Square Icon Button
                    CompanionSquareIconButton(
                        icon = when (state) {
                            FloatingButtonState.GENERATING -> Icons.Default.Stop
                            FloatingButtonState.REVERT_WINDOW -> Icons.AutoMirrored.Filled.Undo
                            FloatingButtonState.IDLE -> Icons.Default.AutoAwesome
                        },
                        contentDescription = "Companion Action",
                        tint = when (state) {
                            FloatingButtonState.GENERATING -> Color(0xFFEF4444)
                            FloatingButtonState.REVERT_WINDOW -> Color(0xFFFBBF24)
                            FloatingButtonState.IDLE -> Color(0xFF60A5FA)
                        },
                        backgroundColor = Color(0x331F2937),
                        borderColor = Color(0x55374151),
                        onClick = {
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
                        }
                    )

                    Spacer(modifier = Modifier.width(6.dp))

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
                                        icon = Icons.AutoMirrored.Filled.Undo,
                                        label = if (isCompactMode) null else "Revert",
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
                                        icon = Icons.Default.Check,
                                        label = if (isCompactMode) null else "Keep Output",
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
                                        icon = Icons.Default.Stop,
                                        label = if (isCompactMode) "Stop" else "Generating… Stop",
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
                                // 1. Quick Fix Fixing in Progress Indicator
                                if (isFixingInProgress) {
                                    item {
                                        CompanionActionChip(
                                            icon = Icons.Default.AutoFixHigh,
                                            label = if (isCompactMode) null else "Fixing…",
                                            accent = true,
                                            onClick = {}
                                        )
                                    }
                                }

                                // 2. Auto-Fixed Undo / Accept Prompt
                                if (pendingFixUndo != null) {
                                    item {
                                        CompanionActionChip(
                                            icon = Icons.AutoMirrored.Filled.Undo,
                                            label = if (isCompactMode) "Undo" else "Undo Fix",
                                            accent = true,
                                            onClick = {
                                                val original = pendingFixUndo
                                                if (original != null) {
                                                    onInsertText(original, false)
                                                    pendingFixUndo = null
                                                    Toast.makeText(context, "Quick fix undone.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                                    item {
                                        CompanionActionChip(
                                            icon = Icons.Default.Check,
                                            label = if (isCompactMode) null else "Accept Fix",
                                            accent = false,
                                            onClick = {
                                                pendingFixUndo = null
                                            }
                                        )
                                    }
                                }

                                // 3. Quick Paste Chip (when user recently copied text)
                                val recentClip = getRecentClipboardText()
                                if (!recentClip.isNullOrBlank()) {
                                    item {
                                        val preview = if (recentClip.length > 12) recentClip.take(12) + "…" else recentClip
                                        CompanionActionChip(
                                            icon = Icons.Default.ContentPaste,
                                            label = if (isCompactMode) null else "Paste: \"$preview\"",
                                            accent = true,
                                            onClick = {
                                                onQuickPaste()
                                                Toast.makeText(context, "Pasted recent copy.", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                }

                                // 4. Standard Transform Prompts with native Vector Icons
                                items(prompts) { prompt ->
                                    val vectorIcon = prompt.getVectorIcon()
                                    CompanionActionChip(
                                        icon = vectorIcon,
                                        label = if (isCompactMode) null else prompt.title,
                                        emoji = if (vectorIcon == null) prompt.iconEmoji else null,
                                        accent = false,
                                        onClick = {
                                            executePrompt(prompt)
                                        },
                                        onLongClick = {
                                            editingPromptInline = prompt
                                            onEditorFocusMode(true)
                                        }
                                    )
                                }

                                // 5. Quick Fix Reactive Toggle Chip (placed as LAST item in list)
                                item {
                                    CompanionActionChip(
                                        icon = Icons.Default.AutoFixHigh,
                                        label = if (isCompactMode) {
                                            if (localQuickFixEnabled) "Fix: ON" else "Fix: OFF"
                                        } else {
                                            if (localQuickFixEnabled) "Quick Fix: ON" else "Quick Fix: OFF"
                                        },
                                        accent = localQuickFixEnabled,
                                        onClick = {
                                            localQuickFixEnabled = !localQuickFixEnabled
                                            onToggleQuickFix(localQuickFixEnabled)
                                            Toast.makeText(
                                                context,
                                                if (localQuickFixEnabled) "Quick Fix: ON" else "Quick Fix: OFF",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Right End Action Controls (Undo, Quick Execute, Collapse)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Dedicated Revert/Undo Button
                        val canUndo = hasUndo() || state == FloatingButtonState.REVERT_WINDOW || pendingFixUndo != null
                        CompanionSquareIconButton(
                            icon = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            enabled = canUndo,
                            tint = if (canUndo) Color(0xFF60A5FA) else Color(0x339CA3AF),
                            backgroundColor = Color(0x331F2937),
                            borderColor = if (canUndo) Color(0x663B82F6) else Color(0x22374151),
                            onClick = {
                                if (pendingFixUndo != null) {
                                    onInsertText(pendingFixUndo!!, false)
                                    pendingFixUndo = null
                                    Toast.makeText(context, "Quick fix undone.", Toast.LENGTH_SHORT).show()
                                } else {
                                    onRevertText()
                                    state = FloatingButtonState.IDLE
                                    Toast.makeText(context, "Undone to previous input.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        // 2. Quick Execute Button (Checks for /command in text or runs direct execution)
                        CompanionSquareIconButton(
                            icon = Icons.Default.Bolt,
                            contentDescription = "Quick Execute",
                            tint = Color(0xFFFBBF24),
                            backgroundColor = Color(0x333B82F6),
                            borderColor = Color(0xFF3B82F6),
                            onClick = {
                                val liveText = getLiveInputText()
                                if (liveText.isBlank()) {
                                    Toast.makeText(context, "Please type or place cursor in a text field first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    val (matchedCommand, cleanedText) = slashCommandRepository.matchAndExtractCommand(liveText)
                                    if (matchedCommand != null) {
                                        executeSlashCommand(matchedCommand, cleanedText)
                                    } else {
                                        startGeneration("", directExecute = true)
                                    }
                                }
                            }
                        )

                        // 3. Collapse ('x') Button
                        CompanionSquareIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Collapse",
                            tint = Color(0xFF9CA3AF),
                            backgroundColor = Color(0x331F2937),
                            borderColor = Color(0x33374151),
                            onClick = {
                                if (editingPromptInline != null) {
                                    editingPromptInline = null
                                    onEditorFocusMode(false)
                                }
                                setExpanded(false)
                            }
                        )
                    }
                }
            }
        }
    }
}
