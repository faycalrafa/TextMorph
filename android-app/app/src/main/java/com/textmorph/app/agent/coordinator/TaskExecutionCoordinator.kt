package com.textmorph.app.agent.coordinator

import com.textmorph.app.agent.model.AgentStepStatus
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Immutable state container for the active agent multi-step execution routine.
 */
data class TaskExecutionState(
    val isRunning: Boolean = false,
    val isThinking: Boolean = false,
    val goal: String = "",
    val sessionId: String? = null,
    val currentStep: AgentTaskStep? = null,
    val stepsHistory: List<AgentTaskStep> = emptyList(),
    val isInterrupted: Boolean = false,
    val isFailed: Boolean = false,
    val failureReason: String? = null,
    val summary: String? = null
)

/**
 * Central thread-safe coordinator orchestrating active agent task execution state,
 * step-by-step progress tracking, thinking state, and interruption triggers.
 */
object TaskExecutionCoordinator {

    private const val TAG = "TaskExecutionCoordinator"

    private val _state = MutableStateFlow(TaskExecutionState())
    val state: StateFlow<TaskExecutionState> = _state.asStateFlow()

    private val interruptListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addInterruptListener(listener: () -> Unit) {
        interruptListeners.add(listener)
    }

    fun removeInterruptListener(listener: () -> Unit) {
        interruptListeners.remove(listener)
    }

    fun startTask(goal: String, sessionId: String? = null) {
        AppLogger.i(TAG, "Starting task: '$goal' (session=$sessionId)")
        _state.value = TaskExecutionState(
            isRunning = true,
            isThinking = true,
            goal = goal,
            sessionId = sessionId,
            currentStep = null,
            stepsHistory = emptyList(),
            isInterrupted = false,
            isFailed = false,
            failureReason = null,
            summary = null
        )
    }

    fun setThinking(isThinking: Boolean) {
        val cur = _state.value
        if (!cur.isRunning || cur.isThinking == isThinking) return
        _state.value = cur.copy(isThinking = isThinking)
    }

    fun beginStep(stepIndex: Int, actionType: String, title: String, detail: String = "") {
        val cur = _state.value
        if (!cur.isRunning || cur.isInterrupted) return

        AppLogger.d(TAG, "Beginning step $stepIndex [$actionType]: $title")

        // Finalize previous running step if still active
        val updatedHistory = cur.stepsHistory.map { step ->
            if (step.status == AgentStepStatus.RUNNING) {
                step.copy(status = AgentStepStatus.COMPLETED)
            } else step
        }.toMutableList()

        val newStep = AgentTaskStep(
            stepIndex = stepIndex,
            actionType = actionType,
            title = title,
            detail = detail,
            status = AgentStepStatus.RUNNING
        )
        updatedHistory.add(newStep)

        _state.value = cur.copy(
            isThinking = false,
            currentStep = newStep,
            stepsHistory = updatedHistory
        )
    }

    fun completeCurrentStep(success: Boolean, detail: String? = null) {
        val cur = _state.value
        val curStep = cur.currentStep ?: return
        val newStatus = if (success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED

        val updatedHistory = cur.stepsHistory.map { step ->
            if (step.stepIndex == curStep.stepIndex && step.actionType == curStep.actionType) {
                step.copy(
                    status = newStatus,
                    detail = detail ?: step.detail
                )
            } else step
        }

        _state.value = cur.copy(
            currentStep = curStep.copy(status = newStatus, detail = detail ?: curStep.detail),
            stepsHistory = updatedHistory
        )
    }

    fun interrupt() {
        val cur = _state.value
        if (!cur.isRunning && !cur.isInterrupted) return

        AppLogger.i(TAG, "Interrupting active agent task execution!")

        val updatedHistory = cur.stepsHistory.map { step ->
            if (step.status == AgentStepStatus.RUNNING) {
                step.copy(status = AgentStepStatus.INTERRUPTED, detail = "Interrupted by user")
            } else step
        }

        _state.value = cur.copy(
            isRunning = false,
            isThinking = false,
            isInterrupted = true,
            isFailed = false,
            failureReason = null,
            currentStep = cur.currentStep?.copy(status = AgentStepStatus.INTERRUPTED, detail = "Interrupted by user"),
            stepsHistory = updatedHistory,
            summary = "Task stopped by user."
        )

        for (listener in interruptListeners) {
            try {
                listener.invoke()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying interrupt listener: ${e.message}", e)
            }
        }
    }

    fun finishTask(summary: String, success: Boolean) {
        val cur = _state.value
        AppLogger.i(TAG, "Finishing task (success=$success): $summary")

        val finalStatus = if (cur.isInterrupted) {
            AgentStepStatus.INTERRUPTED
        } else if (success) {
            AgentStepStatus.COMPLETED
        } else {
            AgentStepStatus.FAILED
        }

        val updatedHistory = cur.stepsHistory.map { step ->
            if (step.status == AgentStepStatus.RUNNING) {
                step.copy(status = finalStatus, detail = if (!success) summary else step.detail)
            } else step
        }

        _state.value = cur.copy(
            isRunning = false,
            isThinking = false,
            isFailed = !success && !cur.isInterrupted,
            failureReason = if (!success && !cur.isInterrupted) summary else null,
            stepsHistory = updatedHistory,
            summary = summary
        )
    }

    fun reset() {
        _state.value = TaskExecutionState()
    }
}
