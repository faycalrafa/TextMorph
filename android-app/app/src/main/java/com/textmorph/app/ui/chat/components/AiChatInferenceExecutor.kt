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
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.context.ChatCompactor
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
                    chatRepository.appendMessage(ChatMessageEntity(sessionId = sessionId, role = "user", content = prompt))
                    chatRepository.appendMessage(ChatMessageEntity(sessionId = sessionId, role = "assistant", content = commandResult.message))
                    onComplete()
                }
                return
            }
            is CommandRouteResult.BenchmarkCommand -> {
                scope.launch(Dispatchers.Main) {
                    chatRepository.appendMessage(ChatMessageEntity(sessionId = sessionId, role = "user", content = prompt))
                    chatRepository.appendMessage(ChatMessageEntity(sessionId = sessionId, role = "assistant", content = "To run the Agentic Readiness Suite, open the Benchmark screen from the sidebar or Model Hub."))
                    onComplete()
                }
                return
            }
            is CommandRouteResult.PlanCommand -> {
                if (context != null) {
                    AiChatPlanExecutionHandler.handlePlanCommand(
                        scope = scope, sessionId = sessionId, command = commandResult,
                        userPrompt = prompt, engine = engine, chatRepository = chatRepository,
                        context = context, onComplete = onComplete
                    )
                    return
                }
            }
            is CommandRouteResult.DeepSearchCommand -> {
                if (context != null) {
                    AiChatDeepSearchHandler.handleDeepSearchCommand(
                        scope = scope, sessionId = sessionId, command = commandResult,
                        userPrompt = prompt, engine = engine, chatRepository = chatRepository,
                        context = context, onComplete = onComplete
                    )
                    return
                }
            }
            is CommandRouteResult.GoalCommand -> {
                if (context != null) {
                    val coordinator = com.textmorph.app.agent.orchestration.GoalExecutionCoordinator(
                        context = context, engine = engine, chatRepository = chatRepository, agentEngine = agentEngine
                    )
                    coordinator.executeGoal(scope = scope, sessionId = sessionId, goal = commandResult.goal, userPrompt = prompt, onComplete = onComplete)
                    return
                }
            }
            else -> {}
        }

        val standardTurn = commandResult as? CommandRouteResult.StandardTurn
        val phase = standardTurn?.phase ?: ChatCommandRouter.classifyPhase(prompt)
        val forcedTool = standardTurn?.forcedTool

        val phaseTools = ToolFilter.getToolsForPhase(phase, context).toMutableList()
        if (forcedTool != null && phaseTools.none { it.id == forcedTool }) {
            val candidate = (AgentToolRegistry.getInAppTools() + AgentToolRegistry.getJsTools(context))
                .firstOrNull { it.id == forcedTool }
            if (candidate != null) {
                phaseTools.add(0, candidate)
            }
        }
        val allowedTools = phaseTools

        val activeSession = chatRepository.getActiveSession()
        val priorMessages = chatRepository.currentMessages.value

        val baseSystemPrompt = effectiveSettings.defaultSystemPrompt.takeIf { !it.isNullOrBlank() }
            ?: AgentPrompts.CHAT_SYSTEM_PROMPT
        var systemPrompt = AgentPrompts.resolvePhaseSystemPrompt(baseSystemPrompt, phase, context)
        if (forcedTool != null) {
            val forcedDef = allowedTools.firstOrNull { it.id == forcedTool }
            val sigNote = if (forcedDef != null) "\nRequired Tool Signature: ${forcedDef.codeSignature}" else ""
            systemPrompt = "MANDATORY IMMEDIATE ACTION: The user explicitly invoked @$forcedTool. You MUST execute this tool on your first step to satisfy the user request.$sigNote Do not answer directly without calling this tool.\n\n" + systemPrompt
        }
        if (effectiveSettings.isThinkingActive) {
            systemPrompt = "Think carefully step-by-step before answering. Place your entire internal reasoning process between <think> and </think> tags.\n\n" + systemPrompt
        }

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

            val stepTraces = mutableListOf<com.textmorph.app.data.model.AgentStepTrace>()
            val collectedSources = mutableListOf<com.textmorph.app.data.model.SourceCitation>()

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
                    val flow = AiChatInferenceUtils.streamFromEngine(
                        engine = engine,
                        prompt = currentPromptForInference,
                        imageBytes = currentImagePayload,
                        temperature = if (hop > 0) 0.0f else effectiveSettings.temperature
                    ) {
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
                            stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces).takeIf { stepTraces.isNotEmpty() },
                            sourcesJson = ChatMessageEntity.serializeSources(collectedSources).takeIf { collectedSources.isNotEmpty() },
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
                    if (action == null || AiChatInferenceUtils.isPresentationOrFollowUpAction(effectiveFinalContent, action)) {
                        if (hop == 0 && forcedTool != null && executedActionName == null) {
                            AppLogger.w(TAG, "Forced tool @$forcedTool was not executed on first hop. Enforcing tool invocation.")
                            val forcedDef = allowedTools.firstOrNull { it.id == forcedTool }
                            val exampleCall = forcedDef?.codeSignature ?: "$forcedTool(...)"
                            val enforcementPrompt = "CRITICAL DIRECTIVE: You failed to execute the required tool '@$forcedTool'. You MUST invoke the tool now before answering. Example: ```tool\n$exampleCall\n```"
                            chatRepository.setGenerationStatus("Invoking required tool @$forcedTool…")
                            currentPromptForInference = AiChatInferenceUtils.assembleNextHopPrompt(
                                systemPrompt = systemPrompt,
                                activeSession = activeSession,
                                priorMessages = priorMessages,
                                turnHistory = turnHistory,
                                sessionId = sessionId,
                                effectiveContent = effectiveFinalContent,
                                nextPrompt = enforcementPrompt,
                                maxContextTokens = engine.maxContextTokens
                            )
                            currentImagePayload = null
                            hop++
                            continue
                        }
                        // Pure conversational output or follow-up presentation; tool loop terminates cleanly
                        break
                    }

                    val currentTrace = AiChatInferenceUtils.createStepTrace(
                        stepIndex = stepTraces.size + 1,
                        action = action,
                        stepResponse = action.reasoning ?: action.response,
                        thought = thought
                    )
                    stepTraces.add(currentTrace)
                    chatRepository.updateLastAssistantMessage(
                        sessionId = sessionId,
                        content = effectiveFinalContent,
                        thoughtContent = thought,
                        stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces),
                        sourcesJson = ChatMessageEntity.serializeSources(collectedSources).takeIf { collectedSources.isNotEmpty() }
                    )

                    // Pre-Flight Validation
                    val validation = ToolValidator.validate(action, allowedTools, context)
                    if (!validation.isValid) {
                        AppLogger.w(TAG, "Tool pre-flight validation failed: ${validation.error}. Triggering self-correction retry.")
                        AiChatInferenceUtils.failStepTrace(currentTrace, "Validation error: ${validation.error}")
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = effectiveFinalContent,
                            thoughtContent = thought,
                            stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces)
                        )
                        val correctionPrompt = ToolValidator.synthesizeCorrectionPrompt(action, validation.error)
                        chatRepository.setGenerationStatus("Validating tool parameters… self-correcting…")

                        currentPromptForInference = AiChatInferenceUtils.assembleNextHopPrompt(
                            systemPrompt = systemPrompt,
                            activeSession = activeSession,
                            priorMessages = priorMessages,
                            turnHistory = turnHistory,
                            sessionId = sessionId,
                            effectiveContent = effectiveFinalContent,
                            nextPrompt = correctionPrompt,
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
                        AiChatInferenceUtils.failStepTrace(currentTrace, "Loop halted: ${guardrailDecision.reason}")
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = effectiveFinalContent,
                            thoughtContent = thought,
                            stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces)
                        )
                        val recoveryPrompt = "Notice: The tool action '${action.action}' was already executed or blocked (${guardrailDecision.reason}). Please provide the complete final answer directly to the user based on available information without calling this tool again."
                        currentPromptForInference = AiChatInferenceUtils.assembleNextHopPrompt(
                            systemPrompt = systemPrompt,
                            activeSession = activeSession,
                            priorMessages = priorMessages,
                            turnHistory = turnHistory,
                            sessionId = sessionId,
                            effectiveContent = effectiveFinalContent,
                            nextPrompt = recoveryPrompt,
                            maxContextTokens = engine.maxContextTokens
                        )
                        currentImagePayload = null
                        hop++
                        continue
                    }

                    executedActionName = action.action

                    // Execute tool if it is an information tool
                    if (context != null && ChatToolExecutionHandler.isInformationTool(action, context)) {
                        val statusMsg = ChatToolExecutionHandler.formatToolStatus(action, context)
                        chatRepository.setGenerationStatus(statusMsg)
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = effectiveFinalContent,
                            thoughtContent = thought,
                            actionPayloadJson = action.action,
                            actionStatus = "running",
                            stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces)
                        )

                        val (toolSuccess, toolOutput) = ChatToolExecutionHandler.executeTool(action, context)
                        val toolName = ChatToolExecutionHandler.getToolDisplayName(action, context)
                        AiChatInferenceUtils.extractSourcesFromToolOutput(action, toolOutput, collectedSources)

                        if (toolSuccess) {
                            AiChatInferenceUtils.completeStepTrace(currentTrace, toolOutput)
                        } else {
                            AiChatInferenceUtils.failStepTrace(currentTrace, toolOutput.take(120))
                        }

                        chatRepository.setGenerationStatus("$toolName finished! Thinking & synthesizing answer…")
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = effectiveFinalContent,
                            thoughtContent = thought,
                            actionPayloadJson = action.action,
                            actionStatus = if (toolSuccess) "success" else "failed",
                            stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces),
                            sourcesJson = ChatMessageEntity.serializeSources(collectedSources).takeIf { collectedSources.isNotEmpty() }
                        )

                        val obsPrompt = ChatToolExecutionHandler.formatObservationPrompt(action, toolOutput)
                        currentPromptForInference = AiChatInferenceUtils.assembleNextHopPrompt(
                            systemPrompt = systemPrompt,
                            activeSession = activeSession,
                            priorMessages = priorMessages,
                            turnHistory = turnHistory,
                            sessionId = sessionId,
                            effectiveContent = effectiveFinalContent,
                            nextPrompt = obsPrompt,
                            maxContextTokens = engine.maxContextTokens
                        )
                        currentImagePayload = null
                        hop++
                    } else if (agentEngine != null) {
                        // Autonomous device-level action
                        AiChatInferenceUtils.completeStepTrace(currentTrace, "Device action initiated")
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = effectiveFinalContent,
                            stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces)
                        )
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

                for (t in stepTraces) {
                    if (t.status == "running") {
                        AiChatInferenceUtils.completeStepTrace(t, "Finished")
                    }
                }

                val finalDuration = lastMetrics?.totalDurationMs ?: (System.currentTimeMillis() - streamStartTime).coerceAtLeast(1L)
                val finalTps = lastMetrics?.tokensPerSecond?.takeIf { it > 0f } ?: AiChatInferenceUtils.calculateTps(streamTokens, finalDuration)
                val finalTokens = lastMetrics?.tokenCount ?: streamTokens

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = effectiveFinalContent,
                    thoughtContent = thought,
                    rawNakedOutput = finalOutput,
                    systemPrompt = systemPrompt,
                    actionPayloadJson = executedActionName,
                    actionStatus = if (executedActionName != null) "success" else null,
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces).takeIf { stepTraces.isNotEmpty() },
                    sourcesJson = ChatMessageEntity.serializeSources(collectedSources).takeIf { collectedSources.isNotEmpty() },
                    tokensPerSecond = finalTps,
                    totalTokens = finalTokens,
                    durationMs = finalDuration
                )
                AppLogger.d(TAG, "Completed chat turn for session=$sessionId: length=${finalOutput.length}, speed=$finalTps tok/s")

                AiChatInferenceUtils.recordInteractionAndCompact(
                    context = context,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    finalOutput = finalOutput,
                    effectiveFinalContent = effectiveFinalContent,
                    thought = thought,
                    finalTps = finalTps,
                    finalTokens = finalTokens,
                    finalDuration = finalDuration,
                    chatRepository = chatRepository,
                    sessionId = sessionId,
                    engine = engine,
                    activeSession = activeSession
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                AppLogger.i(TAG, "Chat turn cancelled: ${ce.message}")
                com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                agentEngine?.cancelRunningTask()
                AiChatInferenceUtils.interruptStepTraces(stepTraces)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Task stopped by user.",
                    actionStatus = "interrupted",
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(stepTraces).takeIf { stepTraces.isNotEmpty() }
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
