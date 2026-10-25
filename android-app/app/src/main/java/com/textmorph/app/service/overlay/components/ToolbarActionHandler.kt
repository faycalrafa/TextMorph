package com.textmorph.app.service.overlay.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.model.SlashCommandEntity
import com.textmorph.app.llm.LiteRtLmSessionDelegate
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.service.overlay.FloatingButtonState
import com.textmorph.app.ui.camera.CameraCaptureActivity
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.EmojiTextArtHelper
import com.textmorph.app.util.KeywordContextResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Handles text generation, vision analysis, prompt execution, and typing triggers
 * for the floating companion toolbar.
 */
class ToolbarActionHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engineProvider: () -> LlmEngine,
    private val getLiveInputText: () -> String,
    private val isVisionCapable: () -> Boolean,
    private val onInsertText: (text: String, isIntermediate: Boolean) -> Unit,
    private val onSnapshotOriginalText: (original: String) -> Unit,
    private val onRevertText: () -> Unit,
    private val onTakeScreenshotContext: (((ByteArray) -> Unit) -> Unit)?,
    private val onStateChanged: (FloatingButtonState) -> Unit,
    private val onActiveJobChanged: (Job?) -> Unit
) {
    private val TAG = "ToolbarActionHandler"

    private var activeJob: Job? = null
    private var quickFixJob: Job? = null

    fun cancelActiveJob() {
        activeJob?.cancel()
        activeJob = null
        onActiveJobChanged(null)
        onStateChanged(FloatingButtonState.REVERT_WINDOW)
    }

    fun cancelQuickFix() {
        quickFixJob?.cancel()
        quickFixJob = null
    }

    fun handleUserTyped(
        newText: String,
        currentState: FloatingButtonState,
        quickFixEnabled: Boolean,
        onFixingInProgressChanged: (Boolean) -> Unit,
        onPendingFixUndoChanged: (String?) -> Unit
    ) {
        if (currentState == FloatingButtonState.REVERT_WINDOW) {
            onStateChanged(FloatingButtonState.IDLE)
        }
        onPendingFixUndoChanged(null)

        // 1. Emoji trigger :(emotion):
        val emojiMatch = EmojiTextArtHelper.findEmojiMatch(newText)
        if (emojiMatch != null) {
            val emotion = emojiMatch.groupValues[1]
            val fullMatch = emojiMatch.value
            scope.launch {
                try {
                    val emoji = EmojiTextArtHelper.resolveEmoji(emotion, engineProvider())
                    val current = getLiveInputText()
                    if (current.contains(fullMatch)) {
                        val replaced = current.replace(fullMatch, "$emoji ")
                        onSnapshotOriginalText(current)
                        onInsertText(replaced, false)
                        Toast.makeText(context, "Emoji: $emoji", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Emoji resolution failed: ${e.message}")
                }
            }
        }

        // 2. Text Art trigger T:(emotion):
        val textArtMatch = EmojiTextArtHelper.findTextArtMatch(newText)
        if (textArtMatch != null) {
            val artRequest = textArtMatch.groupValues[1]
            val fullMatch = textArtMatch.value
            scope.launch {
                try {
                    val textArt = EmojiTextArtHelper.resolveTextArt(artRequest, engineProvider())
                    val current = getLiveInputText()
                    if (current.contains(fullMatch)) {
                        val replaced = current.replace(fullMatch, "$textArt ")
                        onSnapshotOriginalText(current)
                        onInsertText(replaced, false)
                        Toast.makeText(context, "Text Art applied", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Text art resolution failed: ${e.message}")
                }
            }
        }

        // 3. Quick Fix debounced auto-replacement
        if (quickFixEnabled && currentState == FloatingButtonState.IDLE) {
            quickFixJob?.cancel()
            val trimmed = newText.trim()
            val wordCount = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }.size
            if (trimmed.length in 3..140 && wordCount <= 25) {
                quickFixJob = scope.launch {
                    delay(550L)
                    if (!isActive) return@launch
                    val current = getLiveInputText().trim()
                    if (current != trimmed || current.length < 3 || current.length > 140) return@launch
                    try {
                        onFixingInProgressChanged(true)
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
                                onPendingFixUndoChanged(current)
                                AppLogger.d(TAG, "Quick fix automatically replaced text with: '$fixed'")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Quick fix background error: ${e.message}")
                    } finally {
                        onFixingInProgressChanged(false)
                    }
                }
            }
        } else {
            quickFixJob?.cancel()
            onFixingInProgressChanged(false)
        }
    }

    fun startVisionGenerationWithBytes(instruction: String, imageBytes: ByteArray, overrideInput: String? = null) {
        val rawInput = overrideInput ?: getLiveInputText().ifBlank { "Analyze this image" }
        onSnapshotOriginalText(getLiveInputText())

        onStateChanged(FloatingButtonState.GENERATING)
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val engine = engineProvider()
                val sb = StringBuilder()
                var lastStreamFlushTime = 0L

                engine.generateStream(rawInput, instruction, imageBytes).collect { token ->
                    sb.append(token)
                    val now = System.currentTimeMillis()
                    if (now - lastStreamFlushTime >= 80L || token.contains("\n") || token.contains(".")) {
                        lastStreamFlushTime = now
                        val intermediate = LiteRtLmSessionDelegate.sanitizeNoThinking(sb.toString())
                        if (intermediate.isNotEmpty()) {
                            onInsertText(intermediate, true)
                        }
                    }
                }

                val finalOutput = LiteRtLmSessionDelegate.sanitizeNoThinking(sb.toString().trim())
                if (finalOutput.isNotEmpty()) {
                    onInsertText(finalOutput, false)
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("TextMorph Response", finalOutput))
                        Toast.makeText(context, "Response generated & copied to clipboard", Toast.LENGTH_SHORT).show()
                    } catch (clipEx: Exception) {
                        AppLogger.w(TAG, "Failed writing vision response to clipboard: ${clipEx.message}")
                    }
                    onStateChanged(FloatingButtonState.REVERT_WINDOW)
                } else {
                    onStateChanged(FloatingButtonState.IDLE)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Vision inference error: ${e.message}", e)
                Toast.makeText(context, "Vision error: ${e.message}", Toast.LENGTH_SHORT).show()
                onStateChanged(FloatingButtonState.IDLE)
            } finally {
                onActiveJobChanged(null)
            }
        }
        onActiveJobChanged(activeJob)
    }

    fun executeGenerationInternal(systemInstruction: String, directExecute: Boolean, input: String) {
        onSnapshotOriginalText(input)
        onStateChanged(FloatingButtonState.GENERATING)
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
                        val intermediate = LiteRtLmSessionDelegate.sanitizeNoThinking(sb.toString())
                        if (intermediate.isNotEmpty()) {
                            onInsertText(intermediate, true)
                        }
                    }
                }

                val finalOutput = LiteRtLmSessionDelegate.sanitizeNoThinking(sb.toString().trim())
                if (finalOutput.startsWith("Error:") || finalOutput.startsWith("Error (")) {
                    AppLogger.w(TAG, "Generation returned error: $finalOutput")
                    Toast.makeText(context, finalOutput, Toast.LENGTH_LONG).show()
                    onStateChanged(FloatingButtonState.IDLE)
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
                    onStateChanged(FloatingButtonState.REVERT_WINDOW)
                } else {
                    onStateChanged(FloatingButtonState.IDLE)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Inference error during generation: ${e.message}", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                onStateChanged(FloatingButtonState.IDLE)
            } finally {
                onActiveJobChanged(null)
            }
        }
        onActiveJobChanged(activeJob)
    }

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
            ContextAction.ACCESSIBILITY_TEXT -> startGeneration(prompt.systemInstruction, directExecute = false)
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
            ContextAction.SCREENSHOT -> startVisionGeneration(command.systemInstruction, overrideInput = cleanedInput.ifBlank { "Analyze this screen" })
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
            ContextAction.OCR -> startOcrGeneration(command.systemInstruction, overrideInput = cleanedInput.ifBlank { "Transcribe and analyze this screen" })
            ContextAction.ACCESSIBILITY_TEXT -> startGeneration(command.systemInstruction, directExecute = false, contextInput = cleanedInput)
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
            ContextAction.NONE -> startGeneration(command.systemInstruction, directExecute = false, contextInput = cleanedInput)
        }
    }
}
