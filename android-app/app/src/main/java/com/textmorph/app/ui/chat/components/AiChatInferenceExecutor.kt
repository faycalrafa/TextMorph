package com.textmorph.app.ui.chat.components

import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.context.ChatCompactor
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles inference streaming, context window assembly, and persistence of chat turns for AiChatScreen.
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
        setJob: (Job) -> Unit,
        agentEngine: com.textmorph.app.agent.AgentEngine? = null
    ) {
        onStart()

        val activeSession = chatRepository.getActiveSession()
        val priorMessages = chatRepository.currentMessages.value
        val isAgent = activeSession?.isAgentMode == true
        val systemPrompt = if (isAgent) ChatCompactor.AGENT_SYSTEM_PROMPT else ChatCompactor.DEFAULT_SYSTEM_PROMPT

        // Assemble sliding-window multi-turn inference prompt with compressed history & session state
        val assembledPrompt = ChatCompactor.assembleInferencePrompt(
            systemPrompt = systemPrompt,
            sessionSummary = activeSession?.summary,
            sessionState = activeSession?.sessionState,
            historyMessages = priorMessages,
            currentPrompt = prompt,
            documentText = documentText
        )

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

            val isModelLoaded = when (engine) {
                is LiteRtLmEngine -> engine.sessionState.value.isLoaded
                is LlamaCppEngine -> engine.sessionState.value.isLoaded
                else -> true
            }
            chatRepository.setGenerationStatus(if (!isModelLoaded) "Loading model into memory…" else "Thinking…")

            try {
                val sb = StringBuilder()
                var lastMetrics: com.textmorph.app.llm.InferenceMetrics? = null

                val flow = when (engine) {
                    is LiteRtLmEngine -> engine.generateStreamWithMetrics(
                        prompt = assembledPrompt,
                        imageBytes = imageBytes,
                        onMetricsUpdated = { lastMetrics = it }
                    )
                    is LlamaCppEngine -> engine.generateStreamWithMetrics(
                        prompt = assembledPrompt,
                        imageBytes = imageBytes,
                        onMetricsUpdated = { lastMetrics = it }
                    )
                    else -> engine.generateStream(prompt = assembledPrompt, imageBytes = imageBytes)
                }

                flow.collect { chunk ->
                    chatRepository.setGenerationStatus(null)
                    sb.append(chunk)
                    val currentText = sb.toString()
                    val (thought, answer) = extractThought(currentText)
                    val effectiveContent = if (thought != null) answer else currentText
                    chatRepository.updateLastAssistantMessage(
                        sessionId = sessionId,
                        content = effectiveContent,
                        thoughtContent = thought,
                        tokensPerSecond = lastMetrics?.tokensPerSecond,
                        totalTokens = lastMetrics?.tokenCount,
                        durationMs = lastMetrics?.totalDurationMs
                    )
                }

                val finalOutput = sb.toString().trim()
                val (thought, answer) = extractThought(finalOutput)
                val effectiveFinalContent = if (thought != null) answer else finalOutput

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = effectiveFinalContent,
                    thoughtContent = thought,
                    tokensPerSecond = lastMetrics?.tokensPerSecond,
                    totalTokens = lastMetrics?.tokenCount,
                    durationMs = lastMetrics?.totalDurationMs
                )
                AppLogger.d(TAG, "Completed chat turn for session=$sessionId: length=${finalOutput.length}, speed=${lastMetrics?.tokensPerSecond} tok/s")

                // If in Agent mode, check if LLM returned an autonomous tool action JSON
                if (isAgent && agentEngine != null) {
                    val parsedAction = com.textmorph.app.agent.parser.AgentActionParser.parseAction(effectiveFinalContent)
                    if (parsedAction != null) {
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = parsedAction.response.ifEmpty { "Executing ${parsedAction.action}…" },
                            thoughtContent = thought,
                            actionPayloadJson = parsedAction.action,
                            actionStatus = "running"
                        )
                        scope.launch(Dispatchers.IO) {
                            try {
                                val result = agentEngine.execute(parsedAction) { statusUpdate ->
                                    chatRepository.setGenerationStatus(statusUpdate)
                                }
                                chatRepository.updateLastAssistantMessage(
                                    sessionId = sessionId,
                                    content = if (result.success) {
                                        parsedAction.response.ifEmpty { result.details }
                                    } else {
                                        "Error executing ${parsedAction.action}: ${result.details}"
                                    },
                                    actionStatus = if (result.success) "success" else "failed"
                                )
                            } catch (actEx: Exception) {
                                AppLogger.e(TAG, "Action execution failed: ${actEx.message}", actEx)
                                chatRepository.updateLastAssistantMessage(
                                    sessionId = sessionId,
                                    content = "Error: ${actEx.message}",
                                    actionStatus = "failed"
                                )
                            } finally {
                                chatRepository.setGenerationStatus(null)
                            }
                        }
                    }
                }

                // Trigger background rolling summarization if message history exceeds trigger count
                val allMessages = chatRepository.currentMessages.value
                if (allMessages.size >= ChatCompactor.SUMMARY_TRIGGER_MESSAGE_COUNT) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val (older, _) = ChatCompactor.partitionWindow(allMessages)
                            if (older.isNotEmpty()) {
                                val newSummary = ChatCompactor.summarizeTurns(engine, activeSession?.summary, older)
                                val updatedFacts = ChatCompactor.extractSessionFacts(engine, activeSession?.sessionState, allMessages)
                                chatRepository.updateSessionCompactionState(sessionId, newSummary, updatedFacts)
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Background compaction failed: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Inference error during chat turn: ${e.message}", e)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Error: ${e.message}"
                )
            } finally {
                chatRepository.setGenerationStatus(null)
                onComplete()
            }
        }
        setJob(job)
    }

    fun extractThought(rawText: String): Pair<String?, String> {
        val thinkTag = when {
            rawText.contains("<think>") -> "<think>" to "</think>"
            rawText.contains("<thought>") -> "<thought>" to "</thought>"
            else -> return null to rawText
        }
        val start = rawText.indexOf(thinkTag.first)
        val end = rawText.indexOf(thinkTag.second)
        return if (end != -1) {
            val thought = rawText.substring(start + thinkTag.first.length, end).trim()
            val pre = rawText.substring(0, start).trim()
            val post = rawText.substring(end + thinkTag.second.length).trim()
            val answer = listOf(pre, post).filter { it.isNotEmpty() }.joinToString("\n\n")
            thought to answer
        } else {
            val thought = rawText.substring(start + thinkTag.first.length).trim()
            val pre = rawText.substring(0, start).trim()
            thought to pre
        }
    }
}
