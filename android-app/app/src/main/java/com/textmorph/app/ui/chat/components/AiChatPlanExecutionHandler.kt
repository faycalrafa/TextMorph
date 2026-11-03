package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.orchestration.DeepPlanEngine
import com.textmorph.app.agent.orchestration.DeepPlanResult
import com.textmorph.app.agent.router.CommandRouteResult
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.data.model.ArtifactMetadata
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the Two-Stage Plan-and-Execute lifecycle for /plan commands:
 * Stage 1: Decomposes tasks into structured, modular steps for user review (with Edit & Execute actions).
 * Stage 2: Executes approved steps concurrently across bounded multi-agent workers and compiles deliverables.
 */
object AiChatPlanExecutionHandler {

    private const val TAG = "AiChatPlanExecutionHandler"

    data class CachedPlan(
        val goal: String,
        val sections: List<PlanSection>,
        val version: Int = 1,
        val workerCount: Int? = null
    )

    private val planCache = ConcurrentHashMap<String, CachedPlan>()

    fun handlePlanCommand(
        scope: CoroutineScope,
        sessionId: String,
        command: CommandRouteResult.PlanCommand,
        userPrompt: String,
        engine: LlmEngine,
        chatRepository: ChatSessionRepository,
        context: Context,
        onComplete: () -> Unit
    ) {
        when {
            command.isExecute -> executeApprovedPlan(
                scope = scope, sessionId = sessionId, goal = command.goal,
                workerCount = command.workersOverride, userPrompt = userPrompt,
                engine = engine, chatRepository = chatRepository,
                context = context, onComplete = onComplete
            )
            command.isEdit -> handlePlanEdit(
                scope = scope, sessionId = sessionId,
                editFeedback = command.editFeedback ?: "", userPrompt = userPrompt,
                engine = engine, chatRepository = chatRepository,
                context = context, onComplete = onComplete
            )
            else -> generatePlan(
                scope = scope, sessionId = sessionId, goal = command.goal,
                workerCount = command.workersOverride, userPrompt = userPrompt,
                engine = engine, chatRepository = chatRepository,
                context = context, onComplete = onComplete
            )
        }
    }

