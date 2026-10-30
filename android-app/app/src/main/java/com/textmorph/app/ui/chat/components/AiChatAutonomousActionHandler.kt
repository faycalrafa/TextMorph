package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.agent.AgentEngine
import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentStepStatus
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Handles device-level autonomous agent actions (such as UI clicks, typing, app launching, multi-step tasks)
 * triggered from the unified chat loop.
 */
object AiChatAutonomousActionHandler {

    private const val TAG = "AiChatAutonomousAction"

    suspend fun handleAutonomousAction(
        action: AgentAction,
        userPrompt: String,
        sessionId: String,
        systemPrompt: String,
        thought: String?,
        finalOutput: String,
        agentEngine: AgentEngine,
        chatRepository: ChatSessionRepository,
        scope: CoroutineScope
    ) {
        val isPresentationAction = action.action == AgentAction.ACTION_PRESENT_INTERACTIVE_VIEW ||
            action.action == AgentAction.ACTION_REQUEST_USER_APPROVAL ||
            action.action == AgentAction.ACTION_SUGGEST_NEXT_ACTIONS
        if (isPresentationAction) return

        val effectiveAction = if (action.action == AgentAction.ACTION_OPEN_APP &&
            AiChatInferenceUtils.isMultiStepIntent(userPrompt, action.response)
        ) {
            AppLogger.i(TAG, "Auto-escalated standalone open_app to execute_task for multi-step prompt: '$userPrompt'")
            AgentAction(
                action = AgentAction.ACTION_EXECUTE_TASK,
                params = mapOf("goal" to JsonPrimitive(userPrompt)),
                response = "Executing multi-step task: $userPrompt"
            )
        } else if (action.action == AgentAction.ACTION_EXECUTE_TASK) {
            val existingGoal = action.getStringParam("goal")
                .ifEmpty { action.getStringParam("task") }
                .ifEmpty { action.getStringParam("prompt") }
            val effectiveGoal = if (existingGoal.isBlank() || existingGoal == "Executing execute_task" || existingGoal == "execute_task") {
                userPrompt
            } else {
                existingGoal
            }
            action.copy(
                params = action.params + ("goal" to JsonPrimitive(effectiveGoal)),
                response = "Executing multi-step task: $effectiveGoal"
            )
        } else {
            action
        }

        AppLogger.i(TAG, "Executing autonomous agent action: ${effectiveAction.action}")
        if (effectiveAction.action != AgentAction.ACTION_EXECUTE_TASK) {
            TaskExecutionCoordinator.reset()
        }
        chatRepository.updateLastAssistantMessage(
            sessionId = sessionId,
            content = effectiveAction.response.ifEmpty { "Executing ${effectiveAction.action}…" },
            thoughtContent = thought,
            rawNakedOutput = finalOutput,
            systemPrompt = systemPrompt,
            actionPayloadJson = effectiveAction.action,
            actionStatus = "running"
        )
        try {
            val result = agentEngine.execute(
                action = effectiveAction,
                onProgress = { statusUpdate ->
                    chatRepository.setGenerationStatus(statusUpdate)
                },
                onStepsUpdated = { steps ->
                    val isInterrupted = TaskExecutionCoordinator.state.value.isInterrupted
                    val hasFailed = steps.any { it.status == AgentStepStatus.FAILED }
                    val currentStatus = when {
                        isInterrupted -> "interrupted"
                        hasFailed -> "failed"
                        else -> "running"
                    }
                    scope.launch(Dispatchers.IO) {
                        chatRepository.updateLastAssistantMessage(
                            sessionId = sessionId,
                            content = effectiveAction.response.ifEmpty { "Executing ${effectiveAction.action}…" },
                            thoughtContent = thought,
                            rawNakedOutput = finalOutput,
                            systemPrompt = systemPrompt,
                            actionPayloadJson = effectiveAction.action,
                            actionStatus = currentStatus,
                            actionStepsJson = AgentTaskStep.serializeList(steps)
                        )
                    }
                }
            )
            val isExecuteTask = effectiveAction.action == AgentAction.ACTION_EXECUTE_TASK
            val taskState = TaskExecutionCoordinator.state.value
            val isInterrupted = if (isExecuteTask) taskState.isInterrupted else false
            val isSuccess = if (isExecuteTask) {
                result.success && !taskState.isInterrupted && !taskState.isFailed
            } else {
                result.success
            }
            val finalContent = when {
                isInterrupted -> "Task stopped by user."
                isSuccess -> effectiveAction.response.ifEmpty { result.details }
                result.details.startsWith("Task Failed:") ||
                    result.details.startsWith("Error:") ||
                    result.details.startsWith("Action Failed:") -> result.details
                else -> "Action Failed: ${result.details}"
            }
            val finalSteps = if (isExecuteTask) {
                taskState.stepsHistory
            } else {
                val stepStatus = when {
                    isInterrupted -> AgentStepStatus.INTERRUPTED
                    isSuccess -> AgentStepStatus.COMPLETED
                    else -> AgentStepStatus.FAILED
                }
                listOf(
                    AgentTaskStep(
                        stepIndex = 1,
                        actionType = action.action,
                        title = action.response.ifEmpty { "Executing ${action.action}…" },
                        detail = result.details,
                        status = stepStatus
                    )
                )
            }
            chatRepository.updateLastAssistantMessage(
                sessionId = sessionId,
                content = finalContent,
                rawNakedOutput = agentEngine.taskExecutor.lastRawOutput.ifBlank { finalOutput },
                actionStatus = if (isInterrupted) "interrupted" else if (isSuccess) "success" else "failed",
                actionStepsJson = AgentTaskStep.serializeList(finalSteps)
            )
            if (!isSuccess && !isInterrupted) {
                AppLogger.w(TAG, "Agent action '${action.action}' failed: $finalContent (details='${result.details}')")
            } else if (isSuccess) {
                AppLogger.i(TAG, "Agent action '${action.action}' succeeded: ${result.details}")
            } else {
                AppLogger.i(TAG, "Agent action '${action.action}' interrupted by user.")
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            AppLogger.i(TAG, "Agent action execution cancelled by user.")
            TaskExecutionCoordinator.interrupt()
            agentEngine.cancelRunningTask()
            chatRepository.updateLastAssistantMessage(
                sessionId = sessionId,
                content = "Task stopped by user.",
                actionStatus = "interrupted"
            )
            throw ce
        } catch (actEx: Exception) {
            AppLogger.e(TAG, "Agent action '${action.action}' threw exception: ${actEx.message}", actEx)
            chatRepository.updateLastAssistantMessage(
                sessionId = sessionId,
                content = "Error: ${actEx.message}",
                actionStatus = "failed"
            )
        } finally {
            chatRepository.setGenerationStatus(null)
        }
    }
}
