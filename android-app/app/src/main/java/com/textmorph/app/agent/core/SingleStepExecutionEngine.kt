package com.textmorph.app.agent.core

import android.content.Context
import com.textmorph.app.agent.codemode.AgentActionPlan
import com.textmorph.app.agent.codemode.CodeModeScriptBuilder
import com.textmorph.app.agent.codemode.MobileCodeModeSandbox
import com.textmorph.app.agent.events.AgentEventStore
import com.textmorph.app.agent.events.InferenceCompletedEvent
import com.textmorph.app.agent.events.InferenceStartedEvent
import com.textmorph.app.agent.events.ScreenPerceivedEvent
import com.textmorph.app.agent.events.SteerEvent
import com.textmorph.app.agent.events.ToolExecutionCompletedEvent
import com.textmorph.app.agent.events.ToolExecutionFailedEvent
import com.textmorph.app.agent.events.ToolExecutionRequestedEvent
import com.textmorph.app.agent.inbox.SessionInboxRepository
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.perception.AttentionalScreenLinearizer
import com.textmorph.app.agent.perception.ScreenDiffer
import com.textmorph.app.agent.perception.ScreenHashUtils
import com.textmorph.app.agent.security.PermissionGateway
import com.textmorph.app.agent.security.PermissionLevel
import com.textmorph.app.agent.security.SpatialCircuitBreaker
import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.llm.GpuInferenceGate
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.service.overlay.ScreenAutomationController
import com.textmorph.app.service.overlay.UiElementNode
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class StepExecutionResult(
    val isTerminal: Boolean,
    val stepIndex: Int,
    val outputText: String,
    val isSuccess: Boolean = true
)

/**
 * Re-entrant single physical step execution engine.
 * Decouples step iterations into discrete state transitions, checking for steer directives
 * and releasing GPU locks between steps to prevent driver lockups.
 */