    fun generatePlan(
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
            val targetGoal = goal.ifBlank { "System Task" }
            appendUserAndPlaceholder(chatRepository, sessionId, userPrompt, "Planning execution steps…")

            val traces = mutableListOf(createInitialTrace("Task Planner", "Decomposing goal into discrete worker steps"))
            updateTraces(chatRepository, sessionId, traces)

            try {
                val planEngine = DeepPlanEngine(context, engine)
                val sections = planEngine.generateExecutionPlan(targetGoal)
                planCache[sessionId] = CachedPlan(targetGoal, sections, version = 1, workerCount = workerCount)

                completeTrace(traces.first(), "Planned ${sections.size} steps")
                chatRepository.setGenerationStatus(null)

                val planMarkdown = planEngine.formatPlanMarkdown(targetGoal, sections, version = 1)
                val artifact = ArtifactMetadata(
                    title = targetGoal.take(60),
                    wordCount = planMarkdown.split(Regex("\\s+")).size,
                    sectionCount = sections.size,
                    content = planMarkdown,
                    id = UUID.randomUUID().toString(),
                    type = "execution_plan",
                    summary = "Proposed execution plan with ${sections.size} steps ready for review.",
                    createdAtMs = System.currentTimeMillis()
                )

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "I have drafted a step-by-step execution plan. Review the steps below and click **Execute Plan** to launch multi-agent workers, or **Edit Plan** to refine the steps:",
                    artifactPayloadJson = ChatMessageEntity.serializeArtifact(artifact),
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                )
            } catch (t: Throwable) {
                handleError(t, chatRepository, sessionId, traces)
            } finally {
                onComplete()
            }
        }
    }

    fun handlePlanEdit(
        scope: CoroutineScope,
        sessionId: String,
        editFeedback: String,
        userPrompt: String,
        engine: LlmEngine,
        chatRepository: ChatSessionRepository,
        context: Context,
        onComplete: () -> Unit
    ) {
        scope.launch(Dispatchers.Main) {
            appendUserAndPlaceholder(chatRepository, sessionId, userPrompt, "Revising execution plan…")
            val traces = mutableListOf(createInitialTrace("Plan Revisor", "Adjusting steps according to user feedback"))
            updateTraces(chatRepository, sessionId, traces)

            try {
                val cached = planCache[sessionId] ?: CachedPlan("System Task", DeepPlanEngine(context, engine).generateDefaultSections("System Task"))
                val planEngine = DeepPlanEngine(context, engine)
                val revisedSections = planEngine.revisePlan(cached.goal, cached.sections, editFeedback)
                val newVersion = cached.version + 1
                planCache[sessionId] = cached.copy(sections = revisedSections, version = newVersion)

                completeTrace(traces.first(), "Updated plan to v$newVersion (${revisedSections.size} steps)")
                chatRepository.setGenerationStatus(null)

                val planMarkdown = planEngine.formatPlanMarkdown(cached.goal, revisedSections, version = newVersion)
                val artifact = ArtifactMetadata(
                    title = cached.goal.take(60),
                    wordCount = planMarkdown.split(Regex("\\s+")).size,
                    sectionCount = revisedSections.size,
                    content = planMarkdown,
                    id = UUID.randomUUID().toString(),
                    type = "execution_plan",
                    summary = "Revised execution plan (v$newVersion) with ${revisedSections.size} steps ready for review.",
                    createdAtMs = System.currentTimeMillis()
                )

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Execution plan updated (v$newVersion). Review the revised steps below and click **Execute Plan** to proceed:",
                    artifactPayloadJson = ChatMessageEntity.serializeArtifact(artifact),
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                )
            } catch (t: Throwable) {
                handleError(t, chatRepository, sessionId, traces)
            } finally {
                onComplete()
            }
        }
    }

    fun executeApprovedPlan(
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
            val cached = planCache[sessionId]
            val targetGoal = goal.ifBlank { cached?.goal ?: "Execution Plan" }
            val planEngine = DeepPlanEngine(context, engine)
            val sections = cached?.sections ?: planEngine.generateDefaultSections(targetGoal)
            val effectiveWorkers = workerCount ?: cached?.workerCount

            appendUserAndPlaceholder(chatRepository, sessionId, userPrompt, "Spawning worker agents…")
            val traces = mutableListOf<AgentStepTrace>()

            val onProgressCallback: (String) -> Unit = { statusMsg ->
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

            try {
                val result = planEngine.executeApprovedPlan(
                    goal = targetGoal,
                    sections = sections,
                    sessionId = sessionId,
                    workerOverride = effectiveWorkers,
                    onProgress = onProgressCallback
                )

                finishTraces(traces)
                chatRepository.setGenerationStatus(null)

                val artifact = ArtifactMetadata(
                    title = targetGoal.take(60),
                    wordCount = (result.artifactContent.length / 5).coerceAtLeast(1),
                    sectionCount = result.sections.size.coerceAtLeast(1),
                    content = result.artifactContent,
                    id = UUID.randomUUID().toString(),
                    type = "deliverable",
                    summary = result.artifactContent.lines().filter { it.isNotBlank() }.take(2).joinToString(" ").take(160),
                    createdAtMs = System.currentTimeMillis()
                )

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Plan executed successfully! All worker tasks completed. The compiled deliverable is presented below:",
                    artifactPayloadJson = ChatMessageEntity.serializeArtifact(artifact),
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces),
                    totalTokens = (result.artifactContent.length / 4).coerceAtLeast(1),
                    durationMs = result.durationMs
                )
            } catch (t: Throwable) {
                handleError(t, chatRepository, sessionId, traces)
            } finally {
                onComplete()
            }
        }
    }

    /**
     * Legacy single-pass execution.
     */
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
        val isPlan = userPrompt.trim().startsWith("/plan", ignoreCase = true)
        if (isPlan) {
            generatePlan(scope, sessionId, goal, workerCount, userPrompt, engine, chatRepository, context, onComplete)
        } else {
            AiChatDeepSearchHandler.generateSearchPlan(scope, sessionId, goal, workerCount, userPrompt, engine, chatRepository, context, onComplete)
        }
    }

    private suspend fun appendUserAndPlaceholder(repo: ChatSessionRepository, sessionId: String, userText: String, status: String) {
        repo.appendMessage(ChatMessageEntity(sessionId = sessionId, role = "user", content = userText, isAgentMode = true))
        repo.appendMessage(ChatMessageEntity(sessionId = sessionId, role = "assistant", content = "", isAgentMode = true))
        repo.setGenerationStatus(status)
    }

    private fun createInitialTrace(tool: String, query: String) = AgentStepTrace(
        stepIndex = 1,
        toolName = tool,
        inputQuery = query,
        status = "running",
        startTimeMs = System.currentTimeMillis()
    )

    private fun completeTrace(trace: AgentStepTrace, output: String) {
        trace.status = "success"
        trace.outputSnippet = output
        trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
    }

    private fun finishTraces(traces: List<AgentStepTrace>) {
        val now = System.currentTimeMillis()
        for (trace in traces) {
            if (trace.status == "running") {
                trace.status = "success"
                trace.durationMs = now - trace.startTimeMs
            }
        }
    }

    private suspend fun updateTraces(repo: ChatSessionRepository, sessionId: String, traces: List<AgentStepTrace>) {
        repo.updateLastAssistantMessage(sessionId = sessionId, content = "", stepTracesJson = ChatMessageEntity.serializeStepTraces(traces))
    }

    private suspend fun handleError(t: Throwable, repo: ChatSessionRepository, sessionId: String, traces: List<AgentStepTrace>) {
        val isCancelled = t is CancellationException
        if (isCancelled) {
            AppLogger.i(TAG, "Task cancelled by user.")
        } else {
            AppLogger.e(TAG, "Task error: ${t.message}", t)
        }
        for (trace in traces) {
            if (trace.status == "running") {
                trace.status = if (isCancelled) "cancelled" else "failed"
                trace.failureReason = t.message ?: "Unknown error"
                trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
            }
        }
        repo.setGenerationStatus(null)
        repo.updateLastAssistantMessage(
            sessionId = sessionId,
            content = if (isCancelled) "Task cancelled by user." else "Execution encountered an error: ${t.message ?: "Unknown error"}",
            stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
        )
    }

    private fun handlePlanProgress(statusMsg: String, traces: MutableList<AgentStepTrace>) {
        val now = System.currentTimeMillis()
        if (statusMsg.startsWith("Planned")) {
            traces.firstOrNull { it.status == "running" }?.let {
                it.status = "success"
                it.outputSnippet = statusMsg
                it.durationMs = now - it.startTimeMs
            }
        } else if (statusMsg.contains("Completed '")) {
            val title = statusMsg.substringAfter("Completed '").substringBefore("'")
            val running = traces.firstOrNull { it.inputQuery.contains(title) && it.status == "running" }
            if (running != null) {
                running.status = "success"
                running.outputSnippet = "Task complete"
                running.durationMs = now - running.startTimeMs
            } else {
                traces.add(AgentStepTrace(stepIndex = traces.size + 1, toolName = "Worker Agent", inputQuery = title, status = "success", outputSnippet = "Task complete", startTimeMs = now - 1000, durationMs = 1000))
            }
        } else if (statusMsg.contains("Spawning") || statusMsg.contains("Drafting")) {
            val queryText = statusMsg.take(50)
            if (traces.none { it.inputQuery == queryText }) {
                traces.add(AgentStepTrace(stepIndex = traces.size + 1, toolName = "Worker Agent", inputQuery = queryText, status = "running", startTimeMs = now))
            }
        }
    }
}
