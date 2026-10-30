package com.textmorph.app.agent.orchestration

import android.content.Context
import com.textmorph.app.agent.artifacts.ArtifactAssemblyStore
import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.artifacts.SectionStatus
import com.textmorph.app.llm.GpuInferenceGate
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Result model emitted after successful plan-execute-append completion.
 */
data class DeepPlanResult(
    val goal: String,
    val artifactContent: String,
    val sections: List<PlanSection>,
    val workerCount: Int,
    val durationMs: Long
)

/**
 * DeepPlanEngine orchestrates the complete Bounded Plan-Execute-Append pipeline.
 * It coordinates the Planner Pass (decomposing user requests into 3–6 structured sections),
 * dispatches them across a BoundedWorkerPool with two-tier GPU locking, and compiles the
 * order-preserving final artifact via ArtifactAssemblyStore.
 */
class DeepPlanEngine(
    private val context: Context? = null,
    private val engine: LlmEngine,
    val maxConcurrentWorkers: Int = BoundedWorkerPool.DEFAULT_CONCURRENT_WORKERS
) {

    private val TAG = "DeepPlanEngine"

    /**
     * Executes the end-to-end plan-execute-append workflow.
     */
    suspend fun execute(
        goal: String,
        sessionId: String,
        workerOverride: Int? = null,
        onProgress: (statusMessage: String) -> Unit
    ): DeepPlanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val effectiveWorkers = (workerOverride ?: maxConcurrentWorkers).coerceIn(
            BoundedWorkerPool.MIN_WORKERS,
            BoundedWorkerPool.MAX_WORKERS
        )

        AppLogger.i(TAG, "[$sessionId] Initiating deep plan for: '$goal' (workers: $effectiveWorkers)")
        onProgress("Decomposing research plan into structured sections…")

        // 1. Planner Pass: Decompose into 3–6 sections
        val sections = decomposeIntoPlan(goal)
        AppLogger.i(TAG, "[$sessionId] Planner produced ${sections.size} sections: ${sections.map { it.title }}")
        onProgress("Planned ${sections.size} sections. Spawning $effectiveWorkers concurrent workers…")

        // 2. Initialize Artifact Assembly Store with disk staging
        val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val store = ArtifactAssemblyStore(cacheDir, sessionId)
        store.initSession(goal, sections)

        // 3. Dispatch bounded workers
        val pool = BoundedWorkerPool(effectiveWorkers)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)

        pool.executeAll(
            planGoal = goal,
            sections = sections,
            engine = engine,
            context = context,
            store = store,
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

        // 4. Final Quality & Assembly
        onProgress("Assembling and formatting final artifact…")
        val finalArtifact = store.compileFinalArtifact()
        val duration = System.currentTimeMillis() - startTime
        val finalSections = store.getAllSections()

        AppLogger.i(TAG, "[$sessionId] Deep plan pipeline completed in ${duration}ms with ${finalArtifact.length} chars.")
        return@withContext DeepPlanResult(
            goal = goal,
            artifactContent = finalArtifact,
            sections = finalSections,
            workerCount = effectiveWorkers,
            durationMs = duration
        )
    }

    /**
     * Planner Pass: Prompts the LLM to decompose the goal into 3–5 structured sub-tasks.
     */
    suspend fun decomposeIntoPlan(goal: String): List<PlanSection> {
        val prompt = """
            Decompose this research or report topic into 3 to 5 distinct, sequential sections:
            Topic: "$goal"

            Respond with ONLY a numbered list in this format:
            1. [Section Title] - [Focus and key questions to cover]
            2. [Section Title] - [Focus and key questions to cover]
            3. [Section Title] - [Focus and key questions to cover]
        """.trimIndent()

        val rawOutput = try {
            GpuInferenceGate.withLock("PlannerPass") {
                engine.generate(prompt, "You are a master research planner. Output clean, numbered sections.")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Planner LLM pass failed: ${t.message}. Utilizing heuristic fallback.")
            ""
        }

        val parsed = parsePlanSections(rawOutput, goal)
        if (parsed.size >= 2) {
            return parsed
        }

        AppLogger.i(TAG, "LLM plan output parsing yielded < 2 sections. Generating default research structure.")
        return generateDefaultSections(goal)
    }

    /**
     * Parses numbered text lines into structured PlanSection models.
     */
    fun parsePlanSections(text: String, fallbackGoal: String): List<PlanSection> {
        if (text.isBlank()) return emptyList()

        val results = mutableListOf<PlanSection>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        val regex = Regex("^(\\d+)[.)\\s]+(.*?)(?:[-:–—]\\s*(.*))?$")
        for (line in lines) {
            val match = regex.find(line)
            if (match != null) {
                val index = match.groupValues[1].toIntOrNull() ?: (results.size + 1)
                val rawTitle = match.groupValues[2].trim().trim('*', '#', '`')
                val desc = match.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Research and draft comprehensive analysis on $rawTitle"

                if (rawTitle.length >= 3) {
                    results.add(
                        PlanSection(
                            index = index,
                            title = rawTitle,
                            queryOrGoal = desc,
                            status = SectionStatus.PENDING
                        )
                    )
                }
            }
        }

        // Renumber indices to 1..N strictly
        return results.mapIndexed { i, sec -> sec.copy(index = i + 1) }.take(6)
    }

    /**
     * Generates a robust default 4-section plan if planner LLM output was insufficient.
     */
    fun generateDefaultSections(goal: String): List<PlanSection> {
        return listOf(
            PlanSection(
                index = 1,
                title = "Overview & Core Background",
                queryOrGoal = "Provide fundamental context, history, and key definitions regarding $goal.",
                status = SectionStatus.PENDING
            ),
            PlanSection(
                index = 2,
                title = "In-Depth Analysis & Key Findings",
                queryOrGoal = "Detail primary mechanics, empirical data, and current developments on $goal.",
                status = SectionStatus.PENDING
            ),
            PlanSection(
                index = 3,
                title = "Comparative Evaluation & Challenges",
                queryOrGoal = "Analyze alternative approaches, limitations, trade-offs, and critical challenges for $goal.",
                status = SectionStatus.PENDING
            ),
            PlanSection(
                index = 4,
                title = "Summary & Strategic Takeaways",
                queryOrGoal = "Synthesize key insights and formulate actionable recommendations on $goal.",
                status = SectionStatus.PENDING
            )
        )
    }
}
