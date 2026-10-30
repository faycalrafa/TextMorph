package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.agent.AgentEngine
import com.textmorph.app.agent.guardrails.ExecutionGuardrail
import com.textmorph.app.agent.guardrails.ToolValidator
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.agent.router.ChatCommandRouter
import com.textmorph.app.agent.router.CommandRouteResult
import com.textmorph.app.agent.tools.registry.AgentToolRegistry
import com.textmorph.app.agent.tools.registry.ToolFilter
import com.textmorph.app.data.model.ChatAttachment
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.LlmInteractionRepository
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
 * Handles unified chat inference turns, pre-inference slash routing,
 * dynamic phase tool filtering, and multi-turn Code-as-Action guardrail loops.
 */
object AiChatInferenceExecutor {

    private const val TAG = "AiChatInference"
    private const val MAX_TOOL_HOPS = 5

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
        agentEngine: AgentEngine? = null,
        attachments: List<ChatAttachment> = emptyList(),
        settings: com.textmorph.app.data.model.AppSettings? = null,
        context: Context? = null
    ) {
        onStart()

        val effectiveSettings = settings ?: com.textmorph.app.data.model.AppSettings()
        val commandResult = ChatCommandRouter.route(prompt)

        // Handle pre-inference non-LLM commands (/workers, /benchmark)
        when (commandResult) {
            is CommandRouteResult.SetWorkersCommand -> {
                scope.launch(Dispatchers.Main) {
                    val userMsg = ChatMessageEntity(sessionId = sessionId, role = "user", content = prompt)
                    val reply = ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = commandResult.message
                    )
                    chatRepository.appendMessage(userMsg)
                    chatRepository.appendMessage(reply)
                    onComplete()
                }
                return
            }
            is CommandRouteResult.BenchmarkCommand -> {
                scope.launch(Dispatchers.Main) {
                    val userMsg = ChatMessageEntity(sessionId = sessionId, role = "user", content = prompt)
                    val reply = ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = "To run the Agentic Readiness Suite, open the Benchmark screen from the sidebar or Model Hub."
                    )
                    chatRepository.appendMessage(userMsg)
                    chatRepository.appendMessage(reply)
                    onComplete()
                }
                return
            }
            is CommandRouteResult.PlanCommand -> {
                if (context != null) {
                    AiChatPlanExecutionHandler.executePlanOrDeepSearch(
                        scope = scope,
                        sessionId = sessionId,
                        goal = commandResult.goal,
                        workerCount = commandResult.workersOverride,
                        userPrompt = prompt,
                        engine = engine,
                        chatRepository = chatRepository,
                        context = context,
                        onComplete = onComplete
                    )
                    return
                }
            }
            is CommandRouteResult.DeepSearchCommand -> {
                if (context != null) {
                    AiChatPlanExecutionHandler.executePlanOrDeepSearch(
                        scope = scope,
                        sessionId = sessionId,
                        goal = commandResult.query,
                        workerCount = commandResult.workersOverride,
                        userPrompt = prompt,
                        engine = engine,
                        chatRepository = chatRepository,
                        context = context,
                        onComplete = onComplete
                    )
                    return
                }
            }
            else -> {}
        }

        val phase = if (commandResult is CommandRouteResult.StandardTurn) commandResult.phase else ChatCommandRouter.classifyPhase(prompt)
        val allowedTools = ToolFilter.getToolsForPhase(phase, context)

        val activeSession = chatRepository.getActiveSession()
        val priorMessages = chatRepository.currentMessages.value

        val baseSystemPrompt = effectiveSettings.defaultSystemPrompt.takeIf { !it.isNullOrBlank() }
            ?: AgentPrompts.CHAT_SYSTEM_PROMPT
        val systemPrompt = AgentPrompts.resolvePhaseSystemPrompt(baseSystemPrompt, phase, context)

        val assembledPrompt = ChatCompactor.assembleInferencePrompt(
            systemPrompt = systemPrompt,
            sessionSummary = activeSession?.summary,
            sessionState = activeSession?.sessionState,
            historyMessages = priorMessages,
            currentPrompt = prompt,
            documentText = documentText,
            maxContextTokens = engine.maxContextTokens
        )

        val userEntity = ChatMessageEntity(
            sessionId = sessionId,
            role = "user",
            content = prompt,
            attachmentsJson = ChatMessageEntity.serializeAttachments(attachments),
            isAgentMode = true
        )
        val aiPlaceholder = ChatMessageEntity(
            sessionId = sessionId,
            role = "assistant",
            content = "",
            isAgentMode = true
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
                var currentPromptForInference = assembledPrompt
                var currentImagePayload: ByteArray? = imageBytes
                val guardrail = ExecutionGuardrail(maxHops = MAX_TOOL_HOPS)
                var hop = 0
                var finalOutput = ""
                var effectiveFinalContent = ""
                var thought: String? = null
                var executedActionName: String? = null
                var lastMetrics: com.textmorph.app.llm.InferenceMetrics? = null
                val streamStartTime = System.currentTimeMillis()
                var firstTokenTime: Long? = null
                var streamTokens = 0

                val turnHistory = mutableListOf<ChatMessageEntity>()

                while (hop < MAX_TOOL_HOPS) {
                    val sb = StringBuilder()
                    val flow = AiChatInferenceUtils.streamFromEngine(engine, currentPromptForInference, currentImagePayload) {
                        lastMetrics = it
                    }

                    flow.collect { chunk ->
                        chatRepository.setGenerationStatus(null)
                        val now = System.currentTimeMillis()
                        if (firstTokenTime == null && chunk.isNotBlank()) firstTokenTime = now
                        val words = chunk.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        streamTokens += words.coerceAtLeast(1)
                        sb.append(chunk)

                        val elapsedSec = ((now - (firstTokenTime ?: streamStartTime)) / 1000.0).coerceAtLeast(0.001)
                        val fallbackTps = (streamTokens / elapsedSec).toFloat()

                        val currentText = sb.toString()
                        val (curThought, curAnswer) = AiChatInferenceUtils.extractThought(currentText)
                        val effectiveChunkContent = if (curThought != null) curAnswer else currentText
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = effectiveChunkContent,
                            thoughtContent = curThought,
                            rawNakedOutput = currentText,
                            systemPrompt = systemPrompt,
                            tokensPerSecond = lastMetrics?.tokensPerSecond ?: fallbackTps,
                            totalTokens = lastMetrics?.tokenCount ?: streamTokens,
                            durationMs = lastMetrics?.totalDurationMs ?: (now - streamStartTime)
                        )
                    }

                    finalOutput = sb.toString().trim()
                    val (th, ans) = AiChatInferenceUtils.extractThought(finalOutput)
                    thought = th
                    effectiveFinalContent = if (th != null) ans else finalOutput

                    // Check if output contains a Code-as-Action call
                    val action = CodeActionParser.parse(effectiveFinalContent)
                    if (action == null) {
                        // Pure conversational output; tool loop terminates cleanly
                        break
                    }

                    // Pre-Flight Validation
                    val validation = ToolValidator.validate(action, allowedTools)
                    if (!validation.isValid) {
                        AppLogger.w(TAG, "Tool pre-flight validation failed: ${validation.error}. Triggering self-correction retry.")
                        val correctionPrompt = ToolValidator.synthesizeCorrectionPrompt(action, validation.error)
                        chatRepository.setGenerationStatus("Validating tool parameters… self-correcting…")

                        turnHistory.add(ChatMessageEntity(sessionId = sessionId, role = "assistant", content = effectiveFinalContent))
                        currentPromptForInference = ChatCompactor.assembleInferencePrompt(
                            systemPrompt = systemPrompt,
                            sessionSummary = activeSession?.summary,
                            sessionState = activeSession?.sessionState,
                            historyMessages = priorMessages + turnHistory,
                            currentPrompt = correctionPrompt,
                            documentText = null,
                            maxContextTokens = engine.maxContextTokens
                        )
                        currentImagePayload = null
                        hop++
                        continue
                    }

                    // Execution Guardrail & Loop Detection
                    val guardrailDecision = guardrail.evaluate(action.action, action.params)
                    if (!guardrailDecision.allowed) {
                        AppLogger.w(TAG, "Execution circuit breaker fired: ${guardrailDecision.reason}")
                        effectiveFinalContent = "${effectiveFinalContent}\n\n[Guardrail Notice: ${guardrailDecision.reason}]"
                        break
                    }

                    executedActionName = action.action

                    // Execute tool if it is an information tool
                    if (context != null && ChatToolExecutionHandler.isInformationTool(action, context)) {
                        val statusMsg = ChatToolExecutionHandler.formatToolStatus(action, context)
                        chatRepository.setGenerationStatus(statusMsg)
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = "",
                            actionPayloadJson = action.action,
                            actionStatus = "running"
                        )

                        val (_, toolOutput) = ChatToolExecutionHandler.executeTool(action, context)
                        val toolName = ChatToolExecutionHandler.getToolDisplayName(action, context)
                        chatRepository.setGenerationStatus("$toolName finished! Thinking & synthesizing answer…")
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = "",
                            actionPayloadJson = action.action,
                            actionStatus = "success"
                        )

                        val obsPrompt = ChatToolExecutionHandler.formatObservationPrompt(action, toolOutput)
                        turnHistory.add(ChatMessageEntity(sessionId = sessionId, role = "assistant", content = effectiveFinalContent))

                        currentPromptForInference = ChatCompactor.assembleInferencePrompt(
                            systemPrompt = systemPrompt,
                            sessionSummary = activeSession?.summary,
                            sessionState = activeSession?.sessionState,
                            historyMessages = priorMessages + turnHistory,
                            currentPrompt = obsPrompt,
                            documentText = null,
                            maxContextTokens = engine.maxContextTokens
                        )
                        currentImagePayload = null
                        hop++
                    } else if (agentEngine != null) {
                        // Autonomous device-level action
                        AiChatAutonomousActionHandler.handleAutonomousAction(
                            action = action,
                            userPrompt = prompt,
                            sessionId = sessionId,
                            systemPrompt = systemPrompt,
                            thought = thought,
                            finalOutput = finalOutput,
                            agentEngine = agentEngine,
                            chatRepository = chatRepository,
                            scope = scope
                        )
                        break
                    } else {
                        break
                    }
                }

                val endNow = System.currentTimeMillis()
                val totalDuration = (endNow - streamStartTime).coerceAtLeast(1L)
                val finalFallbackTps = if (streamTokens > 0 && totalDuration > 0) {
                    (streamTokens / (totalDuration / 1000f)).coerceAtLeast(0.1f)
                } else 0f
                val finalTps = (lastMetrics?.tokensPerSecond?.takeIf { it > 0f } ?: finalFallbackTps)
                val finalTokens = lastMetrics?.tokenCount ?: streamTokens
                val finalDuration = lastMetrics?.totalDurationMs ?: totalDuration

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = effectiveFinalContent,
                    thoughtContent = thought,
                    rawNakedOutput = finalOutput,
                    systemPrompt = systemPrompt,
                    actionPayloadJson = executedActionName,
                    actionStatus = if (executedActionName != null) "success" else null,
                    tokensPerSecond = finalTps,
                    totalTokens = finalTokens,
                    durationMs = finalDuration
                )
                AppLogger.d(TAG, "Completed chat turn for session=$sessionId: length=${finalOutput.length}, speed=$finalTps tok/s")

                if (context != null) {
                    try {
                        LlmInteractionRepository.getInstance(context).recordInteraction(
                            source = InteractionTriggerSource.CHAT_CONVERSATION,
                            title = "Chat Turn",
                            userPrompt = prompt,
                            systemPrompt = systemPrompt,
                            rawOutput = finalOutput,
                            cleanOutput = effectiveFinalContent,
                            thoughtContent = thought,
                            tokensPerSecond = finalTps,
                            totalTokens = finalTokens,
                            durationMs = finalDuration
                        )
                    } catch (logEx: Exception) {
                        AppLogger.w(TAG, "Failed logging chat turn interaction: ${logEx.message}")
                    }
                }

                // Rolling summarization
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

    fun isMultiStepIntent(userPrompt: String, modelResponse: String = ""): Boolean =
        AiChatInferenceUtils.isMultiStepIntent(userPrompt, modelResponse)
}
