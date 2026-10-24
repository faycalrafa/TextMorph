package com.textmorph.app.ui.chat.components

import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles inference streaming and persistence of chat turns for AiChatScreen.
 */
object AiChatInferenceExecutor {

    private const val TAG = "AiChatInference"

    fun executeTurn(
        sessionId: String,
        prompt: String,
        imageBytes: ByteArray?,
        documentText: String?,
        engine: LlmEngine,
        chatRepository: ChatSessionRepository,
        scope: CoroutineScope,
        onStart: () -> Unit,
        onComplete: () -> Unit,
        setJob: (Job) -> Unit
    ) {
        onStart()

        // Prepare user prompt (including document context if present)
        val fullUserPrompt = if (!documentText.isNullOrBlank()) {
            "Attached Document:\n$documentText\n\nUser Request:\n$prompt"
        } else {
            prompt
        }

        val userEntity = ChatMessageEntity(
            sessionId = sessionId,
            role = "user",
            content = prompt
        )

        val aiPlaceholder = ChatMessageEntity(
            sessionId = sessionId,
            role = "assistant",
            content = ""
        )

        val job = scope.launch(Dispatchers.Main) {
            chatRepository.appendMessage(userEntity)
            chatRepository.appendMessage(aiPlaceholder)

            try {
                val sb = StringBuilder()
                val flow = when (engine) {
                    is LiteRtLmEngine -> engine.generateStreamWithMetrics(prompt = fullUserPrompt, imageBytes = imageBytes)
                    is LlamaCppEngine -> engine.generateStreamWithMetrics(prompt = fullUserPrompt, imageBytes = imageBytes)
                    else -> engine.generateStream(prompt = fullUserPrompt, imageBytes = imageBytes)
                }

                flow.collect { chunk ->
                    sb.append(chunk)
                    val currentText = sb.toString()
                    val (thought, answer) = extractThought(currentText)
                    chatRepository.updateLastAssistantMessage(
                        sessionId = sessionId,
                        content = answer.ifEmpty { currentText },
                        thoughtContent = thought
                    )
                }

                val finalOutput = sb.toString().trim()
                val (thought, answer) = extractThought(finalOutput)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = answer.ifEmpty { finalOutput },
                    thoughtContent = thought
                )
                AppLogger.d(TAG, "Completed chat turn for session=$sessionId: length=${finalOutput.length}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Inference error during chat turn: ${e.message}", e)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Error: ${e.message}"
                )
            } finally {
                onComplete()
            }
        }
        setJob(job)
    }

    private fun extractThought(rawText: String): Pair<String?, String> {
        if (!rawText.contains("<think>")) return null to rawText
        val start = rawText.indexOf("<think>") + 7
        val end = rawText.indexOf("</think>")
        return if (end != -1) {
            val thought = rawText.substring(start, end).trim()
            val answer = rawText.substring(end + 8).trim()
            thought to answer
        } else {
            val thought = rawText.substring(start).trim()
            thought to ""
        }
    }
}
