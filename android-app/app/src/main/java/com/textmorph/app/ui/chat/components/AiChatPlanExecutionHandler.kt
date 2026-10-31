package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.agent.orchestration.DeepPlanEngine
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
 * the unified chat interface, streaming progress notifications and persisting
 * assembled markdown artifacts.
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
            val userMsg = ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                content = userPrompt,
                isAgentMode = true
            )
            val assistantPlaceholder = ChatMessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = "Initializing deep plan…",
                isAgentMode = true
            )
            chatRepository.appendMessage(userMsg)
            chatRepository.appendMessage(assistantPlaceholder)
            chatRepository.setGenerationStatus("Decomposing plan into sections…")

            try {
                val planEngine = DeepPlanEngine(context, engine)
                val result = planEngine.execute(
                    goal = goal.ifBlank { userPrompt },
                    sessionId = sessionId,
                    workerOverride = workerCount,
                    onProgress = { statusMsg ->
                        scope.launch(Dispatchers.Main) {
                            chatRepository.setGenerationStatus(statusMsg)
                            chatRepository.updateLastAssistantMessage(
                                sessionId = sessionId,
                                content = "*Generating research report…*\n\n> $statusMsg"
                            )
                        }
                    }
                )

                chatRepository.setGenerationStatus(null)
                val isPlan = userPrompt.trim().startsWith("/plan", ignoreCase = true)
                val artifactType = if (isPlan) "plan" else "report"
                val artifactTitle = goal.ifBlank { userPrompt }.take(60)
                val artifactMetadata = com.textmorph.app.data.model.ArtifactMetadata(
                    title = artifactTitle,
                    wordCount = (result.artifactContent.length / 5).coerceAtLeast(1),
                    sectionCount = 1,
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
                    totalTokens = (result.artifactContent.length / 4).coerceAtLeast(1),
                    durationMs = result.durationMs
                )
            } catch (ce: CancellationException) {
                AppLogger.i(TAG, "Deep plan execution cancelled.")
                chatRepository.setGenerationStatus(null)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Deep plan execution encountered an error: ${t.message}", t)
                chatRepository.setGenerationStatus(null)
                chatRepository.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = "Deep plan execution encountered an error: ${t.message ?: "Unknown error"}"
                )
            } finally {
                onComplete()
            }
        }
    }
}
