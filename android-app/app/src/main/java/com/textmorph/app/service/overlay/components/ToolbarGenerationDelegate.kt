package com.textmorph.app.service.overlay.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.parser.GemmaToolCallParser
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.repository.LlmInteractionRepository
import com.textmorph.app.llm.LiteRtLmSessionDelegate
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.service.overlay.FloatingButtonState
import com.textmorph.app.ui.chat.components.ChatToolExecutionHandler
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates LLM text/vision streaming and autonomous background tool execution
 * for the Floating Companion Toolbar.
 * Intercepts tool calls, executes them in the background, feeds back observations,
 * and pastes only clean, synthesized text directly into the user's active input field.
 */
class ToolbarGenerationDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engineProvider: () -> LlmEngine,
    private val getLiveInputText: () -> String,
    private val onInsertText: (text: String, isIntermediate: Boolean) -> Unit,
    private val onSnapshotOriginalText: (original: String) -> Unit,
    private val onStateChanged: (FloatingButtonState) -> Unit,
    private val onActiveJobChanged: (Job?) -> Unit
) {
    private val TAG = "ToolbarGenDelegate"
    private var activeJob: Job? = null

    fun cancelActiveJob() {
        activeJob?.cancel()
        activeJob = null
        onActiveJobChanged(null)
        onStateChanged(FloatingButtonState.REVERT_WINDOW)
    }

    fun executeGeneration(systemInstruction: String, directExecute: Boolean, input: String) {
        onSnapshotOriginalText(input)
        onStateChanged(FloatingButtonState.GENERATING)
        activeJob?.cancel()

        activeJob = scope.launch {
            try {
                val engine = engineProvider()
                var currentInput = input
                var effectiveInstruction = if (directExecute) {
                    "You are an on-device writing assistant with access to tools (search_web, fetch_page, get_weather). Execute the request provided in the user's text. Return ONLY the final synthesized output with zero conversational commentary, zero intros/outros, zero quotes, and zero reasoning traces."
                } else {
                    "$systemInstruction. Strict rule: Output ONLY the transformed result directly. Do not include commentary, explanations, or thinking blocks."
                }

                var turn = 0
                val maxTurns = 3
                var finalOutput = ""
                var rawNaked = ""

                while (turn < maxTurns) {
                    turn++
                    val sb = StringBuilder()
                    var lastStreamFlushTime = 0L

                    engine.generateStream(currentInput, effectiveInstruction).collect { token ->
                        sb.append(token)
                        val now = System.currentTimeMillis()
                        val currentText = sb.toString()
                        val isToolSignaled = currentText.contains("<|tool_call") ||
                                currentText.contains("<tool_call") ||
                                currentText.contains("search_web(") ||
                                currentText.contains("fetch_page(") ||
                                currentText.contains("get_weather(")

                        // Suppress raw tool call tokens from being typed into the target app
                        if (!isToolSignaled && (now - lastStreamFlushTime >= 80L || token.contains("\n") || token.contains("."))) {
                            lastStreamFlushTime = now
                            val intermediate = LiteRtLmSessionDelegate.sanitizeNoThinking(currentText)
                            if (intermediate.isNotEmpty()) {
                                onInsertText(intermediate, true)
                            }
                        }
                    }

                    rawNaked = sb.toString()
                    val candidateAction = GemmaToolCallParser.parse(rawNaked) ?: AgentActionParser.parseAction(rawNaked)

                    if (candidateAction != null && ChatToolExecutionHandler.isInformationTool(candidateAction, context)) {
                        AppLogger.i(TAG, "Floating toolbar intercepted tool call: ${candidateAction.action}")
                        val statusMsg = ChatToolExecutionHandler.formatToolStatus(candidateAction)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
                        }

                        val (_, toolOutput) = ChatToolExecutionHandler.executeTool(candidateAction, context)
                        val obsPrompt = ChatToolExecutionHandler.formatObservationPrompt(candidateAction, toolOutput)

                        currentInput = "$input\n\nAssistant: ${candidateAction.action}\n$obsPrompt\nSynthesize the final answer based on the observation."
                        effectiveInstruction = "Synthesize the final output based on the observation. Return ONLY the final result with zero meta-commentary."
                        // Continue loop to synthesize the observation
                        continue
                    } else {
                        // Standard generation finished
                        finalOutput = LiteRtLmSessionDelegate.sanitizeNoThinking(rawNaked.trim())
                        finalOutput = GemmaToolCallParser.stripGemmaToolCall(finalOutput)
                        break
                    }
                }

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
                    try {
                        val triggerSource = if (directExecute) InteractionTriggerSource.CUSTOM_COMMANDS else InteractionTriggerSource.CUSTOM_PROMPTS
                        val triggerTitle = if (directExecute) "Toolbar Quick Execution" else "Toolbar Custom Prompt"
                        LlmInteractionRepository.getInstance(context).recordInteraction(
                            source = triggerSource,
                            title = triggerTitle,
                            userPrompt = input,
                            systemPrompt = effectiveInstruction,
                            rawOutput = rawNaked,
                            cleanOutput = finalOutput
                        )
                    } catch (logEx: Exception) {
                        AppLogger.w(TAG, "Failed recording toolbar diagnostic turn: ${logEx.message}")
                    }
                    onStateChanged(FloatingButtonState.REVERT_WINDOW)
                } else {
                    onStateChanged(FloatingButtonState.IDLE)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Inference error during toolbar generation: ${e.message}", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                onStateChanged(FloatingButtonState.IDLE)
            } finally {
                onActiveJobChanged(null)
            }
        }
        onActiveJobChanged(activeJob)
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

                val rawNaked = sb.toString()
                val finalOutput = LiteRtLmSessionDelegate.sanitizeNoThinking(rawNaked.trim())
                if (finalOutput.isNotEmpty()) {
                    onInsertText(finalOutput, false)
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("TextMorph Response", finalOutput))
                        Toast.makeText(context, "Response generated & copied to clipboard", Toast.LENGTH_SHORT).show()
                    } catch (clipEx: Exception) {
                        AppLogger.w(TAG, "Failed writing vision response to clipboard: ${clipEx.message}")
                    }
                    try {
                        LlmInteractionRepository.getInstance(context).recordInteraction(
                            source = InteractionTriggerSource.CUSTOM_PROMPTS,
                            title = "Vision Prompt Transformation",
                            userPrompt = rawInput,
                            systemPrompt = instruction,
                            rawOutput = rawNaked,
                            cleanOutput = finalOutput
                        )
                    } catch (logEx: Exception) {
                        AppLogger.w(TAG, "Failed recording vision diagnostic turn: ${logEx.message}")
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
}