class SingleStepExecutionEngine(
    private val context: Context,
    private val engineProvider: () -> LlmEngine,
    private val screenToolProvider: () -> ScreenAutomationTool?,
    private val controllerProvider: () -> ScreenAutomationController? = { null }
) {
    private val TAG = "SingleStepEngine"
    private val claimManager = WriteAheadClaimManager.getInstance(context)
    private val eventStore = AgentEventStore.getInstance(context)
    private val inboxRepo = SessionInboxRepository.getInstance(context)
    private val sandbox by lazy { MobileCodeModeSandbox.getInstance(context, controllerProvider) }
    private val circuitBreaker = SpatialCircuitBreaker()
    private val json = Json { ignoreUnknownKeys = true }

    private var lastNodes: List<UiElementNode> = emptyList()

    /**
     * Executes one atomic physical step in the agent state machine.
     */
    suspend fun executeStep(
        sessionId: String,
        executionId: String,
        goal: String,
        stepIndex: Int,
        maxSteps: Int,
        systemPrompt: String
    ): StepExecutionResult = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "[$sessionId] Starting Step $stepIndex / $maxSteps")

            // 1. Claim Lease Update
            claimManager.acquireClaim(
                sessionId = sessionId,
                executionId = executionId,
                stepIndex = stepIndex,
                phase = ExecutionClaim.PHASE_PERCEIVING
            )

            // 2. Check for Real-Time Steering Directives in Session Inbox
            val pendingSteer = inboxRepo.peekActiveSteer(sessionId)
            val steerHint = if (pendingSteer != null) {
                inboxRepo.markConsumed(pendingSteer.inboxId)
                val steerEvt = SteerEvent(
                    sessionId = sessionId,
                    stepIndex = stepIndex,
                    steerInstruction = pendingSteer.prompt
                )
                eventStore.appendEvent(sessionId, steerEvt.eventType, json.encodeToString(steerEvt))
                "\n\n[USER STEERING DIRECTIVE]: ${pendingSteer.prompt}\nAdjust your current course according to this directive!"
            } else ""

            // 3. Screen Perception & Tree Diffing
            val screenTool = screenToolProvider()
            val controller = controllerProvider()
            val currentNodes = controller?.dumpScreenHierarchy() ?: emptyList()
            val activePkg = controller?.getCurrentPackage() ?: "com.textmorph.app"
            val diff = ScreenDiffer.computeDiff(lastNodes, currentNodes, activePkg)
            lastNodes = currentNodes

            circuitBreaker.recordScreen(diff.currentHash, activePkg)
            val breakerDecision = circuitBreaker.evaluate()
            if (!breakerDecision.allowed) {
                AppLogger.w(TAG, "Circuit breaker tripped: ${breakerDecision.reason}")
                return@withContext StepExecutionResult(
                    isTerminal = true,
                    stepIndex = stepIndex,
                    outputText = "Loop halted: ${breakerDecision.reason}",
                    isSuccess = false
                )
            }

            val screenDumpText = AttentionalScreenLinearizer.formatPerceptionPrompt(currentNodes, diff, goal, activePkg)
            val perceptionEvt = ScreenPerceivedEvent(
                sessionId = sessionId,
                stepIndex = stepIndex,
                activePackage = activePkg,
                screenHash = diff.currentHash,
                elementCount = currentNodes.size,
                isDiffOnly = diff.hasMeaningfulChanges
            )
            eventStore.appendEvent(sessionId, perceptionEvt.eventType, json.encodeToString(perceptionEvt))

            // 4. Prompt Assembly & Inference Phase
            claimManager.updatePhase(sessionId, ExecutionClaim.PHASE_INFERRING)
            val inferencePrompt = "TASK OBJECTIVE: $goal$steerHint\n\n$screenDumpText\n\nStep $stepIndex of $maxSteps. Provide next action."

            val engine = engineProvider()
            val imageBytes = screenTool?.captureCleanScreenshotBytes()

            val infStartEvt = InferenceStartedEvent(
                sessionId = sessionId,
                stepIndex = stepIndex,
                systemPromptHash = ScreenHashUtils.computeScreenHash(emptyList(), systemPrompt),
                isVisionIncluded = imageBytes != null
            )
            eventStore.appendEvent(sessionId, infStartEvt.eventType, json.encodeToString(infStartEvt))

            val startTime = System.currentTimeMillis()
            val rawOutput = GpuInferenceGate.withLock(callerTag = "StepEngine-$stepIndex") {
                val flow = engine.generateStream(inferencePrompt, systemPrompt, imageBytes)
                val sb = StringBuilder()
                flow.collect { sb.append(it) }
                sb.toString().trim()
            }
            val duration = System.currentTimeMillis() - startTime

            val infEndEvt = InferenceCompletedEvent(
                sessionId = sessionId,
                stepIndex = stepIndex,
                rawOutput = rawOutput,
                durationMs = duration
            )
            eventStore.appendEvent(sessionId, infEndEvt.eventType, json.encodeToString(infEndEvt))

            // 5. Action Parsing & Execution Phase
            claimManager.updatePhase(sessionId, ExecutionClaim.PHASE_EXECUTING, rawOutput)
            val plan = CodeModeScriptBuilder.parseActionPlan(rawOutput)

            when (plan) {
                is AgentActionPlan.CodeModeScript -> {
                    AppLogger.i(TAG, "Executing Mobile CodeMode JavaScript batch...")
                    val (success, scriptResult) = sandbox.executeScript(plan.scriptContent)
                    val completedEvt = ToolExecutionCompletedEvent(
                        callId = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        stepIndex = stepIndex,
                        toolName = "codemode_script",
                        success = success,
                        outputSnippet = scriptResult.take(300),
                        durationMs = System.currentTimeMillis() - startTime
                    )
                    eventStore.appendEvent(sessionId, completedEvt.eventType, json.encodeToString(completedEvt))
                    return@withContext StepExecutionResult(
                        isTerminal = success && scriptResult.contains("done"),
                        stepIndex = stepIndex,
                        outputText = scriptResult,
                        isSuccess = success
                    )
                }

                is AgentActionPlan.SingleCodeAction -> {
                    val action = plan.action
                    if (action.action == "done") {
                        val summary = action.getStringParamWithAliases("summary", "text", default = "Task complete.")
                        return@withContext StepExecutionResult(
                            isTerminal = true,
                            stepIndex = stepIndex,
                            outputText = summary,
                            isSuccess = true
                        )
                    }

                    // Capability authorization check
                    val perm = PermissionGateway.evaluate(sessionId, action, activePkg)
                    if (perm == PermissionLevel.DENY) {
                        return@withContext StepExecutionResult(
                            isTerminal = true,
                            stepIndex = stepIndex,
                            outputText = "Action '${action.action}' was denied by security policy.",
                            isSuccess = false
                        )
                    }

                    val reqEvt = ToolExecutionRequestedEvent(
                        callId = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        stepIndex = stepIndex,
                        toolName = action.action
                    )
                    eventStore.appendEvent(sessionId, reqEvt.eventType, json.encodeToString(reqEvt))

                    val (toolSuccess, toolOutput) = executeSingleAction(action, screenTool)

                    val toolDoneEvt = ToolExecutionCompletedEvent(
                        callId = reqEvt.callId,
                        sessionId = sessionId,
                        stepIndex = stepIndex,
                        toolName = action.action,
                        success = toolSuccess,
                        outputSnippet = toolOutput.take(300),
                        durationMs = 0L
                    )
                    eventStore.appendEvent(sessionId, toolDoneEvt.eventType, json.encodeToString(toolDoneEvt))

                    return@withContext StepExecutionResult(
                        isTerminal = stepIndex >= maxSteps,
                        stepIndex = stepIndex,
                        outputText = toolOutput,
                        isSuccess = toolSuccess
                    )
                }

                is AgentActionPlan.ConversationalResponse -> {
                    return@withContext StepExecutionResult(
                        isTerminal = true,
                        stepIndex = stepIndex,
                        outputText = plan.text,
                        isSuccess = true
                    )
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error executing step $stepIndex: ${t.message}", t)
            return@withContext StepExecutionResult(
                isTerminal = true,
                stepIndex = stepIndex,
                outputText = "Step failed: ${t.message}",
                isSuccess = false
            )
        }
    }

    private fun executeSingleAction(action: AgentAction, screenTool: ScreenAutomationTool?): Pair<Boolean, String> {
        if (screenTool == null) return Pair(false, "Screen tool not active")
        return when (action.action) {
            AgentAction.ACTION_CLICK_TEXT -> {
                val text = action.getStringParamWithAliases("text", "label")
                val ok = screenTool.clickText(text)
                Pair(ok, if (ok) "Clicked text '$text'" else "Failed to find text '$text'")
            }
            AgentAction.ACTION_CLICK_AT -> {
                val x = action.getStringParam("x").toFloatOrNull() ?: 0f
                val y = action.getStringParam("y").toFloatOrNull() ?: 0f
                val ok = screenTool.clickAt(x, y)
                Pair(ok, if (ok) "Clicked coordinates ($x, $y)" else "Failed click at ($x, $y)")
            }
            AgentAction.ACTION_OPEN_APP -> {
                val app = action.getStringParamWithAliases("app_name", "app", "name")
                Pair(true, "Requested open app $app")
            }
            AgentAction.ACTION_PRESS_HOME -> {
                val ok = screenTool.pressHome()
                Pair(ok, "Pressed Home")
            }
            AgentAction.ACTION_PRESS_BACK -> {
                val ok = screenTool.pressBack()
                Pair(ok, "Pressed Back")
            }
            AgentAction.ACTION_TYPE_TEXT -> {
                val text = action.getStringParamWithAliases("text", "input")
                val ok = screenTool.typeText(text)
                Pair(ok, if (ok) "Typed '$text'" else "Failed typing text")
            }
            AgentAction.ACTION_SCROLL -> {
                val dir = action.getStringParamWithAliases("direction", default = "down")
                val ok = screenTool.scroll(dir)
                Pair(ok, "Scrolled $dir")
            }
            else -> Pair(false, "Unsupported tool ${action.action}")
        }
    }
}
