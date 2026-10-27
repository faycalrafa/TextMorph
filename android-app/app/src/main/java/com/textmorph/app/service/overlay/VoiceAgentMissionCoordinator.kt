package com.textmorph.app.service.overlay

import android.content.Context
import com.textmorph.app.agent.AgentEngine
import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentActionResult
import com.textmorph.app.agent.model.AgentStepStatus
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.LlmInteractionRepository
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * Coordinates Voice Agent missions initiated from the floating toolbar / companion.
 * Executes tasks autonomously via [AgentEngine] while streaming real-time milestones,
 * step execution histories, and action payloads to [ChatSessionRepository].
 *
 * Ensures full UI mirroring: viewing the resulting session in the Chat screen displays
 * the exact same interactive timeline cards, milestone chips, and tool progress as
 * running directly from Chat.
 */
object VoiceAgentMissionCoordinator {

    private const val TAG = "VoiceAgentCoordinator"

    suspend fun executeMission(
        context: Context,
        agentEngine: AgentEngine,
        goal: String,
        onProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isBlank()) return@withContext "Empty goal provided."

        AppLogger.i(TAG, "Starting unified Voice Agent mission: '$trimmedGoal'")
        val chatRepo = ChatSessionRepository.getInstance(context)
        val interactionRepo = LlmInteractionRepository.getInstance(context)

        // 1. Get or create the unified single Voice Agent session in ChatSessionRepository
        val session = try {
            chatRepo.getOrCreateVoiceAgentSession()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed retrieving unified voice chat session: ${e.message}", e)
            null
        }
        val sessionId = session?.id

        if (sessionId != null) {
            try {
                chatRepo.setAgentMode(sessionId, true)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed setting agent mode on session $sessionId: ${e.message}")
            }

            // 2. Append User Message
            val userMsg = ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                content = trimmedGoal,
                isAgentMode = true
            )
            chatRepo.appendMessage(userMsg)

            // 3. Append Assistant Placeholder
            val assistantPlaceholder = ChatMessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = "Executing multi-step task: $trimmedGoal",
                actionPayloadJson = AgentAction.ACTION_EXECUTE_TASK,
                actionStatus = "running",
                isAgentMode = true
            )
            chatRepo.appendMessage(assistantPlaceholder)
        }

        // 4. Execute via AgentEngine with step history mirroring
        val action = AgentAction(
            action = AgentAction.ACTION_EXECUTE_TASK,
            params = mapOf("goal" to JsonPrimitive(trimmedGoal)),
            response = "Executing multi-step task: $trimmedGoal"
        )

        chatRepo.setGenerationStatus("Starting voice task…")

        val result = try {
            agentEngine.execute(
                action = action,
                onProgress = { statusMsg ->
                    chatRepo.setGenerationStatus(statusMsg)
                    onProgress?.invoke(statusMsg)
                },
                onStepsUpdated = { steps ->
                    if (sessionId != null) {
                        val taskState = TaskExecutionCoordinator.state.value
                        val isInterrupted = taskState.isInterrupted
                        val hasFailed = steps.any { it.status == AgentStepStatus.FAILED }
                        val currentStatus = when {
                            isInterrupted -> "interrupted"
                            hasFailed -> "failed"
                            else -> "running"
                        }
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                chatRepo.updateLastAssistantMessage(
                                    sessionId = sessionId,
                                    content = "Executing multi-step task: $trimmedGoal",
                                    actionPayloadJson = AgentAction.ACTION_EXECUTE_TASK,
                                    actionStatus = currentStatus,
                                    actionStepsJson = AgentTaskStep.serializeList(steps)
                                )
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Error updating intermediate assistant steps: ${e.message}")
                            }
                        }
                    }
                }
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "Voice Agent mission cancelled by user.")
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unexpected error in agent engine execution: ${e.message}", e)
            AgentActionResult(AgentAction.ACTION_EXECUTE_TASK, false, "Execution failed: ${e.message}")
        } finally {
            chatRepo.setGenerationStatus(null)
        }

        // 5. Finalize state and persist true byte-for-byte output
        val taskState = TaskExecutionCoordinator.state.value
        val isInterrupted = taskState.isInterrupted
        val isSuccess = result.success && !taskState.isInterrupted && !taskState.isFailed
        val finalSummary = when {
            isInterrupted -> "Task stopped by user."
            isSuccess -> if (result.details.isNotBlank()) result.details else "Voice agent completed task successfully."
            result.details.startsWith("Task Failed:") || result.details.startsWith("Error:") -> result.details
            else -> "Action Failed: ${result.details}"
        }

        val finalSteps = taskState.stepsHistory
        val rawNaked = agentEngine.taskExecutor.lastRawOutput.ifBlank { finalSummary }
        val serializedSteps = AgentTaskStep.serializeList(finalSteps)

        if (sessionId != null) {
            try {
                chatRepo.updateLastAssistantMessage(
                    sessionId = sessionId,
                    content = finalSummary,
                    rawNakedOutput = rawNaked,
                    actionPayloadJson = AgentAction.ACTION_EXECUTE_TASK,
                    actionStatus = if (isInterrupted) "interrupted" else if (isSuccess) "success" else "failed",
                    actionStepsJson = serializedSteps
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed updating final assistant message: ${e.message}", e)
            }
        }

        try {
            interactionRepo.recordInteraction(
                source = InteractionTriggerSource.VOICE_AGENT,
                title = "Voice Agent Mission",
                userPrompt = trimmedGoal,
                rawOutput = rawNaked,
                cleanOutput = finalSummary,
                isError = !isSuccess,
                errorMessage = if (!isSuccess) finalSummary else null,
                actionStepsJson = serializedSteps
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed recording LLM interaction: ${e.message}", e)
        }

        AppLogger.i(TAG, "Voice Agent mission completed (success=$isSuccess): $finalSummary")
        finalSummary
    }
}
