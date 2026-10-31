package com.textmorph.app.agent

import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.agent.execution.ActionExecutionDispatcher
import com.textmorph.app.agent.memory.ActionStep
import com.textmorph.app.agent.memory.SavedSkill
import com.textmorph.app.agent.memory.SkillMemoryService
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.navigation.NavigationShortcutHelper
import com.textmorph.app.agent.guardrails.ExecutionGuardrail
import com.textmorph.app.agent.guardrails.GuardrailDecision
import com.textmorph.app.agent.guardrails.ToolValidator
import com.textmorph.app.agent.guardrails.ValidationResult
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.recovery.RecoveryAction
import com.textmorph.app.agent.recovery.RecoveryEngine
import com.textmorph.app.agent.tools.AppLauncherTool
import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.agent.ui.AgentToastNotifier
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Multi-step autonomous mobile agent execution engine.
 * 1-to-1 alignment with PrivateAgent's TaskExecutor perception-action routine,
 * skill memory replay, smart shortcuts, and self-healing recovery engine.
 * Supports dual syntax (Code-as-Action and JSON).
 */
class TaskExecutor(
    private val appLauncher: AppLauncherTool,
    private val screenTool: ScreenAutomationTool,
    private val engineProvider: () -> LlmEngine,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val chatRepoProvider: (() -> com.textmorph.app.data.repository.ChatSessionRepository)? = null,
    private val skillMemory: SkillMemoryService? = null,
    private val recoveryEngine: RecoveryEngine = RecoveryEngine()
) {

    private val TAG = "TaskExecutor"

    private val memory by lazy {
        skillMemory ?: SkillMemoryService(appLauncher.context)
    }

    private val dispatcher by lazy {
        ActionExecutionDispatcher(appLauncher, screenTool)
    }

    private val guardrail = ExecutionGuardrail(maxHops = 15)

    @Volatile
    private var isCancelled = false

    @Volatile
    var lastRawOutput: String = ""
        private set

    fun cancel() {
        isCancelled = true
        AppLogger.i(TAG, "Task execution cancelled by user.")
    }

    suspend fun executeTask(
        goal: String,
        maxSteps: Int = 15,
        onProgress: (step: Int, message: String) -> Unit,
        onStepsUpdated: ((List<AgentTaskStep>) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        isCancelled = false
        lastRawOutput = ""
        guardrail.reset()
        AppLogger.i(TAG, "executeTask() called with goal: '$goal' (maxSteps=$maxSteps)")

        if (!screenTool.isAccessibilityReady()) {
            val errorMsg = "Accessibility service is not enabled. Go to Settings → Accessibility → TextMorph Screen Control and enable it."
            AppLogger.w(TAG, errorMsg)
            TaskExecutionCoordinator.finishTask(errorMsg, success = false)
            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
            return@withContext errorMsg
        }

        val results = mutableListOf<String>()
        results.add("Starting task: $goal")
        onProgress(0, "Starting task: $goal")
        TaskExecutionCoordinator.startTask(goal)
        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)

        val interruptListener = { cancel() }
        TaskExecutionCoordinator.addInterruptListener(interruptListener)

        val currentEngine = engineProvider()
        val isVisionCapable = when (currentEngine) {
            is LiteRtLmEngine -> currentEngine.sessionState.value.isVisionCapable
            is LlamaCppEngine -> currentEngine.sessionState.value.isVisionCapable
            is OpenAiCloudEngine -> true
            else -> false
        }
        val taskSystemPrompt = AgentPrompts.TASK_SYSTEM_PROMPT

        try {
            AgentToastNotifier.showStatusToast(appLauncher.context, "🤖 Starting: ${goal.take(30)}…")
            // 1. Skill Memory Check
            TaskExecutionCoordinator.beginStep(0, "memory", "Checking memory...", "Searching for saved skill")
            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)

            val savedSkill = memory.findSkill(goal)
            if (savedSkill != null && savedSkill.isReliable) {
                onProgress(0, "Found saved skill! Replaying ${savedSkill.steps.size} steps...")
                val replaySuccess = replaySkill(savedSkill, results, onProgress, onStepsUpdated)
                if (replaySuccess) {
                    results.add("Task complete via skill memory.")
                    onProgress(savedSkill.steps.size, "Task complete (via skill memory).")
                    AgentToastNotifier.showStatusToast(appLauncher.context, "Task Complete! (Memory)")
                    TaskExecutionCoordinator.completeCurrentStep(true)
                    TaskExecutionCoordinator.finishTask("Task complete via skill memory.", success = true)
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    return@withContext "Done."
                } else {
                    onProgress(0, "Replay failed, falling back to AI...")
                    memory.recordFailure(savedSkill.id)
                }
            }

            // 2. Navigation Shortcuts Check
            val shortcut = NavigationShortcutHelper.getNavigationShortcut(goal)
            var lastAction = ""
            var sameActionCount = 0
            var consecutiveFailures = 0
            var lastFailedAction = ""
            val executedSteps = mutableListOf<ActionStep>()

            if (!shortcut.isNullOrEmpty()) {
                results.add("Using navigation shortcut: ${shortcut.size} steps")
                onProgress(0, "Using navigation shortcut...")
                for (step in shortcut) {
                    if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) break
                    var success = false
                    if (step.action == "open_app") {
                        val appName = step.params["app_name"].orEmpty()
                        TaskExecutionCoordinator.beginStep(1, AgentAction.ACTION_OPEN_APP, "Opening $appName...", "Fast shortcut launcher")
                        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                        val res = appLauncher.openApp(appName)
                        success = res.isSuccess
                        delay(3000)
                    } else if (step.action == "click_text") {
                        val text = step.params["text"].orEmpty()
                        TaskExecutionCoordinator.beginStep(1, AgentAction.ACTION_CLICK_TEXT, "Tapping \"$text\"...", "Fast shortcut launcher")
                        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                        success = screenTool.clickText(text)
                        delay(1500)
                    }
                    if (success) {
                        executedSteps.add(step)
                        lastAction = step.action
                    } else {
                        break
                    }
                }
            } else {
                val currentPkg = screenTool.getCurrentPackage()
                if (currentPkg == "com.textmorph.app") {
                    onProgress(0, "Moving to background...")
                    TaskExecutionCoordinator.beginStep(0, AgentAction.ACTION_PRESS_HOME, "Moving to background...", "Going Home")
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    screenTool.pressHome()
                    delay(1500)
                }
            }

            // 3. Step Loop (0 until maxSteps)
            for (step in 0 until maxSteps) {
                if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) {
                    results.add("Task cancelled by user.")
                    onProgress(step, "Task cancelled.")
                    AgentToastNotifier.showStatusToast(appLauncher.context, "Task Cancelled")
                    TaskExecutionCoordinator.interrupt()
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    return@withContext "Task cancelled."
                }

                // Adaptive delay
                val settleDelay = when (lastAction) {
                    "open_app" -> 3000L
                    "type_text" -> 2000L
                    "click_text", "click_at", "click_element" -> 1500L
                    "scroll", "swipe" -> 1000L
                    else -> 1200L
                }
                delay(settleDelay)

                // Screen perception
                TaskExecutionCoordinator.setThinking(false)
                TaskExecutionCoordinator.beginStep(step + 1, AgentAction.ACTION_READ_SCREEN, "Viewing screen...", "Perceiving active screen context")
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)

                val screenContent = screenTool.readScreenPrivateAgent(goal)
                AppLogger.d(TAG, "=== SCREEN DUMP (Step ${step + 1}) ===\n$screenContent")

                val prevResultStr = if (step > 0 && results.isNotEmpty()) "\nPREVIOUS ACTION RESULT: ${results.last()}\n" else ""
                val failureHint = if (consecutiveFailures >= 3) {
                    "\n\nWARNING: You have failed $consecutiveFailures times in a row with the same approach. You MUST try a completely different action. If open_app failed, try press_home and look for the app icon on the home screen instead. If click_text failed, use click_at with coordinates. Do NOT repeat the same failed action."
                } else ""

                val prompt = "TASK: $goal\n\nCURRENT SCREEN TEXT DUMP:\n$screenContent$prevResultStr$failureHint\nStep ${step + 1}/$maxSteps. Look at the text dump and coordinates. What is the next action?"

                TaskExecutionCoordinator.setThinking(true)
                onProgress(step + 1, "Planning next action (Step ${step + 1}/$maxSteps)...")

                val imageBytes: ByteArray? = if (isVisionCapable) screenTool.captureCleanScreenshotBytes() else null
                var rawOutput = queryLlm(prompt, imageBytes, taskSystemPrompt)
                lastRawOutput = rawOutput

                if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) {
                    TaskExecutionCoordinator.interrupt()
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    return@withContext "Task cancelled."
                }

                // 4. Parse action with 1-attempt self-correction retry
                var action = CodeActionParser.parse(rawOutput)
                if (action == null) {
                    AppLogger.w(TAG, "=== PARSE FAILED, RETRYING === Raw: $rawOutput")
                    onProgress(step + 1, "Retrying step ${step + 1}...\n(Failed to parse action)")
                    delay(1500)
                    val correctionQuery = "$prompt\n\n[Instruction]: Respond with ONLY a single Code-as-Action command (e.g. click_text(\"Submit\"), scroll(\"down\"), open_app(\"YouTube\"))."
                    val retryOutput = queryLlm(correctionQuery, imageBytes, taskSystemPrompt)
                    rawOutput = retryOutput
                    lastRawOutput = retryOutput
                    action = CodeActionParser.parse(retryOutput)

                    if (action == null) {
                        val errorMsg = "I could not understand the AI response. Please try again."
                        results.add("Step ${step + 1}: Error after retry")
                        onProgress(step + 1, "AI Error: Failed parsing action")
                        AgentToastNotifier.showStatusToast(appLauncher.context, "Agent Error: Parse failure")
                        delay(3000)
                        TaskExecutionCoordinator.finishTask(errorMsg, success = false)
                        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                        return@withContext errorMsg
                    }
                }

                // Pre-flight validation
                val validation = ToolValidator.validate(action, AgentPhase.PHASE_AUTOMATION, appLauncher.context)
                if (validation is ValidationResult.Invalid) {
                    AppLogger.w(TAG, "Tool validation failed: ${validation.reason}, requesting self-correction")
                    val retryOutput = queryLlm("$prompt\n\n${validation.correctionPrompt}", imageBytes, taskSystemPrompt)
                    rawOutput = retryOutput
                    lastRawOutput = retryOutput
                    action = CodeActionParser.parse(retryOutput) ?: action
                }

                TaskExecutionCoordinator.setThinking(false)
                val reasoning = action.reasoning ?: action.response
                val title = dispatcher.formatActionTitle(action)
                TaskExecutionCoordinator.beginStep(step + 1, action.action, title, reasoning)
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                onProgress(step + 1, "Step ${step + 1}: $reasoning")
                AgentToastNotifier.showStepToast(appLauncher.context, step + 1, maxSteps, title)

                // Call-signature loop breaker & anti-repetition check
                val guardrailDecision = guardrail.evaluate(action)
                if (guardrailDecision is GuardrailDecision.LoopDetected) {
                    val blockedResult = "Blocked repeated ${action.action} call. Choose a different visible element or action."
                    results.add(blockedResult)
                    onProgress(step + 1, blockedResult)
                    consecutiveFailures = 3
                    lastFailedAction = action.action
                    lastAction = action.action
                    continue
                }
                lastAction = action.action

                // Execute action
                val (success, actionResult) = dispatcher.executeAction(action)
                TaskExecutionCoordinator.completeCurrentStep(success, actionResult)
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)

                if (!success) {
                    if (action.action == lastFailedAction) {
                        consecutiveFailures++
                    } else {
                        consecutiveFailures = 1
                        lastFailedAction = action.action
                    }

                    if (consecutiveFailures >= 5) {
                        val failMsg = "I could not complete the task. Please try again."
                        results.add("Agent is stuck. Stopping task after $consecutiveFailures consecutive failures.")
                        onProgress(step + 1, "Agent stuck — stopping task.")
                        AgentToastNotifier.showStatusToast(appLauncher.context, "Agent stuck. Task stopped.")
                        delay(4000)
                        TaskExecutionCoordinator.finishTask("Agent stuck after repeated failures.", success = false)
                        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                        return@withContext failMsg
                    }

                    val recovery = recoveryEngine.diagnose(action.action, screenContent)
                    onProgress(step + 1, "Recovering: ${recovery.description}")
                    AgentToastNotifier.showStatusToast(appLauncher.context, "⚠️ Recovering: ${recovery.description.take(28)}")
                    TaskExecutionCoordinator.beginStep(step + 1, "recovery", "Recovering: ${recovery.description}", recovery.description)
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    executeRecovery(recovery)
                    results.add("Recovery step: ${recovery.description}")
                    continue
                } else {
                    consecutiveFailures = 0
                    lastFailedAction = ""
                    executedSteps.add(ActionStep(action.action, action.paramsStringMap()))
                }

                results.add("Step ${step + 1}: $actionResult ($reasoning)")

                if (!action.isComplete && action.action != AgentAction.ACTION_DONE && (step + 1) % 3 == 0) {
                    AgentToastNotifier.showStatusToast(appLauncher.context, "Working... (Step ${step + 1})")
                }

                if (action.isComplete || action.action == AgentAction.ACTION_DONE) {
                    results.add("Task complete: $reasoning")
                    onProgress(step + 1, "Task complete: $reasoning")
                    TaskExecutionCoordinator.beginStep(step + 1, AgentAction.ACTION_DONE, "Task Complete", reasoning)
                    TaskExecutionCoordinator.completeCurrentStep(true)
                    TaskExecutionCoordinator.finishTask(reasoning.ifEmpty { "Done." }, success = true)
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)

                    memory.saveSkill(goal, executedSteps)
                    AgentToastNotifier.showStatusToast(appLauncher.context, "Task Complete!")
                    delay(4000)
                    return@withContext reasoning.ifEmpty { "Done." }
                }
            }

            // Max steps reached
            val maxStepMsg = "I could not complete the task within the allowed steps."
            results.add("Reached maximum steps ($maxSteps). Task may be incomplete.")
            onProgress(maxSteps, "Reached maximum steps.")
            AgentToastNotifier.showStatusToast(appLauncher.context, "Reached maximum steps.")
            delay(4000)
            TaskExecutionCoordinator.finishTask(maxStepMsg, success = false)
            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
            maxStepMsg
        } catch (ce: CancellationException) {
            TaskExecutionCoordinator.interrupt()
            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
            "Task cancelled."
        } catch (e: Exception) {
            AppLogger.e(TAG, "Task execution error: ${e.message}", e)
            val failReason = "Task Failed: ${e.localizedMessage ?: "Unexpected error during execution"}"
            TaskExecutionCoordinator.finishTask(failReason, success = false)
            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
            failReason
        } finally {
            TaskExecutionCoordinator.removeInterruptListener(interruptListener)
        }
    }

    private suspend fun replaySkill(
        skill: SavedSkill,
        results: MutableList<String>,
        onProgress: (step: Int, message: String) -> Unit,
        onStepsUpdated: ((List<AgentTaskStep>) -> Unit)?
    ): Boolean {
        for (i in skill.steps.indices) {
            if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) return false
            val step = skill.steps[i]
            onProgress(i + 1, "Replaying step ${i + 1}/${skill.steps.size}: ${step.action}")
            TaskExecutionCoordinator.beginStep(i + 1, step.action, "Replaying: ${step.action}", "Skill Memory Replay")
            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)

            val delayMs = when (step.action) {
                "open_app" -> 3000L
                "type_text" -> 2000L
                "click_text", "click_at", "click_element" -> 1500L
                "scroll", "swipe" -> 1000L
                else -> 1200L
            }
            delay(delayMs)

            val (success, actionResult) = dispatcher.executeStep(step)
            TaskExecutionCoordinator.completeCurrentStep(success, actionResult)
            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
            results.add("Memory Replay Step ${i + 1}: $actionResult")

            if (!success) {
                return false
            }
        }
        return true
    }

    private suspend fun executeRecovery(recovery: RecoveryAction) {
        when (recovery.action) {
            "wait" -> delay(2000)
            "press_back" -> screenTool.pressBack()
            "scroll" -> {
                val dir = recovery.params["direction"] ?: "down"
                screenTool.scroll(dir)
            }
            "press_home" -> screenTool.pressHome()
        }
    }

    private suspend fun queryLlm(
        prompt: String,
        imageBytes: ByteArray? = null,
        systemInstruction: String = AgentPrompts.TASK_SYSTEM_PROMPT_CODE_ACTION
    ): String {
        return try {
            val engine = engineProvider()
            val sb = StringBuilder()
            engine.generateStream(prompt, systemInstruction, imageBytes).collect { token ->
                sb.append(token)
            }
            sb.toString().trim()
        } catch (ce: CancellationException) {
            AppLogger.d(TAG, "Llm query cancelled by user.")
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "Llm query failed: ${e.message}", e)
            ""
        }
    }

    private fun AgentAction.paramsStringMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for ((k, v) in this.params) {
            map[k] = v.toString().trim('\"')
        }
        return map
    }
}
