package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.agent.orchestration.DeepPlanEngine
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Delegates the asynchronous execution of /plan and /deepsearch commands within
 * the unified chat interface, streaming structured step progress into StepTraceAccordion
 * and persisting assembled markdown artifacts.
 */
object AiChatPlanExecutionHandler {

    private const val TAG = "AiChatPlanExecutionHandler"

    fun executePlanOrDeepSearch(
        scope: CoroutineScope,
        sessionId: String,
        goal: String,
        workerCount: Int?,
        userPrompt: String,
        engine: LlmEngine,
        chatRepository: ChatSessionRepository,
        context: Context,
        onComplete: () -> Unit
    ) {
        scope.launch(Dispatchers.Main) {
            val isPlan = userPrompt.trim().startsWith("/plan", ignoreCase = true)
            val userMsg = ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                content = userPrompt,
                isAgentMode = true
            )
            val assistantPlaceholder = ChatMessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = "",
                isAgentMode = true
            )
            chatRepository.appendMessage(userMsg)
            chatRepository.appendMessage(assistantPlaceholder)
            chatRepository.setGenerationStatus(if (isPlan) "Planning decomposition…" else "Researching deep plan…")

            val traces = mutableListOf<AgentStepTrace>()
            val initialStep = AgentStepTrace(
                stepIndex = 1,
                toolName = "Planner Pass",
                inputQuery = "Decomposing task into structured sections",
                status = "running",
                startTimeMs = System.currentTimeMillis()
            )
            traces.add(initialStep)
            chatRepository.updateLastAssistantMessage(
                sessionId = sessionId,
                content = "",
                stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
            )

            try {
                val planEngine = DeepPlanEngine(context, engine)
                val result = planEngine.execute(
                    goal = goal.ifBlank { userPrompt },
                    sessionId = sessionId,
                    workerOverride = workerCount,
                    onProgress = { statusMsg ->
                        scope.launch(Dispatchers.Main) {
                            chatRepository.setGenerationStatus(statusMsg)
                            handlePlanProgress(statusMsg, traces)
                            chatRepository.updateLastAssistantMessage(
                                sessionId = sessionId,
                                content = "",
                                stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                            )
                        }
                    }
                )

                // Complete any open traces
                for (trace in traces) {
                    if (trace.status == "running") {
                        trace.status = "success"
                        trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
                    }
                }

                chatRepository.setGenerationStatus(null)
                val artifactType = if (isPlan) "plan" else "report"
                val artifactTitle = goal.ifBlank { userPrompt }.take(60)
                val artifactMetadata = com.textmorph.app.data.model.ArtifactMetadata(
                    title = artifactTitle,
                    wordCount = (result.artifactContent.length / 5).coerceAtLeast(1),
                    sectionCount = result.sections.size.coerceAtLeast(1),
                    content = result.artifactContent,
                    id = java.util.UUID.randomUUID().toString(),
                    type = artifactType,
                    summary = result.artifactContent.lines().filter { it.isNotBlank() }.take(2).joinToString(" ").take(160),
                    createdAtMs = System.currentTimeMillis()
                )
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = if (isPlan) "Plan generated successfully. Review the structured execution plan below:" else "Deep research completed. View the full report below:",
                    artifactPayloadJson = ChatMessageEntity.serializeArtifact(artifactMetadata),
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces),
                    totalTokens = (result.artifactContent.length / 4).coerceAtLeast(1),
                    durationMs = result.durationMs
                )
            } catch (ce: CancellationException) {
                AppLogger.i(TAG, "Deep plan execution cancelled.")
                for (trace in traces) {
                    if (trace.status == "running") {
                        trace.status = "cancelled"
                        trace.failureReason = "Cancelled by user"
                        trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
                    }
                }
                chatRepository.setGenerationStatus(null)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Task cancelled by user.",
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                )
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Deep plan execution encountered an error: ${t.message}", t)
                for (trace in traces) {
                    if (trace.status == "running") {
                        trace.status = "failed"
                        trace.failureReason = t.message ?: "Unknown error"
                        trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
                    }
                }
                chatRepository.setGenerationStatus(null)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Deep plan execution encountered an error: ${t.message ?: "Unknown error"}",
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                )
            } finally {
                onComplete()
            }
        }
    }

    private fun handlePlanProgress(statusMsg: String, traces: MutableList<AgentStepTrace>) {
        val now = System.currentTimeMillis()
        if (statusMsg.startsWith("Planned")) {
            // Planner pass complete
            traces.firstOrNull { it.toolName == "Planner Pass" && it.status == "running" }?.let {
                it.status = "success"
                it.outputSnippet = statusMsg
                it.durationMs = now - it.startTimeMs
            }
        } else if (statusMsg.contains("Completed '")) {
            val title = statusMsg.substringAfter("Completed '").substringBefore("'")
            val running = traces.firstOrNull { it.inputQuery.contains(title) && it.status == "running" }
            if (running != null) {
                running.status = "success"
                running.outputSnippet = "Research complete"
                running.durationMs = now - running.startTimeMs
            } else {
                traces.add(
                    AgentStepTrace(
                        stepIndex = traces.size + 1,
                        toolName = "Section Worker",
                        inputQuery = title,
                        status = "success",
                        outputSnippet = "Research complete",
                        startTimeMs = now - 1500,
                        durationMs = 1500
                    )
                )
            }
        } else if (statusMsg.startsWith("Researching '")) {
            val title = statusMsg.substringAfter("Researching '").substringBefore("'")
            if (traces.none { it.inputQuery.contains(title) }) {
                traces.add(
                    AgentStepTrace(
                        stepIndex = traces.size + 1,
                        toolName = "Section Worker",
                        inputQuery = "Researching '$title'",
                        status = "running",
                        startTimeMs = now
                    )
                )
            }
        } else if (statusMsg.contains("Assembling")) {
            for (trace in traces) {
                if (trace.status == "running") {
                    trace.status = "success"
                    trace.durationMs = now - trace.startTimeMs
                }
            }
            if (traces.none { it.toolName == "Assembly & Quality Gate" }) {
                traces.add(
                    AgentStepTrace(
                        stepIndex = traces.size + 1,
                        toolName = "Assembly & Quality Gate",
                        inputQuery = "Compiling and resolving document artifact",
                        status = "running",
                        startTimeMs = now
                    )
                )
            }
        }
    }
}
