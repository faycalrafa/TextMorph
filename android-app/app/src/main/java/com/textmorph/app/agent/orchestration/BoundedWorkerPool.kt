package com.textmorph.app.agent.orchestration

import android.content.Context
import com.textmorph.app.agent.artifacts.ArtifactAssemblyStore
import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.artifacts.SectionStatus
import com.textmorph.app.agent.guardrails.ToolValidator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolDefinition
import com.textmorph.app.agent.tools.registry.AgentToolParameter
import com.textmorph.app.llm.GpuInferenceGate
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.ui.chat.components.ChatToolExecutionHandler
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * BoundedWorkerPool executes decomposed planning and deep search sub-tasks concurrently.
 * It enforces a strict concurrency ceiling (default 2, bounded 1..4) via Semaphore to safeguard
 * mobile RAM and thermals, while coordinating with GpuInferenceGate to serialize native LLM
 * forward-passes and allow concurrent network I/O.
 */
class BoundedWorkerPool(
    requestedWorkers: Int = DEFAULT_CONCURRENT_WORKERS
) {

    companion object {
        const val TAG = "BoundedWorkerPool"
        const val DEFAULT_CONCURRENT_WORKERS = 2
        const val MIN_WORKERS = 1
        const val MAX_WORKERS = 4
        private const val MAX_WORKER_HOPS = 4
    }

    val maxConcurrentWorkers: Int = requestedWorkers.coerceIn(MIN_WORKERS, MAX_WORKERS).also {
        if (requestedWorkers > 2) {
            AppLogger.w(TAG, "Worker concurrency set to $it > 2. May increase thermal throttling on battery.")
        }
    }

    private val semaphore = Semaphore(maxConcurrentWorkers)

    /**
     * Executes all sub-tasks using the bounded worker pool.
     */
    suspend fun executeAll(
        planGoal: String,
        sections: List<PlanSection>,
        engine: LlmEngine,
        context: Context? = null,
        store: ArtifactAssemblyStore,
        onProgress: (index: Int, statusMessage: String) -> Unit
    ): Unit = coroutineScope {
        AppLogger.i(TAG, "Starting pool execution for ${sections.size} sections with $maxConcurrentWorkers concurrent workers.")

        val tasks = sections.map { section ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    executeSectionWorker(
                        planGoal = planGoal,
                        section = section,
                        engine = engine,
                        context = context,
                        store = store,
                        onProgress = onProgress
                    )
                }
            }
        }

        tasks.awaitAll()
        AppLogger.i(TAG, "All ${sections.size} worker sub-tasks completed.")
    }

    /**
     * Single worker executing a sub-task section.
     */
    private suspend fun executeSectionWorker(
        planGoal: String,
        section: PlanSection,
        engine: LlmEngine,
        context: Context? = null,
        store: ArtifactAssemblyStore,
        onProgress: (index: Int, statusMessage: String) -> Unit
    ) {
        val workerTag = "Worker-${section.index}"
        AppLogger.i(TAG, "[$workerTag] Started sub-task: '${section.title}'")
        store.updateStatus(section.index, SectionStatus.RESEARCHING)
        onProgress(section.index, "Researching '${section.title}'…")

        var hop = 0
        var promptForWorker = buildWorkerPrompt(planGoal, section)
        val workerTools = getWorkerTools()

        try {
            while (hop < MAX_WORKER_HOPS) {
                // Tier 2: Acquire GPU lock for LLM inference turn
                val llmOutput = GpuInferenceGate.withLock(workerTag) {
                    val systemPrompt = "You are an autonomous research and drafting worker for Section ${section.index}."
                    engine.generate(promptForWorker, systemPrompt)
                }.trim()

                AppLogger.d(TAG, "[$workerTag] LLM output received (${llmOutput.length} chars)")

                // Parse action
                val action = CodeActionParser.parse(llmOutput)
                if (action == null) {
                    // If output has substantial markdown without code actions, accept as drafted content
                    if (llmOutput.length >= 100) {
                        store.writeSection(section.index, llmOutput)
                    }
                    break
                }

                if (action.action == AgentAction.ACTION_DONE) {
                    AppLogger.d(TAG, "[$workerTag] Received done action. Concluding sub-task hops.")
                    break
                }

                // Handle section writing actions
                when (action.action) {
                    "write_section" -> {
                        val content = (action.params["markdown"] ?: action.params["content"] ?: "").toString()
                        store.writeSection(section.index, content)
                        store.updateStatus(section.index, SectionStatus.DRAFTING)
                        onProgress(section.index, "Drafting '${section.title}'…")
                    }

                    "append_to_section" -> {
                        val content = (action.params["markdown"] ?: action.params["content"] ?: "").toString()
                        store.appendSection(section.index, content)
                        store.updateStatus(section.index, SectionStatus.DRAFTING)
                        onProgress(section.index, "Expanding '${section.title}'…")
                    }

                    else -> {
                        // Tool execution (Web search, page fetch) - executed on Dispatchers.IO OUTSIDE GPU LOCK
                        val validation = ToolValidator.validate(action, workerTools)
                        if (!validation.isValid) {
                            val correction = ToolValidator.synthesizeCorrectionPrompt(action, validation.error)
                            promptForWorker = "$promptForWorker\n\n$llmOutput\n\n$correction"
                            hop++
                            continue
                        }

                        onProgress(section.index, "Gathering sources for '${section.title}'…")
                        val (_, toolOutput) = ChatToolExecutionHandler.executeTool(action, context)
                        val obsPrompt = ChatToolExecutionHandler.formatObservationPrompt(action, toolOutput)
                        promptForWorker = "$promptForWorker\n\n$llmOutput\n\n$obsPrompt\nNow write the section content using write_section(index=${section.index}, markdown=\"...\")."
                    }
                }

                hop++
            }

            // Quality Gate Evaluation
            val qualityResult = store.validateSectionQuality(section.index)
            if (!qualityResult.isValid && section.retryCount == 0) {
                AppLogger.w(TAG, "[$workerTag] Quality gate failed: ${qualityResult.reason}. Triggering 1-turn retry.")
                section.retryCount++
                onProgress(section.index, "Refining '${section.title}'…")

                val retryPrompt = """
                    Your drafted content for Section ${section.index} was too brief or incomplete: ${qualityResult.reason}
                    Please produce a thorough, well-structured section with detailed facts and findings:
                    write_section(index=${section.index}, markdown="## ${section.title}\n[Detailed content here]")
                """.trimIndent()

                val retryOutput = GpuInferenceGate.withLock(workerTag) {
                    engine.generate(retryPrompt, "You are an expert research editor.")
                }

                val retryAction = CodeActionParser.parse(retryOutput)
                if (retryAction != null && (retryAction.action == "write_section" || retryAction.action == "append_to_section")) {
                    val markdown = (retryAction.params["markdown"] ?: retryAction.params["content"] ?: "").toString()
                    store.writeSection(section.index, markdown)
                } else if (retryOutput.length >= 100) {
                    store.writeSection(section.index, retryOutput)
                }
            }

            store.updateStatus(section.index, SectionStatus.DONE)
            onProgress(section.index, "Completed '${section.title}'")
            AppLogger.i(TAG, "[$workerTag] Successfully finished section ${section.index}")

        } catch (ce: CancellationException) {
            AppLogger.i(TAG, "[$workerTag] Sub-task was cancelled.")
            store.updateStatus(section.index, SectionStatus.FAILED)
            throw ce
        } catch (t: Throwable) {
            AppLogger.e(TAG, "[$workerTag] Sub-task worker failed: ${t.message}", t)
            store.updateStatus(section.index, SectionStatus.FAILED)
        }
    }

    private fun buildWorkerPrompt(planGoal: String, section: PlanSection): String {
        return """
            You are researching and drafting Section ${section.index}: "${section.title}" for a comprehensive report on "$planGoal".
            Section Objective: ${section.queryOrGoal}

            AVAILABLE ACTIONS:
            - search_web(query="...") : Search live web sources
            - fetch_page(url="...") : Read content from a URL
            - search_images(query="...") : Search for high-resolution images and get short aliased IDs (e.g. IMG_1)
            - write_section(index=${section.index}, markdown="...") : Write full markdown content for this section (reference images with ![Caption](IMG_1))
            - append_to_section(index=${section.index}, markdown="...") : Append markdown to this section
            - done(summary="...") : Complete this section

            First perform any necessary searches, then invoke write_section(index=${section.index}, markdown="...") with detailed content.
        """.trimIndent()
    }

    private fun getWorkerTools(): List<AgentToolDefinition> {
        return listOf(
            AgentToolDefinition(
                id = AgentAction.ACTION_SEARCH_WEB,
                name = "Search Web",
                category = AgentToolCategory.APPS_AND_WEB,
                description = "Searches the web.",
                codeSignature = "search_web(query=\"...\")",
                parameters = listOf(AgentToolParameter("query", "Search Query", defaultValue = ""))
            ),
            AgentToolDefinition(
                id = AgentAction.ACTION_FETCH_PAGE,
                name = "Fetch Page",
                category = AgentToolCategory.APPS_AND_WEB,
                description = "Fetches page content.",
                codeSignature = "fetch_page(url=\"...\")",
                parameters = listOf(AgentToolParameter("url", "Web URL", defaultValue = ""))
            ),
            AgentToolDefinition(
                id = AgentAction.ACTION_SEARCH_IMAGES,
                name = "Search Images",
                category = AgentToolCategory.APPS_AND_WEB,
                description = "Searches for images and returns short aliased IDs.",
                codeSignature = "search_images(query=\"...\")",
                parameters = listOf(AgentToolParameter("query", "Image Search Query", defaultValue = ""))
            ),
            AgentToolDefinition(
                id = "write_section",
                name = "Write Section",
                category = AgentToolCategory.ORCHESTRATION,
                description = "Writes section content.",
                codeSignature = "write_section(index=1, markdown=\"...\")",
                parameters = listOf(
                    AgentToolParameter("index", "Section Index", type = "number", defaultValue = "1"),
                    AgentToolParameter("markdown", "Markdown Content", defaultValue = "")
                )
            ),
            AgentToolDefinition(
                id = "append_to_section",
                name = "Append Section",
                category = AgentToolCategory.ORCHESTRATION,
                description = "Appends section content.",
                codeSignature = "append_to_section(index=1, markdown=\"...\")",
                parameters = listOf(
                    AgentToolParameter("index", "Section Index", type = "number", defaultValue = "1"),
                    AgentToolParameter("markdown", "Markdown Content", defaultValue = "")
                )
            ),
            AgentToolDefinition(
                id = AgentAction.ACTION_DONE,
                name = "Done",
                category = AgentToolCategory.ORCHESTRATION,
                description = "Finishes the sub-task.",
                codeSignature = "done(summary=\"...\")",
                parameters = listOf(AgentToolParameter("summary", "Summary", defaultValue = "Complete"))
            )
        )
    }
}
