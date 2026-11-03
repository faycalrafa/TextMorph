package com.textmorph.app.agent.builder

import android.content.Context
import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.orchestration.DeepPlanEngine
import com.textmorph.app.agent.router.CommandRouteResult
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.data.model.ArtifactMetadata
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.llm.GpuInferenceGate
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Coordinates the iterative, step-by-step Builder state machine for /builder commands.
 * Executes code generation and file modifications one step at a time with loopback thinking,
 * self-correction retries, and live rendering into HtmlSandboxPreviewCard.
 */
object AiChatBuilderHandler {

    private const val TAG = "AiChatBuilderHandler"

    fun handleBuilderCommand(
        scope: CoroutineScope,
        sessionId: String,
        command: CommandRouteResult.BuilderCommand,
        userPrompt: String,
        engine: LlmEngine,
        chatRepository: ChatSessionRepository,
        context: Context,
        onComplete: () -> Unit
    ) {
        scope.launch(Dispatchers.Main) {
            val targetGoal = command.goal.ifBlank { "Create single-file interactive HTML animation" }
            val targetFilename = command.targetFile?.ifBlank { null } ?: "index.html"
            val sandboxDir = File(context.filesDir, "sandbox/$sessionId").apply { mkdirs() }
            val editingEngine = FileEditingEngine(sandboxDir)

            appendUserAndPlaceholder(chatRepository, sessionId, userPrompt, "Initializing builder workspace…")
            val traces = mutableListOf<AgentStepTrace>()

            try {
                // Stage 1: Decompose into discrete construction steps
                val planEngine = DeepPlanEngine(context, engine)
                val sections = planEngine.generateExecutionPlan(targetGoal)
                AppLogger.i(TAG, "[$sessionId] Builder decomposed '$targetGoal' into ${sections.size} steps.")

                var currentCode = ""
                val targetFile = File(sandboxDir, targetFilename)
                if (targetFile.exists()) {
                    currentCode = targetFile.readText(Charsets.UTF_8)
                }

                // Stage 2: Step-by-Step Iterative Build Loop
                for ((idx, section) in sections.withIndex()) {
                    val stepNum = idx + 1
                    val trace = AgentStepTrace(
                        stepIndex = stepNum,
                        toolName = "Builder Step $stepNum: ${section.title}",
                        inputQuery = "Goal: ${section.rationale}",
                        status = "running",
                        startTimeMs = System.currentTimeMillis()
                    )
                    traces.add(trace)
                    updateTraces(chatRepository, sessionId, traces)
                    chatRepository.setGenerationStatus("Builder Step $stepNum/${sections.size}: ${section.title}…")

                    // Build prompt for this step
                    val fileContext = if (currentCode.isBlank()) {
                        "Target file '$targetFilename' does not exist yet. Use <<<<<<< WRITE to create it with boilerplate."
                    } else {
                        val previewLines = currentCode.lines().take(60).joinToString("\n")
                        "Target file '$targetFilename' currently contains:\n$previewLines\n...(total ${currentCode.lines().size} lines). Use <<<<<<< EDIT or WRITE to modify it."
                    }

                    val stepPrompt = """
                        Task: $targetGoal
                        Current Step ($stepNum/${sections.size}): ${section.title}
                        Objective: ${section.rationale}
                        
                        $fileContext
                        
                        Think inside <think>...</think> about what exact code to write or replace, then output the proper plain-text WRITE or EDIT block.
                    """.trimIndent()

                    // Step Execution with up to 2 self-correction retries
                    var stepSuccess = false
                    var retryCount = 0
                    var currentStepPrompt = stepPrompt
                    var lastOutputSnippet = ""

                    while (!stepSuccess && retryCount <= 2) {
                        val rawResponse = withContext(Dispatchers.IO) {
                            GpuInferenceGate.withLock("BuilderStep_$stepNum") {
                                engine.generate(currentStepPrompt, FileEditingEngine.TINY_MODEL_SYSTEM_PROMPT)
                            }
                        }

                        // Extract thinking trace
                        val thought = if (rawResponse.contains("<think>")) {
                            rawResponse.substringAfter("<think>").substringBefore("</think>").trim()
                        } else null
                        trace.thought = thought

                        // Execute plain-text tool blocks
                        val toolResults = editingEngine.executeLlmResponse(rawResponse)
                        val hasErrors = toolResults.any { it.isError }

                        if (toolResults.isNotEmpty() && !hasErrors) {
                            stepSuccess = true
                            lastOutputSnippet = toolResults.joinToString("\n\n") { it.output }
                        } else if (hasErrors) {
                            retryCount++
                            val errorMsg = toolResults.firstOrNull { it.isError }?.output ?: "Unknown syntax error"
                            AppLogger.w(TAG, "Step $stepNum failed (attempt $retryCount): $errorMsg. Retrying with self-correction.")
                            currentStepPrompt = """
                                Your previous edit encountered this error:
                                $errorMsg
                                
                                Please self-correct and provide the revised SEARCH/REPLACE block.
                            """.trimIndent()
                            lastOutputSnippet = "Error (Attempt $retryCount): $errorMsg"
                        } else {
                            // Model wrote code directly without markers; wrap in WRITE fallback
                            if (currentCode.isBlank() && rawResponse.contains("<html", ignoreCase = true)) {
                                val cleanHtml = rawResponse.substringAfter("```html").substringBefore("```").trim()
                                    .ifBlank { rawResponse.substringAfter("<html").let { "<html$it" } }
                                editingEngine.writeFile(targetFilename, cleanHtml)
                                stepSuccess = true
                                lastOutputSnippet = "SUCCESS: Created $targetFilename from direct HTML generation."
                            } else {
                                stepSuccess = true
                                lastOutputSnippet = rawResponse.take(200)
                            }
                        }
                    }

                    // Update step trace
                    trace.status = if (stepSuccess) "success" else "failed"
                    trace.outputSnippet = lastOutputSnippet
                    trace.durationMs = System.currentTimeMillis() - trace.startTimeMs

                    // Refresh file state and emit live preview
                    if (targetFile.exists()) {
                        currentCode = targetFile.readText(Charsets.UTF_8)
                    }

                    chatRepository.updateLastAssistantMessage(
                        sessionId = sessionId,
                        content = currentCode,
                        stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                    )
                }

                // Stage 3: Final Deliverable Assembly
                chatRepository.setGenerationStatus(null)
                val finalArtifact = ArtifactMetadata(
                    title = targetGoal.take(60),
                    wordCount = currentCode.split(Regex("\\s+")).size,
                    sectionCount = sections.size,
                    filePath = targetFile.absolutePath,
                    content = currentCode,
                    id = UUID.randomUUID().toString(),
                    type = "html_app",
                    summary = "Interactive application built successfully in $targetFilename (${currentCode.lines().size} lines).",
                    createdAtMs = System.currentTimeMillis()
                )

                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = currentCode,
                    artifactPayloadJson = ChatMessageEntity.serializeArtifact(finalArtifact),
                    stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
                )

                AppLogger.i(TAG, "[$sessionId] /builder completed successfully. Target: ${targetFile.absolutePath}")
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

    private suspend fun updateTraces(repo: ChatSessionRepository, sessionId: String, traces: List<AgentStepTrace>) {
        repo.updateLastAssistantMessage(sessionId = sessionId, content = "", stepTracesJson = ChatMessageEntity.serializeStepTraces(traces))
    }

    private suspend fun handleError(t: Throwable, repo: ChatSessionRepository, sessionId: String, traces: List<AgentStepTrace>) {
        val isCancelled = t is CancellationException
        if (isCancelled) {
            AppLogger.i(TAG, "Builder task cancelled by user.")
        } else {
            AppLogger.e(TAG, "Builder task error: ${t.message}", t)
        }
        for (trace in traces) {
            if (trace.status == "running") {
                trace.status = if (isCancelled) "cancelled" else "failed"
                trace.failureReason = t.message ?: "Unknown builder error"
                trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
            }
        }
        repo.setGenerationStatus(null)
        repo.updateLastAssistantMessage(
            sessionId = sessionId,
            content = if (isCancelled) "Build cancelled by user." else "Builder encountered an error: ${t.message ?: "Unknown error"}",
            stepTracesJson = ChatMessageEntity.serializeStepTraces(traces)
        )
    }
}
