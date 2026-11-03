package com.textmorph.app.agent.orchestration

import android.content.Context
import com.textmorph.app.agent.artifacts.ArtifactAssemblyStore
import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.tools.web.WebTools
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates high-depth research workflows tailored to on-device SLM capabilities.
 *
 * Pre-fetches live web search findings on Dispatchers.IO, stages grounded facts to disk,
 * and feeds structured evidence to concurrent workers in BoundedWorkerPool so 1B–2B models
 * can synthesize factual sections without getting trapped in multi-turn tool loops.
 */
object DeepSearchCoordinator {

    private const val TAG = "DeepSearchCoordinator"

    /**
     * Determines if a query is underspecified (e.g. 1–3 words, person name with no domain, or vague keyword).
     */
    fun isAmbiguousQuery(query: String): Boolean {
        val trimmed = query.trim()
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 2 && !trimmed.contains("(") && !trimmed.contains(":") && !trimmed.contains(",")) {
            return true
        }
        val domainKeywords = listOf("ai", "tech", "science", "history", "art", "code", "software", "market", "finance", "medical", "biography", "guide", "overview")
        val lower = trimmed.lowercase()
        return words.size <= 3 && domainKeywords.none { lower.contains(it) }
    }

    /**
     * Generates a structured human-in-the-loop approval call for underspecified topics.
     */
    fun buildClarificationApprovalCall(topic: String): String {
        return """request_user_approval(question="What domain or focus should we research for '$topic'?", options=["AI & Software Engineering", "Academic & Science", "Creative & Arts", "General Biography & Career"])"""
    }

    /**
     * Pre-gathers live web search results for the goal and each planned section on Dispatchers.IO.
     * Persists all findings to a disk staging cache (deepsearch_context_$sessionId.txt) and returns
     * a mapping of section index to factual research summary.
     */
    suspend fun preFetchResearch(
        goal: String,
        sections: List<PlanSection>,
        cacheDir: File,
        sessionId: String,
        onProgress: (String) -> Unit
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        val safeSessionId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val stagingFile = File(cacheDir, "deepsearch_context_$safeSessionId.txt")
        val resultsMap = mutableMapOf<Int, String>()
        val fullReportBuffer = StringBuilder()

        try {
            onProgress("Initiating web discovery for: '$goal'…")
            AppLogger.i(TAG, "[$sessionId] Pre-fetching general context for: $goal")

            // 1. General topic search
            val generalResults = runCatching { WebTools.searchWeb(goal, maxResults = 5) }.getOrDefault(emptyList())
            val generalContext = if (generalResults.isNotEmpty()) {
                generalResults.joinToString("\n\n") { item ->
                    "### ${item.title}\n${item.snippet}\nSource: ${item.url}"
                }
            } else {
                "Topic: $goal"
            }
            fullReportBuffer.append("# Grounded Research: $goal\n\n$generalContext\n\n")

            // 2. Concurrent section-specific search passes
            coroutineScope {
                val deferredSearches = sections.map { section ->
                    async(Dispatchers.IO) {
                        val query = "$goal ${section.title}"
                        AppLogger.d(TAG, "[$sessionId] Pre-fetching section ${section.index}: $query")
                        val hits = runCatching { WebTools.searchWeb(query, maxResults = 4) }.getOrDefault(emptyList())
                        val sectionFacts = if (hits.isNotEmpty()) {
                            hits.joinToString("\n\n") { hit ->
                                "- **${hit.title}**: ${hit.snippet} ([Link](${hit.url}))"
                            }
                        } else {
                            generalContext
                        }
                        section.index to sectionFacts
                    }
                }

                val completed = deferredSearches.awaitAll()
                for ((idx, facts) in completed) {
                    resultsMap[idx] = facts
                    val sectionTitle = sections.firstOrNull { it.index == idx }?.title ?: "Section $idx"
                    fullReportBuffer.append("## Section $idx: $sectionTitle\n$facts\n\n")
                }
            }

            // 3. Stage to disk file
            try {
                stagingFile.writeText(fullReportBuffer.toString())
                AppLogger.i(TAG, "[$sessionId] Staged grounded research (${fullReportBuffer.length} chars) to ${stagingFile.name}")
            } catch (t: Throwable) {
                AppLogger.w(TAG, "[$sessionId] Failed writing deepsearch staging file: ${t.message}")
            }

        } catch (t: Throwable) {
            AppLogger.e(TAG, "[$sessionId] Error during preFetchResearch: ${t.message}", t)
        }

        return@withContext resultsMap
    }

    /**
     * Decomposes a research topic into 3–5 distinct research angles for DeepSearch.
     */
    suspend fun generateSearchPlan(
        query: String,
        engine: LlmEngine,
        context: Context? = null
    ): List<PlanSection> {
        val planEngine = DeepPlanEngine(context, engine)
        val sections = planEngine.generateExecutionPlan(query)
        // Ensure all DeepSearch sections are typed as Research Scout
        return sections.map { sec ->
            sec.copy(role = "Research Scout")
        }
    }

    /**
     * Formats planned research angles into Markdown.
     */
    fun formatSearchPlanMarkdown(query: String, sections: List<PlanSection>, version: Int = 1): String {
        val sb = StringBuilder()
        sb.append("### 🔍 Proposed Deep Search Plan (v").append(version).append(")\n")
        sb.append("**Research Topic:** ").append(query).append("\n\n")
        sb.append("| Angle | Research Domain | Worker Role | Investigation Focus |\n")
        sb.append("| :--- | :--- | :--- | :--- |\n")
        for (sec in sections) {
            val paddedIdx = if (sec.index < 10) "0${sec.index}" else "${sec.index}"
            val desc = sec.rationale.ifBlank { sec.queryOrGoal }
            sb.append("| **").append(paddedIdx).append("** | ").append(sec.title).append(" | `Research Scout` | ").append(desc).append(" |\n")
        }
        sb.append("\n> Review the proposed research plan above. Click **Execute Research** to launch concurrent research agents across the web, or **Edit Plan** to refine the search angles.")
        return sb.toString()
    }

    /**
     * Revises search angles based on user feedback.
     */
    suspend fun reviseSearchPlan(
        query: String,
        currentSections: List<PlanSection>,
        userFeedback: String,
        engine: LlmEngine,
        context: Context? = null
    ): List<PlanSection> {
        val planEngine = DeepPlanEngine(context, engine)
        val revised = planEngine.revisePlan(query, currentSections, userFeedback)
        return revised.map { sec -> sec.copy(role = "Research Scout") }
    }

    /**
     * Executes the grounded DeepSearch workflow with already approved research sections.
     */
    suspend fun executeApprovedSearch(
        goal: String,
        sections: List<PlanSection>,
        sessionId: String,
        engine: LlmEngine,
        context: Context?,
        workerCount: Int? = null,
        onProgress: (statusMessage: String) -> Unit
    ): DeepPlanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val effectiveWorkers = (workerCount ?: BoundedWorkerPool.DEFAULT_CONCURRENT_WORKERS).coerceIn(
            BoundedWorkerPool.MIN_WORKERS,
            BoundedWorkerPool.MAX_WORKERS
        )

        AppLogger.i(TAG, "[$sessionId] Executing approved deep search for: '$goal' across ${sections.size} angles (workers: $effectiveWorkers)")
        val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val store = ArtifactAssemblyStore(cacheDir, sessionId)
        store.initSession(goal, sections)

        // Pre-fetch research on Dispatchers.IO
        onProgress("Gathering live web sources and factual context across angles…")
        val factsMap = preFetchResearch(goal, sections, cacheDir, sessionId, onProgress)

        // Execute workers with grounded facts
        val pool = BoundedWorkerPool(effectiveWorkers)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)

        pool.executeAll(
            planGoal = goal,
            sections = sections,
            engine = engine,
            context = context,
            store = store,
            groundedFacts = factsMap,
            onProgress = { index, statusMsg ->
                val section = sections.firstOrNull { it.index == index }
                val title = section?.title ?: "Section $index"
                if (statusMsg.startsWith("Completed")) {
                    val done = completedCount.incrementAndGet()
                    onProgress("[$done/${sections.size}] Completed '$title'")
                } else {
                    onProgress(statusMsg)
                }
            }
        )

        // Compile final artifact
        onProgress("Assembling and formatting comprehensive report…")
        val finalArtifact = store.compileFinalArtifact()
        val duration = System.currentTimeMillis() - startTime
        val finalSections = store.getAllSections()

        AppLogger.i(TAG, "[$sessionId] DeepSearch completed in ${duration}ms (${finalArtifact.length} chars).")
        return@withContext DeepPlanResult(
            goal = goal,
            artifactContent = finalArtifact,
            sections = finalSections,
            workerCount = effectiveWorkers,
            durationMs = duration
        )
    }

    /**
     * Legacy one-pass workflow.
     */
    suspend fun execute(
        goal: String,
        sessionId: String,
        engine: LlmEngine,
        context: Context?,
        workerCount: Int? = null,
        onProgress: (statusMessage: String) -> Unit
    ): DeepPlanResult {
        val sections = generateSearchPlan(goal, engine, context)
        return executeApprovedSearch(goal, sections, sessionId, engine, context, workerCount, onProgress)
    }
}
