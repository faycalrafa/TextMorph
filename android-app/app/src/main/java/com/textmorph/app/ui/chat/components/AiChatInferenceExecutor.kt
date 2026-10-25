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
                        rawNakedOutput = currentText,
                        systemPrompt = systemPrompt,
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
                    rawNakedOutput = finalOutput,
                    systemPrompt = systemPrompt,
                    tokensPerSecond = lastMetrics?.tokensPerSecond,
                    totalTokens = lastMetrics?.tokenCount,
                    durationMs = lastMetrics?.totalDurationMs
                )
                AppLogger.d(TAG, "Completed chat turn for session=$sessionId: length=${finalOutput.length}, speed=${lastMetrics?.tokensPerSecond} tok/s")

                // If in Agent mode, check if LLM returned an autonomous tool action JSON
                if (isAgent && agentEngine != null) {
                    val parsedAction = com.textmorph.app.agent.parser.AgentActionParser.parseAction(effectiveFinalContent)
                    if (parsedAction != null) {
                        AppLogger.i(TAG, "Executing autonomous agent action: ${parsedAction.action}")
                        // Standalone actions must not inherit stale failure state from previous multi-step tasks
                        if (parsedAction.action != com.textmorph.app.agent.model.AgentAction.ACTION_EXECUTE_TASK) {
                            com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.reset()
                        }
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = parsedAction.response.ifEmpty { "Executing ${parsedAction.action}…" },
                            thoughtContent = thought,
                            rawNakedOutput = finalOutput,
                            systemPrompt = systemPrompt,
                            actionPayloadJson = parsedAction.action,
                            actionStatus = "running"
                        )
                        try {
                            val result = agentEngine.execute(
                                action = parsedAction,
                                onProgress = { statusUpdate ->
                                    chatRepository.setGenerationStatus(statusUpdate)
                                },
                                onStepsUpdated = { steps ->
                                    val isInterrupted = com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.state.value.isInterrupted
                                    val hasFailed = steps.any { it.status == com.textmorph.app.agent.model.AgentStepStatus.FAILED }
                                    val currentStatus = when {
                                        isInterrupted -> "interrupted"
                                        hasFailed -> "failed"
                                        else -> "running"
                                    }
                                    scope.launch(Dispatchers.IO) {
                                        chatRepository.updateLastAssistantMessage(
                                            sessionId = sessionId,
                                            content = parsedAction.response.ifEmpty { "Executing ${parsedAction.action}…" },
                                            thoughtContent = thought,
                                            rawNakedOutput = finalOutput,
                                            systemPrompt = systemPrompt,
                                            actionPayloadJson = parsedAction.action,
                                            actionStatus = currentStatus,
                                            actionStepsJson = com.textmorph.app.agent.model.AgentTaskStep.serializeList(steps)
                                        )
                                    }
                                }
                            )
                            val isExecuteTask = parsedAction.action == com.textmorph.app.agent.model.AgentAction.ACTION_EXECUTE_TASK
                            val taskState = com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.state.value
                            val isInterrupted = if (isExecuteTask) taskState.isInterrupted else false
                            val isSuccess = if (isExecuteTask) {
                                result.success && !taskState.isInterrupted && !taskState.isFailed
                            } else {
                                result.success
                            }
                            val finalContent = when {
                                isInterrupted -> "Task stopped by user."
                                isSuccess -> parsedAction.response.ifEmpty { result.details }
                                result.details.startsWith("Task Failed:") ||
                                    result.details.startsWith("Error:") ||
                                    result.details.startsWith("Action Failed:") -> result.details
                                else -> "Action Failed: ${result.details}"
                            }
                            val finalSteps = if (isExecuteTask) {
                                taskState.stepsHistory
                            } else {
                                val stepStatus = when {
                                    isInterrupted -> com.textmorph.app.agent.model.AgentStepStatus.INTERRUPTED
                                    isSuccess -> com.textmorph.app.agent.model.AgentStepStatus.COMPLETED
                                    else -> com.textmorph.app.agent.model.AgentStepStatus.FAILED
                                }
                                listOf(
                                    com.textmorph.app.agent.model.AgentTaskStep(
                                        stepIndex = 1,
                                        actionType = parsedAction.action,
                                        title = parsedAction.response.ifEmpty { "Executing ${parsedAction.action}…" },
                                        detail = result.details,
                                        status = stepStatus
                                    )
                                )
                            }
                            chatRepository.updateLastAssistantMessage(
                                sessionId = sessionId,
                                content = finalContent,
                                actionStatus = if (isInterrupted) "interrupted" else if (isSuccess) "success" else "failed",
                                actionStepsJson = com.textmorph.app.agent.model.AgentTaskStep.serializeList(finalSteps)
                            )
                            if (!isSuccess && !isInterrupted) {
                                AppLogger.w(TAG, "Agent action '${parsedAction.action}' failed: $finalContent (details='${result.details}')")
                            } else if (isSuccess) {
                                AppLogger.i(TAG, "Agent action '${parsedAction.action}' succeeded: ${result.details}")
                            } else {
                                AppLogger.i(TAG, "Agent action '${parsedAction.action}' interrupted by user.")
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            AppLogger.i(TAG, "Agent action execution cancelled by user.")
                            com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                            agentEngine.cancelRunningTask()
                            chatRepository.updateLastAssistantMessage(
                                sessionId = sessionId,
                                content = "Task stopped by user.",
                                actionStatus = "interrupted"
                            )
                            throw ce
                        } catch (actEx: Exception) {
                            AppLogger.e(TAG, "Agent action '${parsedAction.action}' threw exception: ${actEx.message}", actEx)
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
            } catch (ce: kotlinx.coroutines.CancellationException) {
                AppLogger.i(TAG, "Chat turn cancelled: ${ce.message}")
                com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                agentEngine?.cancelRunningTask()
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Task stopped by user.",
                    actionStatus = "interrupted"
                )
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "Inference error during chat turn: ${e.message}", e)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Error: ${e.message}",
                    actionStatus = "failed"
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
