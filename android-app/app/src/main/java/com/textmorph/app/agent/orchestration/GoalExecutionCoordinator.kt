package com.textmorph.app.agent.orchestration

import android.content.Context
import com.textmorph.app.agent.AgentEngine
import com.textmorph.app.agent.guardrails.ExecutionGuardrail
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.context.ChatCompactor
import com.textmorph.app.ui.chat.components.AiChatInferenceUtils
import com.textmorph.app.ui.chat.components.ChatToolExecutionHandler
import com.textmorph.app.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Continuous autonomous agent loop for /goal <objective>.
 * Iterates autonomously across web research, JS calculations, and device tasks
 * until objective verification succeeds or user halts execution.
 */
class GoalExecutionCoordinator(
    private val context: Context,
    private val engine: LlmEngine,
    private val chatRepository: ChatSessionRepository,
    private val agentEngine: AgentEngine?
) {
    private val TAG = "GoalCoordinator"
    private val isInterrupted = AtomicBoolean(false)
    private var activeJob: Job? = null

    fun interrupt() {
        isInterrupted.set(true)
        activeJob?.cancel()
        agentEngine?.cancelRunningTask()
    }

    fun executeGoal(
        scope: CoroutineScope,
        sessionId: String,
        goal: String,
        userPrompt: String,
        onComplete: () -> Unit
    ) {
        isInterrupted.set(false)
        activeJob = scope.launch(Dispatchers.Main) {
            val userMsg = ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                content = userPrompt,
                isAgentMode = true
            )
            val assistantPlaceholder = ChatMessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = "Initializing autonomous goal runner for: \"$goal\"…",
                isAgentMode = true
            )
            chatRepository.appendMessage(userMsg)
            chatRepository.appendMessage(assistantPlaceholder)
            chatRepository.setGenerationStatus("Starting autonomous /goal loop…")

            val traces = mutableListOf<AgentStepTrace>()
            val guardrail = ExecutionGuardrail(maxHops = 10)
            var currentContent = ""
            var hop = 0
            val maxHops = 10

            val goalSystemPrompt = """
                You are TextMorph Goal Runner, an autonomous task agent.
                Your OBJECTIVE is: "$goal"
                
                You can execute tools to make progress. Available tools include:
                - search_web(query="...")
                - fetch_page(url="...")
                - search_images(query="...")
                - open_app("...")
                - done(summary="...")
                
                RULES:
                1. Call exactly one tool per turn via Code-as-Action.
                2. When you receive tool observations, evaluate if the objective is 100% achieved.
                3. If more information or actions are needed, take the next logical step.
                4. When the goal is completely fulfilled, call done(summary="your final detailed response to the user").
            """.trimIndent()

            try {
                var observationText: String? = null
                val turnHistory = mutableListOf<ChatMessageEntity>()

                while (hop < maxHops && !isInterrupted.get()) {
                    val stepIndex = hop + 1
                    val promptText = if (observationText == null) {
                        "OBJECTIVE: $goal\nBegin execution of step 1."
                    } else {
                        observationText
                    }

                    val fullInferencePrompt = ChatCompactor.assembleInferencePrompt(
                        systemPrompt = goalSystemPrompt,
                        sessionSummary = null,
                        sessionState = "AUTONOMOUS GOAL: $goal (Step $stepIndex/$maxHops)",
                        historyMessages = turnHistory,
                        currentPrompt = promptText,
                        documentText = null,
                        maxContextTokens = engine.maxContextTokens
                    )

                    val activeTrace = AgentStepTrace(
                        stepIndex = stepIndex,
                        toolName = "Planning next action…",
                        status = "running"
                    )
                    traces.add(activeTrace)
                    chatRepository.updateLastAssistantMessage(
                        sessionId = sessionId,
                        content = currentContent,
                        stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                    )

                    val sb = StringBuilder()
                    val stepStart = System.currentTimeMillis()
                    val flow = AiChatInferenceUtils.streamFromEngine(engine, fullInferencePrompt, null)

                    flow.collect { chunk ->
                        if (isInterrupted.get()) throw CancellationException("Goal halted by user.")
                        sb.append(chunk)
                    }

                    val stepDuration = System.currentTimeMillis() - stepStart
                    val modelOutput = sb.toString().trim()
                    val (thought, cleanOutput) = AiChatInferenceUtils.extractThought(modelOutput)
                    val effectiveOutput = cleanOutput.ifBlank { modelOutput }

                    val action = CodeActionParser.parse(effectiveOutput)
                    if (action == null || action.action == AgentAction.ACTION_DONE) {
                        // Concluded
                        val finalSummary = action?.getStringParamWithAliases("summary", "response")?.ifBlank { effectiveOutput } ?: effectiveOutput
                        traces[traces.lastIndex] = activeTrace.copy(
                            toolName = "done",
                            status = "success",
                            durationMs = stepDuration,
                            outputSnippet = "Goal completed."
                        )
                        currentContent = finalSummary
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = currentContent,
                            thoughtContent = thought,
                            stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                        )
                        break
                    }

                    // Check guardrail
                    val guardrailDecision = guardrail.evaluate(action.action, action.params)
                    if (!guardrailDecision.allowed) {
                        traces[traces.lastIndex] = activeTrace.copy(
                            toolName = action.action,
                            status = "failed",
                            durationMs = stepDuration,
                            failureReason = "Loop detected: Duplicate action with arguments ${action.params}"
                        )
                        observationText = "[Notice]: Repeated tool call detected. Summarize and complete your goal immediately."
                        hop++
                        continue
                    }

                    // Execute tool
                    traces[traces.lastIndex] = activeTrace.copy(
                        toolName = action.action,
                        inputQuery = action.getStringParamWithAliases("query", "url", "goal", "text"),
                        status = "running"
                    )
                    chatRepository.setGenerationStatus("Goal Step $stepIndex: Executing ${action.action}…")
                    chatRepository.updateLastAssistantMessage(
                        sessionId = sessionId,
                        content = currentContent,
                        stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                    )

                    val (success, output) = withContext(Dispatchers.IO) {
                        if (ChatToolExecutionHandler.isInformationTool(action, context)) {
                            ChatToolExecutionHandler.executeTool(action, context)
                        } else {
                            Pair(true, "Action ${action.action} dispatched.")
                        }
                    }

                    traces[traces.lastIndex] = traces.last().copy(
                        status = if (success) "success" else "failed",
                        durationMs = System.currentTimeMillis() - stepStart,
                        outputSnippet = output.take(200)
                    )
                    chatRepository.updateLastAssistantMessage(
                        sessionId = sessionId,
                        content = currentContent,
                        stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                    )

                    turnHistory.add(ChatMessageEntity(sessionId = sessionId, role = "assistant", content = effectiveOutput))
                    turnHistory.add(ChatMessageEntity(sessionId = sessionId, role = "user", content = "[Observation for ${action.action}]:\n$output"))
                    observationText = "Observation from ${action.action}:\n$output\n\nEvaluate if goal is achieved or invoke next tool."
                    hop++
                }

                chatRepository.setGenerationStatus(null)
            } catch (ce: CancellationException) {
                AppLogger.i(TAG, "Goal execution cancelled: ${ce.message}")
                val lastIdx = traces.lastIndex
                if (lastIdx >= 0 && traces[lastIdx].status == "running") {
                    traces[lastIdx] = traces[lastIdx].copy(status = "cancelled", failureReason = "Stopped by user")
                }
                chatRepository.setGenerationStatus(null)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = if (currentContent.isNotBlank()) currentContent else "Goal stopped by user.",
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                )
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Goal execution encountered error: ${t.message}", t)
                val lastIdx = traces.lastIndex
                if (lastIdx >= 0) {
                    traces[lastIdx] = traces[lastIdx].copy(status = "failed", failureReason = t.message)
                }
                chatRepository.setGenerationStatus(null)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Goal runner encountered an issue: ${t.message ?: "Unknown error"}",
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                )
            } finally {
                onComplete()
            }
        }
    }
}
