package com.textmorph.app.service.overlay

import android.content.Context
import android.widget.Toast
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.repository.LlmInteractionRepository
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LiteRtLmSessionDelegate
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.ClipboardHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes direct ScreenAssist tasks:
 * Captures clean screen context, runs on-device multimodal assistance,
 * dumps the sanitized result to the system clipboard, and gives immediate user feedback.
 */
object ScreenAssistExecutor {

    private const val TAG = "ScreenAssistExecutor"

    suspend fun executeScreenAssist(
        context: Context,
        service: TextMorphAccessibilityService?,
        customInstruction: String? = null,
        copyToClipboard: Boolean = true,
        customToast: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Starting ScreenAssist execution...")
        val appContext = context.applicationContext

        val effectiveInstruction = customInstruction?.takeIf { it.isNotBlank() }
            ?: "You are an on-device screen assistant. Analyze the user's active screen and provide a direct, helpful, actionable solution or summary. Strict rule: Output ONLY the clean assistance text with zero conversational filler, zero quotes, and zero reasoning traces."

        // 1. Gather Screen Context
        val visibleText = service?.extractScreenVisibleText()?.takeIf { it.isNotBlank() }
        val imageBytes = service?.captureScreenBitmapBytes()

        val promptInput = if (!visibleText.isNullOrBlank()) {
            "Active Screen Content:\n$visibleText\n\nTask: $effectiveInstruction"
        } else {
            "Analyze current screen. $effectiveInstruction"
        }

        try {
            val engine = LiteRtLmEngineManager.getInstance(appContext)
            val sb = StringBuilder()

            val stream = if (imageBytes != null && engine.isVisionCapable) {
                engine.generateStream(promptInput, effectiveInstruction, imageBytes)
            } else {
                engine.generateStream(promptInput, effectiveInstruction)
            }

            stream.collect { token ->
                sb.append(token)
            }

            val rawOutput = sb.toString()
            val cleanOutput = LiteRtLmSessionDelegate.sanitizeNoThinking(rawOutput.trim())

            if (cleanOutput.isBlank() || cleanOutput.startsWith("Error:") || cleanOutput.startsWith("Error (")) {
                val err = if (cleanOutput.isNotBlank()) cleanOutput else "Screen assist generation returned empty result."
                AppLogger.w(TAG, "Screen assist failed: $err")
                return@withContext Pair(false, err)
            }

            // 2. Dump to Clipboard
            if (copyToClipboard) {
                val toast = customToast ?: "📋 Screen Assist copied to clipboard!"
                ClipboardHelper.copyText(
                    context = appContext,
                    text = cleanOutput,
                    label = "Screen Assist",
                    showToast = true,
                    toastMessage = toast
                )
            } else if (!customToast.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, customToast, Toast.LENGTH_SHORT).show()
                }
            }

            // 3. Record Interaction
            try {
                LlmInteractionRepository.getInstance(appContext).recordInteraction(
                    source = InteractionTriggerSource.VOICE_AGENT,
                    title = "Screen Assist Hotkey",
                    userPrompt = promptInput,
                    systemPrompt = effectiveInstruction,
                    rawOutput = rawOutput,
                    cleanOutput = cleanOutput
                )
            } catch (logEx: Exception) {
                AppLogger.w(TAG, "Failed logging interaction record: ${logEx.message}")
            }

            AppLogger.i(TAG, "Screen assist completed successfully (${cleanOutput.length} chars).")
            Pair(true, cleanOutput)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception during ScreenAssist: ${e.message}", e)
            Pair(false, "ScreenAssist error: ${e.message}")
        }
    }
}
