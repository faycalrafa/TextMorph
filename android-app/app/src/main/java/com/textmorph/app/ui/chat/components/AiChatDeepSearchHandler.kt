package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.orchestration.DeepSearchCoordinator
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
 * Manages the Two-Stage DeepSearch lifecycle:
 * Stage 1: Formulates search angles and investigation questions for user review (with Edit & Execute).
 * Stage 2: Executes parallel web discovery across angles, gathers facts to disk, and synthesizes cited reports.
 */
object AiChatDeepSearchHandler {

    private const val TAG = "AiChatDeepSearchHandler"

    data class CachedSearchPlan(
        val query: String,
        val sections: List<PlanSection>,
        val version: Int = 1,
        val workerCount: Int? = null
    )

    private val searchCache = ConcurrentHashMap<String, CachedSearchPlan>()

    fun handleDeepSearchCommand(
        scope: CoroutineScope,
        sessionId: String,
        command: CommandRouteResult.DeepSearchCommand,
        userPrompt: String,
        engine: LlmEngine,
        chatRepository: ChatSessionRepository,
        context: Context,
        onComplete: () -> Unit
    ) {
        when {
            command.isExecute -> executeApprovedSearch(
                scope = scope, sessionId = sessionId, query = command.query,
                workerCount = command.workersOverride, userPrompt = userPrompt,
                engine = engine, chatRepository = chatRepository,
                context = context, onComplete = onComplete
            )
            command.isEdit -> handleSearchPlanEdit(
                scope = scope, sessionId = sessionId,
                editFeedback = command.editFeedback ?: "", userPrompt = userPrompt,
                engine = engine, chatRepository = chatRepository,
                context = context, onComplete = onComplete
            )
            else -> generateSearchPlan(
                scope = scope, sessionId = sessionId, query = command.query,
                workerCount = command.workersOverride, userPrompt = userPrompt,
                engine = engine, chatRepository = chatRepository,
                context = context, onComplete = onComplete
            )
        }
    }

