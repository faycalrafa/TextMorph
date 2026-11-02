package com.textmorph.app.agent.core

import android.content.Context
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.service.overlay.ScreenAutomationController
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DaemonMissionState(
    val isRunning: Boolean = false,
    val sessionId: String? = null,
    val executionId: String? = null,
    val goal: String = "",
    val currentStep: Int = 0,
    val maxSteps: Int = 15,
    val lastOutput: String = "",
    val isSuccess: Boolean = true,
    val failureReason: String? = null
)

/**
 * Master execution coordinator running within the decoupled AgentDaemonService.
 * Replaces monolithic coroutines with durable, re-entrant step orchestrations.
 */
class DaemonExecutionController(
    private val context: Context,
    private val engineProvider: () -> LlmEngine,
    private val screenToolProvider: () -> ScreenAutomationTool?,
    private val controllerProvider: () -> ScreenAutomationController? = { null }
) {
    private val TAG = "DaemonExecController"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val claimManager = WriteAheadClaimManager.getInstance(context)
    private val stepEngine = SingleStepExecutionEngine(context, engineProvider, screenToolProvider, controllerProvider)

    private val _missionState = MutableStateFlow(DaemonMissionState())
    val missionState: StateFlow<DaemonMissionState> = _missionState.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Dispatches an autonomous agent mission across durable physical steps.
     */
    fun startMission(
        sessionId: String,
        goal: String,
        maxSteps: Int = 15,
        onProgress: ((step: Int, message: String) -> Unit)? = null,
        onComplete: ((success: Boolean, message: String) -> Unit)? = null
    ): String {
        val executionId = UUID.randomUUID().toString()
        AppLogger.i(TAG, "Starting autonomous mission: '$goal' (session=$sessionId, exec=$executionId)")

        val job = scope.launch {
            mutex.withLock {
                _missionState.value = DaemonMissionState(
                    isRunning = true,
                    sessionId = sessionId,
                    executionId = executionId,
                    goal = goal,
                    currentStep = 0,
                    maxSteps = maxSteps
                )
            }

            var currentStep = 1
            var finalSuccess = true
            var finalMessage = "Mission completed successfully."

            try {
                while (currentStep <= maxSteps) {
                    val cur = _missionState.value
                    if (!cur.isRunning || cur.sessionId != sessionId) {
                        AppLogger.i(TAG, "Mission $sessionId cancelled before step $currentStep")
                        finalSuccess = false
                        finalMessage = "Task cancelled by user."
                        break
                    }

                    _missionState.value = cur.copy(currentStep = currentStep)
                    onProgress?.invoke(currentStep, "Executing Step $currentStep of $maxSteps...")

                    // Execute one single atomic step
                    val stepResult = stepEngine.executeStep(
                        sessionId = sessionId,
                        executionId = executionId,
                        goal = goal,
                        stepIndex = currentStep,
                        maxSteps = maxSteps,
                        systemPrompt = AgentPrompts.TASK_SYSTEM_PROMPT
                    )

                    _missionState.value = _missionState.value.copy(lastOutput = stepResult.outputText)

                    if (!stepResult.isSuccess) {
                        finalSuccess = false
                        finalMessage = stepResult.outputText
                        break
                    }

                    if (stepResult.isTerminal) {
                        finalSuccess = true
                        finalMessage = stepResult.outputText
                        break
                    }

                    currentStep++
                    // Adaptive pause between physical steps
                    delay(1200)
                }
            } catch (ce: CancellationException) {
                AppLogger.i(TAG, "Mission coroutine cancelled: ${ce.message}")
                finalSuccess = false
                finalMessage = "Task cancelled."
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Fatal failure during mission execution: ${t.message}", t)
                finalSuccess = false
                finalMessage = "Fatal error: ${t.message}"
            } finally {
                claimManager.releaseClaim(sessionId)
                activeJobs.remove(sessionId)
                _missionState.value = DaemonMissionState(
                    isRunning = false,
                    sessionId = sessionId,
                    executionId = executionId,
                    goal = goal,
                    currentStep = currentStep,
                    maxSteps = maxSteps,
                    lastOutput = finalMessage,
                    isSuccess = finalSuccess,
                    failureReason = if (!finalSuccess) finalMessage else null
                )
                onComplete?.invoke(finalSuccess, finalMessage)
            }
        }

        activeJobs[sessionId] = job
        return executionId
    }

    /**
     * Cancels an active mission cleanly.
     */
    fun cancelMission(sessionId: String) {
        AppLogger.i(TAG, "Cancelling mission for session $sessionId")
        activeJobs.remove(sessionId)?.cancel()
        scope.launch {
            claimManager.releaseClaim(sessionId)
            _missionState.value = _missionState.value.copy(
                isRunning = false,
                failureReason = "Cancelled by user."
            )
        }
    }
}
