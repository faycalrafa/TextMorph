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
     * Executes the end-to-end plan-execute-append workflow with already generated/approved sections.
     */
    suspend fun executeApprovedPlan(
        goal: String,
        sections: List<PlanSection>,
        sessionId: String,
        workerOverride: Int? = null,
        onProgress: (statusMessage: String) -> Unit
    ): DeepPlanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val effectiveWorkers = (workerOverride ?: maxConcurrentWorkers).coerceIn(
            BoundedWorkerPool.MIN_WORKERS,
            BoundedWorkerPool.MAX_WORKERS
        )

        AppLogger.i(TAG, "[$sessionId] Executing approved plan for: '$goal' with ${sections.size} sections (workers: $effectiveWorkers)")
        onProgress("Spawning $effectiveWorkers concurrent workers for ${sections.size} steps…")

        // 1. Initialize Artifact Assembly Store with disk staging
        val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val store = ArtifactAssemblyStore(cacheDir, sessionId)
        store.initSession(goal, sections)

        // 2. Dispatch bounded workers
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
                    if (context != null) {
                        try {
                            val eventStore = com.textmorph.app.agent.events.AgentEventStore.getInstance(context)
                            val subEvt = com.textmorph.app.agent.events.SubagentCompletedEvent(
                                parentSessionId = sessionId,
                                childSessionId = "${sessionId}_sub_$index",
                                sectionIndex = index,
                                sectionTitle = title,
                                summary = statusMsg
                            )
                            kotlinx.coroutines.runBlocking {
                                eventStore.appendEvent(
                                    sessionId,
                                    subEvt.eventType,
                                    kotlinx.serialization.json.Json.encodeToString(subEvt)
                                )
                            }
                        } catch (t: Throwable) {
                            AppLogger.w(TAG, "Failed logging subagent event: ${t.message}")
                        }
                    }
                } else {
                    onProgress(statusMsg)
                }
            }
        )

        // 3. Final Quality & Assembly
        onProgress("Assembling and formatting final deliverable…")
        val finalArtifact = store.compileFinalArtifact()
        val duration = System.currentTimeMillis() - startTime
        val finalSections = store.getAllSections()

        AppLogger.i(TAG, "[$sessionId] Plan execution completed in ${duration}ms with ${finalArtifact.length} chars.")
        return@withContext DeepPlanResult(
            goal = goal,
            artifactContent = finalArtifact,
            sections = finalSections,
            workerCount = effectiveWorkers,
            durationMs = duration
        )
    }

    /**
     * Executes legacy one-pass workflow (decompose + execute).
     */
    suspend fun execute(
        goal: String,
        sessionId: String,
        workerOverride: Int? = null,
        onProgress: (statusMessage: String) -> Unit
    ): DeepPlanResult {
        val sections = generateExecutionPlan(goal)
        return executeApprovedPlan(goal, sections, sessionId, workerOverride, onProgress)
    }

    /**
     * Planner Pass: Prompts the LLM to decompose the goal into 3–5 discrete, actionable execution steps.
     */
    suspend fun generateExecutionPlan(goal: String): List<PlanSection> {
        val prompt = """
            Decompose this goal into 3 to 5 discrete, actionable execution steps for worker agents:
            Goal: "$goal"

            For each step, specify:
            1. Step Title
            2. Worker Role (Asset Harvester, Research Scout, Logic Engineer, or QA Validator)
            3. Rationale: What will be done and why.

            Respond with ONLY a numbered list in this format:
            1. [Title] | [Worker Role] | [What will be done and why]
            2. [Title] | [Worker Role] | [What will be done and why]
            3. [Title] | [Worker Role] | [What will be done and why]
        """.trimIndent()

        val rawOutput = try {
            GpuInferenceGate.withLock("PlannerPass") {
                engine.generate(prompt, "You are a master task planner. Output clean, numbered steps with pipe-separated roles.")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Planner LLM pass failed: ${t.message}. Utilizing heuristic fallback.")
            ""
        }

        val parsed = parsePlanSections(rawOutput, goal)
        if (parsed.size >= 2) {
            return parsed
        }

        AppLogger.i(TAG, "LLM plan output parsing yielded < 2 sections. Generating default execution plan.")
        return generateDefaultSections(goal)
    }

    /**
     * Backward-compatible alias for decomposeIntoPlan.
     */
    suspend fun decomposeIntoPlan(goal: String): List<PlanSection> = generateExecutionPlan(goal)

    /**
     * Revises an existing execution plan based on user feedback.
     */
    suspend fun revisePlan(
        goal: String,
        currentSections: List<PlanSection>,
        userFeedback: String
    ): List<PlanSection> {
        val stepsSummary = currentSections.joinToString("\n") {
            "${it.index}. ${it.title} | ${it.role} | ${it.rationale.ifBlank { it.queryOrGoal }}"
        }
        val prompt = """
            You are an expert system planner revising an existing execution plan.
            Goal: "$goal"
            Current Plan:
            $stepsSummary

            User Revision Request:
            "$userFeedback"

            Update the plan to address the user's request. Modify, add, or remove steps as requested.
            Respond with 3 to 5 discrete steps strictly in this format:
            1. [Title] | [Worker Role] | [What will be done and why]
        """.trimIndent()

        val rawOutput = try {
            GpuInferenceGate.withLock("PlannerPass") {
                engine.generate(prompt, "You are a master task planner revising an execution plan.")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Planner revision LLM pass failed: ${t.message}")
            ""
        }

        val parsed = parsePlanSections(rawOutput, goal)
        if (parsed.size >= 2) {
            return parsed
        }

        // Fallback: append or replace based on feedback
        val revised = currentSections.toMutableList()
        revised.add(
            PlanSection(
                index = revised.size + 1,
                title = "Refinement: ${userFeedback.take(30)}",
                queryOrGoal = userFeedback,
                role = inferRole(userFeedback),
                rationale = userFeedback
            )
        )
        return revised.mapIndexed { idx, sec -> sec.copy(index = idx + 1) }
    }

    /**
     * Formats planned execution sections into a clean, human-readable Markdown proposal table.
     */
    fun formatPlanMarkdown(goal: String, sections: List<PlanSection>, version: Int = 1): String {
        val sb = StringBuilder()
        sb.append("### 📋 Proposed Execution Plan (v").append(version).append(")\n")
        sb.append("**Objective:** ").append(goal).append("\n\n")
        sb.append("| Step | Task Name | Worker Role | What & Why |\n")
        sb.append("| :--- | :--- | :--- | :--- |\n")
        for (sec in sections) {
            val paddedIdx = if (sec.index < 10) "0${sec.index}" else "${sec.index}"
            val role = sec.role.ifBlank { inferRole(sec.title) }
            val desc = sec.rationale.ifBlank { sec.queryOrGoal }
            sb.append("| **").append(paddedIdx).append("** | ").append(sec.title).append(" | `").append(role).append("` | ").append(desc).append(" |\n")
        }
        sb.append("\n> Review the proposed execution plan above. Click **Execute Plan** to launch parallel worker agents, or **Edit Plan** to refine the steps.")
        return sb.toString()
    }

    /**
     * Infers worker role based on step title keywords.
     */
    fun inferRole(title: String): String {
        val lower = title.lowercase()
        return when {
            listOf("asset", "svg", "image", "media", "palette", "icon", "vector", "texture", "graphic").any { lower.contains(it) } -> "Asset Harvester"
            listOf("research", "doc", "study", "formula", "math", "kinematic", "spec", "find", "search", "investigate").any { lower.contains(it) } -> "Research Scout"
            listOf("qa", "test", "audit", "verify", "benchmark", "polish", "check", "validation", "lint", "performance").any { lower.contains(it) } -> "QA Validator"
            else -> "Logic Engineer"
        }
    }

    /**
     * Parses numbered text lines into structured PlanSection models supporting pipe and dash delimiters.
     */
    fun parsePlanSections(text: String, fallbackGoal: String): List<PlanSection> {
        if (text.isBlank()) return emptyList()

        // Strip any think tags before parsing
        val cleanText = text.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").replace("</think>", "").trim()
        val results = mutableListOf<PlanSection>()
        val lines = cleanText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val lineRegex = Regex("""^(\d+)[.)\s]+(.*?)$""")
        for (line in lines) {
            val match = lineRegex.find(line) ?: continue
            val index = match.groupValues[1].toIntOrNull() ?: (results.size + 1)
            val remainder = match.groupValues[2].trim()

            var title: String
            var role: String
            var rationale: String

            if (remainder.contains("|")) {
                val parts = remainder.split("|").map { it.trim().trim('*', '#', '`') }
                title = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Step $index"
                role = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: inferRole(title)
                rationale = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Execute $title"
            } else if (remainder.contains(" - ") || remainder.contains(" — ") || remainder.contains(": ")) {
                val splitRegex = Regex("""\s*[-—:]\s*""")
                val parts = remainder.split(splitRegex, limit = 2).map { it.trim().trim('*', '#', '`') }
                title = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Step $index"
                rationale = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Execute $title"
                role = inferRole(title)
            } else {
                title = remainder.trim('*', '#', '`')
                role = inferRole(title)
                rationale = "Execute $title"
            }

            if (title.length >= 3 && !title.startsWith("Section", ignoreCase = true) && !title.startsWith("Step", ignoreCase = true) || title.length >= 5) {
                results.add(
                    PlanSection(
                        index = index,
                        title = title,
                        queryOrGoal = rationale,
                        role = role,
                        rationale = rationale,
                        status = SectionStatus.PENDING
                    )
                )
            }
        }

        // Renumber indices to 1..N strictly
        return results.mapIndexed { i, sec -> sec.copy(index = i + 1) }.take(6)
    }

    /**
     * Generates a robust default 4-section plan if planner LLM output was insufficient.
     */
    fun generateDefaultSections(goal: String): List<PlanSection> {
        val isDevTask = listOf("animat", "html", "css", "js", "code", "app", "ui", "build", "create", "make").any { goal.lowercase().contains(it) }
        return if (isDevTask) {
            listOf(
                PlanSection(
                    index = 1,
                    title = "Asset Harvesting & Visual Foundations",
                    queryOrGoal = "Gather or generate required SVGs, sprites, color palettes, and container styles for $goal.",
                    role = "Asset Harvester",
                    rationale = "Pre-fetches and creates visual assets before structural assembly.",
                    status = SectionStatus.PENDING
                ),
                PlanSection(
                    index = 2,
                    title = "Kinematic & Mathematical Research",
                    queryOrGoal = "Identify necessary formulas, easing functions, and hardware-accelerated transform patterns.",
                    role = "Research Scout",
                    rationale = "Ensures motion algorithms run smoothly at 60 FPS without layout thrashing.",
                    status = SectionStatus.PENDING
                ),
                PlanSection(
                    index = 3,
                    title = "Core Architecture & Logic Implementation",
                    queryOrGoal = "Construct responsive layout, render pipeline, and animation event loop.",
                    role = "Logic Engineer",
                    rationale = "Implements the core behavior connecting visual assets with physics.",
                    status = SectionStatus.PENDING
                ),
                PlanSection(
                    index = 4,
                    title = "Quality Verification & Performance Audit",
                    queryOrGoal = "Validate responsive viewport scaling, frame rate stability, and error handling.",
                    role = "QA Validator",
                    rationale = "Ensures the delivered solution runs stably across all target devices.",
                    status = SectionStatus.PENDING
                )
            )
        } else {
            listOf(
                PlanSection(
                    index = 1,
                    title = "Overview & Core Background",
                    queryOrGoal = "Provide fundamental context, history, and key definitions regarding $goal.",
                    role = "Research Scout",
                    rationale = "Establishes factual baseline and core definitions.",
                    status = SectionStatus.PENDING
                ),
                PlanSection(
                    index = 2,
                    title = "In-Depth Analysis & Key Findings",
                    queryOrGoal = "Detail primary mechanics, empirical data, and current developments on $goal.",
                    role = "Research Scout",
                    rationale = "Investigates empirical metrics and current state.",
                    status = SectionStatus.PENDING
                ),
                PlanSection(
                    index = 3,
                    title = "Comparative Evaluation & Challenges",
                    queryOrGoal = "Analyze alternative approaches, limitations, trade-offs, and critical challenges for $goal.",
                    role = "Logic Engineer",
                    rationale = "Critiques trade-offs, limitations, and failure modes.",
                    status = SectionStatus.PENDING
                ),
                PlanSection(
                    index = 4,
                    title = "Summary & Strategic Takeaways",
                    queryOrGoal = "Synthesize key insights and formulate actionable recommendations on $goal.",
                    role = "QA Validator",
                    rationale = "Consolidates findings into high-impact conclusions.",
                    status = SectionStatus.PENDING
                )
            )
        }
    }
}