    fun generateSearchPlan(
        scope: CoroutineScope,
        sessionId: String,
        query: String,
        workerCount: Int?,
        userPrompt: String,
        engine: LlmEngine,
        chatRepository: ChatSessionRepository,
        context: Context,
        onComplete: () -> Unit
    ) {
        scope.launch(Dispatchers.Main) {
            val targetQuery = query.ifBlank { "Deep Research" }

            // HITL Clarification Check for ambiguous queries
            if (DeepSearchCoordinator.isAmbiguousQuery(targetQuery)) {
                val approvalCall = DeepSearchCoordinator.buildClarificationApprovalCall(targetQuery)
                chatRepository.appendMessage(ChatMessageEntity(sessionId = sessionId, role = "user", content = userPrompt, isAgentMode = true))
                chatRepository.appendMessage(ChatMessageEntity(sessionId = sessionId, role = "assistant", content = approvalCall, isAgentMode = true))
                onComplete()
                return@launch
            }

            appendUserAndPlaceholder(chatRepository, sessionId, userPrompt, "Formulating research angles…")

            val traces = mutableListOf(createInitialTrace("Research Planner", "Decomposing topic into investigation angles"))
            updateTraces(chatRepository, sessionId, traces)

            try {
                val sections = DeepSearchCoordinator.generateSearchPlan(targetQuery, engine, context)
                searchCache[sessionId] = CachedSearchPlan(targetQuery, sections, version = 1, workerCount = workerCount)

                completeTrace(traces.first(), "Formulated ${sections.size} research angles")
                chatRepository.setGenerationStatus(null)

                val planMarkdown = DeepSearchCoordinator.formatSearchPlanMarkdown(targetQuery, sections, version = 1)
                val artifact = ArtifactMetadata(
                    title = targetQuery.take(60),
                    wordCount = planMarkdown.split(Regex("\\s+")).size,
                    sectionCount = sections.size,
                    content = planMarkdown,
                    id = UUID.randomUUID().toString(),
                    type = "search_plan",
                    summary = "Proposed deep search plan with ${sections.size} research angles ready for review.",
                    createdAtMs = System.currentTimeMillis()
                )

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "I have formulated a multi-angle research plan. Review the search angles below and click **Execute Research** to launch concurrent web agents, or **Edit Plan** to refine the focus:",
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

    fun handleSearchPlanEdit(
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
            appendUserAndPlaceholder(chatRepository, sessionId, userPrompt, "Revising research angles…")
            val traces = mutableListOf(createInitialTrace("Research Revisor", "Adjusting research angles according to user feedback"))
            updateTraces(chatRepository, sessionId, traces)

            try {
                val cached = searchCache[sessionId] ?: CachedSearchPlan("Deep Research", DeepSearchCoordinator.generateSearchPlan("Deep Research", engine, context))
                val revisedSections = DeepSearchCoordinator.reviseSearchPlan(cached.query, cached.sections, editFeedback, engine, context)
                val newVersion = cached.version + 1
                searchCache[sessionId] = cached.copy(sections = revisedSections, version = newVersion)

                completeTrace(traces.first(), "Updated search plan to v$newVersion (${revisedSections.size} angles)")
                chatRepository.setGenerationStatus(null)

                val planMarkdown = DeepSearchCoordinator.formatSearchPlanMarkdown(cached.query, revisedSections, version = newVersion)
                val artifact = ArtifactMetadata(
                    title = cached.query.take(60),
                    wordCount = planMarkdown.split(Regex("\\s+")).size,
                    sectionCount = revisedSections.size,
                    content = planMarkdown,
                    id = UUID.randomUUID().toString(),
                    type = "search_plan",
                    summary = "Revised deep search plan (v$newVersion) with ${revisedSections.size} angles ready for review.",
                    createdAtMs = System.currentTimeMillis()
                )

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Deep search plan updated (v$newVersion). Review the revised angles below and click **Execute Research** to proceed:",
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

    fun executeApprovedSearch(
        scope: CoroutineScope,
        sessionId: String,
        query: String,
        workerCount: Int?,
        userPrompt: String,
        engine: LlmEngine,
        chatRepository: ChatSessionRepository,
        context: Context,
        onComplete: () -> Unit
    ) {
        scope.launch(Dispatchers.Main) {
            val cached = searchCache[sessionId]
            val targetQuery = query.ifBlank { cached?.query ?: "Deep Research" }
            val sections = cached?.sections ?: DeepSearchCoordinator.generateSearchPlan(targetQuery, engine, context)
            val effectiveWorkers = workerCount ?: cached?.workerCount

            appendUserAndPlaceholder(chatRepository, sessionId, userPrompt, "Gathering sources across web…")
            val traces = mutableListOf<AgentStepTrace>()

            val onProgressCallback: (String) -> Unit = { statusMsg ->
                scope.launch(Dispatchers.Main) {
                    chatRepository.setGenerationStatus(statusMsg)
                    handleSearchProgress(statusMsg, traces)
                    chatRepository.updateLastAssistantMessage(
                        sessionId = sessionId,
                        content = "",
                        stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                    )
                }
            }

            try {
                val result = DeepSearchCoordinator.executeApprovedSearch(
                    goal = targetQuery,
                    sections = sections,
                    sessionId = sessionId,
                    engine = engine,
                    context = context,
                    workerCount = effectiveWorkers,
                    onProgress = onProgressCallback
                )

                finishTraces(traces)
                chatRepository.setGenerationStatus(null)

                val artifact = ArtifactMetadata(
                    title = targetQuery.take(60),
                    wordCount = (result.artifactContent.length / 5).coerceAtLeast(1),
                    sectionCount = result.sections.size.coerceAtLeast(1),
                    content = result.artifactContent,
                    id = UUID.randomUUID().toString(),
                    type = "report",
                    summary = result.artifactContent.lines().filter { it.isNotBlank() }.take(2).joinToString(" ").take(160),
                    createdAtMs = System.currentTimeMillis()
                )

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Deep research complete! Findings synthesized into the comprehensive cited report below:",
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
            AppLogger.i(TAG, "Search cancelled by user.")
        } else {
            AppLogger.e(TAG, "Search error: ${t.message}", t)
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
            content = if (isCancelled) "Search cancelled by user." else "Search encountered an error: ${t.message ?: "Unknown error"}",
            stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
        )
    }

    private fun handleSearchProgress(statusMsg: String, traces: MutableList<AgentStepTrace>) {
        val now = System.currentTimeMillis()
        if (statusMsg.startsWith("Formulated") || statusMsg.startsWith("Planned")) {
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
                running.outputSnippet = "Search complete"
                running.durationMs = now - running.startTimeMs
            } else {
                traces.add(AgentStepTrace(stepIndex = traces.size + 1, toolName = "Research Scout", inputQuery = title, status = "success", outputSnippet = "Search complete", startTimeMs = now - 1000, durationMs = 1000))
            }
        } else if (statusMsg.contains("Gathering") || statusMsg.contains("Drafting")) {
            val queryText = statusMsg.take(50)
            if (traces.none { it.inputQuery == queryText }) {
                traces.add(AgentStepTrace(stepIndex = traces.size + 1, toolName = "Research Scout", inputQuery = queryText, status = "running", startTimeMs = now))
            }
        }
    }
}
