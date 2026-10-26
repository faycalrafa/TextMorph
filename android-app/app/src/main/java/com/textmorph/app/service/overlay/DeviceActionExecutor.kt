package com.textmorph.app.service.overlay

import android.content.Context
import android.content.Intent
import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.planner.TaskPlanDecomposer
import com.textmorph.app.agent.ui.AgentToastNotifier
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executes multi-step UI automation routines with LLM vision/tree guidance.
 * Coordinates smooth floating button target-seeking animations.
 */
class DeviceActionExecutor(
    private val context: Context,
    private val screenController: ScreenAutomationController,
    private val liteRtEngine: LiteRtLmEngine,
    private val llamaCppEngine: LlamaCppEngine,
    private val cloudEngine: OpenAiCloudEngine
) {

    private val TAG = "DeviceActionExecutor"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
        TaskExecutionCoordinator.interrupt()
        AppLogger.i(TAG, "Device action execution cancelled by user.")
    }

    fun stopExecution() = cancel()

    suspend fun executeGoal(
        goal: String,
        maxSteps: Int = 15,
        onProgress: (step: Int, message: String) -> Unit,
        onTargetAnimate: (x: Float, y: Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        isCancelled = false
        AppLogger.i(TAG, "Starting autonomous goal: '$goal' (maxSteps=$maxSteps)")
        TaskExecutionCoordinator.startTask(goal)
        onProgress(0, "Initiating: $goal")

        val milestones = TaskPlanDecomposer.decompose(goal)
        var activeMilestoneIdx = 0
        milestones.firstOrNull()?.let { firstM ->
            AgentToastNotifier.showStepToast(context, firstM.index, milestones.size, firstM.description)
        }

        // 1. Fast pre-launch shortcut if detected
        val shortcutApp = detectFastShortcutApp(goal)
        if (shortcutApp != null) {
            TaskExecutionCoordinator.beginStep(1, AgentAction.ACTION_OPEN_APP, "Opening $shortcutApp…", "Fast shortcut launcher")
            onProgress(1, "Launching $shortcutApp…")
            launchApp(shortcutApp)
            TaskExecutionCoordinator.completeCurrentStep(true)
            delay(2500)
        }

        var consecutiveFailures = 0
        var lastAction = ""

        for (step in 1..maxSteps) {
            if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) {
                TaskExecutionCoordinator.interrupt()
                return@withContext "Task cancelled by user."
            }

            TaskExecutionCoordinator.beginStep(step, AgentAction.ACTION_READ_SCREEN, "Viewing screen…", "Inspecting current screen")
            val dump = screenController.getCompressedScreenDump(goal)
            
            val activeMilestone = milestones.getOrNull(activeMilestoneIdx)
            val milestoneHeadline = activeMilestone?.description ?: goal
            AgentToastNotifier.showStepToast(context, activeMilestone?.index ?: step, milestones.size, milestoneHeadline)
            
            val targetGoalText = if (activeMilestone != null && milestones.size > 1) {
                "OVERALL GOAL: $goal\nACTIVE STEP (${activeMilestone.index}/${milestones.size}): ${activeMilestone.description}"
            } else goal

            val prompt = buildActionPrompt(targetGoalText, step, maxSteps, dump)

            TaskExecutionCoordinator.setThinking(true)
            onProgress(step, "Analyzing screen (Step $step/$maxSteps)…")
            val rawOutput = queryActiveEngine(prompt)
            if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) {
                TaskExecutionCoordinator.interrupt()
                return@withContext "Task cancelled by user."
            }

            val actionPlan = parseActionJson(rawOutput)
            if (actionPlan == null) {
                AppLogger.w(TAG, "Failed parsing action JSON from response: $rawOutput")
                if (++consecutiveFailures >= 3) {
                    val stopMsg = "Stopped: AI returned invalid actions repeatedly."
                    TaskExecutionCoordinator.finishTask(stopMsg, success = false)
                    return@withContext stopMsg
                }
                delay(1200)
                continue
            }

            val actionName = actionPlan.action
            val reasoning = actionPlan.reasoning.ifEmpty { "Executing $actionName" }
            TaskExecutionCoordinator.beginStep(step, actionName, reasoning, reasoning)
            onProgress(step, reasoning)

            if (actionPlan.isComplete || actionName == "done") {
                AppLogger.i(TAG, "Goal accomplished in $step steps: $reasoning")
                TaskExecutionCoordinator.completeCurrentStep(true)
                TaskExecutionCoordinator.finishTask(reasoning, success = true)
                AgentToastNotifier.showStatusToast(context, "Task Complete!")
                return@withContext reasoning
            }

            // Animate target coordinates smoothly if applicable
            actionPlan.targetX?.let { tx ->
                actionPlan.targetY?.let { ty ->
                    withContext(Dispatchers.Main) {
                        onTargetAnimate(tx, ty)
                    }
                    delay(380) // Wait for smooth coordinate glide
                }
            }

            val success = executePrimitiveAction(actionPlan)
            TaskExecutionCoordinator.completeCurrentStep(success)

            if (success) {
                consecutiveFailures = 0
                if (activeMilestone != null && activeMilestoneIdx < milestones.size - 1) {
                    val isConfirmed = TaskPlanDecomposer.isMilestoneConfirmed(
                        milestone = activeMilestone,
                        screenDump = dump,
                        currentPackage = screenController.getCurrentPackage()
                    )
                    if (isConfirmed) {
                        AppLogger.i(TAG, "Step ${activeMilestone.index} verified complete: '${activeMilestone.description}'")
                        activeMilestoneIdx++
                        milestones.getOrNull(activeMilestoneIdx)?.let { nextM ->
                            AgentToastNotifier.showStepToast(context, nextM.index, milestones.size, nextM.description)
                        }
                    }
                }
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 4) {
                    onProgress(step, "Recovering: Dismissing keyboard and navigating…")
                    screenController.pressBack()
                    delay(1000)
                }
            }

            lastAction = actionName
            val stepDelay = when (actionName) {
                "open_app" -> 2500L
                "type_text" -> 1500L
                "click_at", "click_text" -> 1200L
                "scroll", "swipe" -> 800L
                else -> 1000L
            }
            delay(stepDelay)
        }

        "Reached max step limit ($maxSteps). Task may be partially finished."
    }

    private suspend fun queryActiveEngine(prompt: String): String {
        return try {
            val sb = StringBuilder()
            val systemPrompt = "You are a phone automation agent. Respond ONLY with a valid JSON object matching the requested schema."
            val flow = when {
                liteRtEngine.sessionState.value.isLoaded -> liteRtEngine.generateStream(prompt, systemPrompt)
                llamaCppEngine.sessionState.value.isLoaded -> llamaCppEngine.generateStream(prompt, systemPrompt)
                else -> cloudEngine.generateStream(prompt, systemPrompt)
            }
            flow.collect { token -> sb.append(token) }
            sb.toString()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Engine query failed: ${e.message}", e)
            ""
        }
    }

    private fun executePrimitiveAction(plan: ActionPlan): Boolean {
        return try {
            when (plan.action) {
                "click_at" -> {
                    if (plan.targetX != null && plan.targetY != null) {
                        screenController.clickAtCoordinates(plan.targetX, plan.targetY)
                    } else false
                }
                "click_text" -> screenController.clickByText(plan.textParam)
                "type_text" -> screenController.typeIntoField(plan.textParam, plan.fieldHint)
                "press_enter" -> screenController.pressImeEnter()
                "scroll" -> screenController.performScroll(plan.textParam.ifEmpty { "down" })
                "press_back" -> screenController.pressBack()
                "press_home" -> screenController.pressHome()
                "open_app" -> launchApp(plan.textParam)
                "wait" -> true
                else -> false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Action execution error: ${e.message}", e)
            false
        }
    }

    private fun launchApp(appName: String): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(appName) ?: run {
                val packages = pm.getInstalledApplications(0)
                val match = packages.find {
                    it.packageName.contains(appName, true) ||
                        (pm.getApplicationLabel(it).toString().contains(appName, true))
                }
                match?.let { pm.getLaunchIntentForPackage(it.packageName) }
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed launching app '$appName': ${e.message}")
            false
        }
    }

    private fun detectFastShortcutApp(goal: String): String? {
        val lower = goal.lowercase()
        return when {
            lower.contains("youtube") -> "com.google.android.youtube"
            lower.contains("settings") -> "com.android.settings"
            lower.contains("chrome") || lower.contains("browser") -> "com.android.chrome"
            lower.contains("map") -> "com.google.android.apps.maps"
            lower.contains("whatsapp") -> "com.whatsapp"
            else -> null
        }
    }

    private fun buildActionPrompt(goal: String, step: Int, maxSteps: Int, dump: String): String {
        return """
            GOAL: $goal
            CURRENT SCREEN:
            $dump
            Step $step/$maxSteps. Return ONLY a single JSON object:
            {
              "action": "click_at | click_text | type_text | press_enter | scroll | open_app | press_back | done",
              "params": {"x": 540, "y": 960, "text": "...", "field_hint": "..."},
              "reasoning": "1 short sentence explaining next step",
              "is_complete": false
            }
        """.trimIndent()
    }

    private fun parseActionJson(raw: String): ActionPlan? {
        try {
            val clean = raw.substringAfter("{").substringBeforeLast("}")
            if (clean.isBlank()) return null
            val obj = json.parseToJsonElement("{$clean}").jsonObject

            val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: "done"
            val reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull ?: ""
            val isComplete = obj["is_complete"]?.jsonPrimitive?.booleanOrNull ?: (action == "done")

            val params = obj["params"]?.jsonObject
            val x = params?.get("x")?.jsonPrimitive?.doubleOrNull?.toFloat()
            val y = params?.get("y")?.jsonPrimitive?.doubleOrNull?.toFloat()
            val text = params?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
            val hint = params?.get("field_hint")?.jsonPrimitive?.contentOrNull

            return ActionPlan(action, x, y, text, hint, reasoning, isComplete)
        } catch (e: Exception) {
            return null
        }
    }

    data class ActionPlan(
        val action: String,
        val targetX: Float?,
        val targetY: Float?,
        val textParam: String,
        val fieldHint: String?,
        val reasoning: String,
        val isComplete: Boolean
    )
}
