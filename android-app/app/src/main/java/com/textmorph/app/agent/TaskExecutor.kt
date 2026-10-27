package com.textmorph.app.agent

import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.planner.TaskPlanDecomposer
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.tools.AppLauncherTool
import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.agent.ui.AgentToastNotifier
import com.textmorph.app.data.model.ActionCommandSyntax
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Autonomous multi-step screen navigation and interaction routine.
 * Guided step-by-step by LLM analysis of accessibility tree dumps, compressed
 * PrivateAgent hierarchies, or clean screenshot bitmaps.
 */
class TaskExecutor(
    private val appLauncher: AppLauncherTool,
    private val screenTool: ScreenAutomationTool,
    private val engineProvider: () -> LlmEngine,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val chatRepoProvider: (() -> com.textmorph.app.data.repository.ChatSessionRepository)? = null
) {

    private val TAG = "TaskExecutor"

    private val verifier by lazy {
        TaskCompletionVerifier(
            screenTool = screenTool,
            queryLlm = ::queryLlm,
            chatRepoProvider = chatRepoProvider
        )
    }

    @Volatile
    private var isCancelled = false

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
        AppLogger.i(TAG, "Executing multi-step task: '$goal' (maxSteps=$maxSteps)")
        TaskExecutionCoordinator.startTask(goal)
        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
        onProgress(0, "Starting: $goal")

        val interruptListener = { cancel() }
        TaskExecutionCoordinator.addInterruptListener(interruptListener)

        val settings = settingsProvider()
        val commandSyntax = settings.actionCommandSyntax
        val currentEngine = engineProvider()
        val isVisionCapable = when (currentEngine) {
            is LiteRtLmEngine -> currentEngine.sessionState.value.isVisionCapable
            is LlamaCppEngine -> currentEngine.sessionState.value.isVisionCapable
            is com.textmorph.app.llm.OpenAiCloudEngine -> true
            else -> false
        }
        val taskSystemPrompt = AgentPrompts.getTaskSystemPrompt(commandSyntax)

        AppLogger.i(TAG, "Task configured with syntax=$commandSyntax (isVisionCapable=$isVisionCapable)")

        try {
            if (!screenTool.isAccessibilityReady()) {
                val errorMsg = "Accessibility Service is not enabled. Please enable TextMorph in Android Accessibility Settings to automate the screen."
                TaskExecutionCoordinator.finishTask(errorMsg, success = false)
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                return@withContext errorMsg
            }

            // 1. Check for quick launcher shortcuts to accelerate cold starts
            val shortcutApp = detectFastShortcutApp(goal)
            var lastAction = ""
            if (shortcutApp != null) {
                TaskExecutionCoordinator.beginStep(1, AgentAction.ACTION_OPEN_APP, "Opening $shortcutApp…", "Fast shortcut launcher")
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                onProgress(1, "Opening $shortcutApp…")
                val openSuccess = appLauncher.openApp(shortcutApp).isSuccess
                TaskExecutionCoordinator.completeCurrentStep(openSuccess)
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                lastAction = "open_app"
                delay(3000)
            }

            val milestones = TaskPlanDecomposer.decompose(goal)
            var activeMilestoneIndex = 0
            milestones.firstOrNull()?.let { firstM ->
                AgentToastNotifier.showStepToast(appLauncher.context, firstM.index, milestones.size, firstM.description)
            }

            var consecutiveFailures = 0
            var sameActionCount = 0

            for (step in 1..maxSteps) {
                if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) {
                    TaskExecutionCoordinator.interrupt()
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    return@withContext "Task stopped by user."
                }

                TaskExecutionCoordinator.beginStep(step, AgentAction.ACTION_READ_SCREEN, "Viewing screen…", "Perceiving active screen context")
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                onProgress(step, "Viewing screen (Step $step/$maxSteps)…")

                // Unified Screen Perception: Grounded Private Agent semantic hierarchy with exact coordinate centers and element IDs
                val screenDump = screenTool.readScreenPrivateAgent(goal)
                val imageBytes: ByteArray? = if (isVisionCapable) {
                    screenTool.captureCleanScreenshotBytes()
                } else null

                // Heuristic 1: Detect loading / spinner states and allow settle time
                val lowerDump = screenDump.lowercase()
                if (lowerDump.contains("loading") || lowerDump.contains("please wait") || lowerDump.contains("buffering")) {
                    AppLogger.i(TAG, "Loading indicator detected on screen. Waiting 2000ms for content to settle...")
                    onProgress(step, "Waiting for screen to finish loading…")
                    delay(2000)
                }

                // Heuristic 2: Auto-dismiss keyboard if lingering after typing or during failures
                if (lowerDump.contains("inputmethod") || lowerDump.contains("gboard") || lowerDump.contains("softkeyboard")) {
                    if (lastAction == "type_text" || consecutiveFailures >= 2) {
                        AppLogger.i(TAG, "Keyboard visible after typing or failure; pressing back to reveal UI elements.")
                        screenTool.pressBack()
                        delay(600)
                    }
                }

                TaskExecutionCoordinator.setThinking(true)
                onProgress(step, "Planning next action (Step $step/$maxSteps)…")

                val activeMilestone = milestones.getOrNull(activeMilestoneIndex)
                val targetGoalText = if (activeMilestone != null && milestones.size > 1) {
                    "OVERALL GOAL: $goal\nACTIVE STEP (${activeMilestone.index}/${milestones.size}): ${activeMilestone.description}"
                } else goal

                val prompt = buildStepPrompt(targetGoalText, step, maxSteps, screenDump, commandSyntax, consecutiveFailures >= 2)

                val rawOutput = queryLlm(prompt, imageBytes, taskSystemPrompt)
                try {
                    chatRepoProvider?.invoke()?.recordDiagnosticTurn(
                        userPrompt = "Step $step ($goal)",
                        systemPrompt = taskSystemPrompt,
                        rawOutput = rawOutput
                    )
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed recording step diagnostic turn: ${e.message}")
                }
                if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) {
                    TaskExecutionCoordinator.interrupt()
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    return@withContext "Task stopped by user."
                }

                val action = AgentActionParser.parseAction(rawOutput)
                if (action == null) {
                    AppLogger.w(TAG, "Failed parsing action at step $step: $rawOutput")
                    if (++consecutiveFailures >= 3) {
                        val failMsg = "Task Failed: Model did not return valid actions."
                        TaskExecutionCoordinator.finishTask(failMsg, success = false)
                        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                        return@withContext failMsg
                    }
                    delay(1200)
                    continue
                }

                val title = formatActionTitle(action)
                val reasoning = action.reasoning ?: action.response
                TaskExecutionCoordinator.beginStep(step, action.action, title, reasoning)
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                onProgress(step, reasoning.ifEmpty { title })

                if (action.isComplete || action.action == AgentAction.ACTION_DONE) {
                    AppLogger.i(TAG, "Task reached completion signal at step $step ($reasoning). Verifying on-screen...")
                    onProgress(step, "Verifying task completion on screen…")
                    TaskExecutionCoordinator.beginStep(step, "verify_completion", "Verifying completion…", "Checking screen to verify if goal was accomplished")
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)

                    val (isVerified, verifyDetails) = verifyTaskCompletion(
                        goal = goal,
                        reportedReason = reasoning.ifEmpty { "Task complete" },
                        taskSystemPrompt = taskSystemPrompt,
                        isVisionCapable = isVisionCapable
                    )

                    if (isVerified) {
                        val finishSummary = verifyDetails.ifEmpty { reasoning.ifEmpty { "Task completed successfully." } }
                        AppLogger.i(TAG, "Task verified complete in $step steps: $finishSummary")
                        TaskExecutionCoordinator.completeCurrentStep(true)
                        TaskExecutionCoordinator.finishTask(finishSummary, success = true)
                        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                        return@withContext finishSummary
                    } else {
                        val missingMsg = verifyDetails.ifEmpty { "Screen state does not confirm completion." }
                        AppLogger.w(TAG, "Screen verification check unconfirmed: $missingMsg")
                        TaskExecutionCoordinator.completeCurrentStep(false)
                        onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                        if (step < maxSteps - 1) {
                            onProgress(step, "Unconfirmed: $missingMsg. Continuing task…")
                            consecutiveFailures++
                            delay(1500)
                            continue
                        } else {
                            val failMsg = "Task Incomplete: $missingMsg"
                            TaskExecutionCoordinator.finishTask(failMsg, success = false)
                            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                            return@withContext failMsg
                        }
                    }
                }

                sameActionCount = if (action.action == lastAction) sameActionCount + 1 else 1

                // Anti-loop protections:
                if (action.action == "press_enter" && sameActionCount > 2) {
                    onProgress(step, "Recovering: Enter pressed repeatedly without progress. Navigating…")
                    screenTool.pressBack()
                    delay(1000)
                    continue
                }
                if ((action.action == "scroll" || action.action == "swipe") && sameActionCount > 3) {
                    onProgress(step, "Recovering: Scroll repeated too many times. Navigating…")
                    screenTool.pressBack()
                    delay(1000)
                    continue
                }
                lastAction = action.action

                val success = executeSingleAction(action)
                TaskExecutionCoordinator.completeCurrentStep(success)
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)

                if (success) {
                    consecutiveFailures = 0
                    if (activeMilestone != null && activeMilestoneIndex < milestones.size - 1) {
                        val isConfirmed = TaskPlanDecomposer.isMilestoneConfirmed(
                            milestone = activeMilestone,
                            screenDump = screenDump,
                            currentPackage = screenTool.getCurrentPackage()
                        )
                        if (isConfirmed) {
                            AppLogger.i(TAG, "Step ${activeMilestone.index} verified complete: '${activeMilestone.description}'")
                            activeMilestoneIndex++
                            milestones.getOrNull(activeMilestoneIndex)?.let { nextM ->
                                AgentToastNotifier.showStepToast(appLauncher.context, nextM.index, milestones.size, nextM.description)
                            }
                        }
                    }
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 4) {
                        onProgress(step, "Recovering: Action repeated failures. Backing out…")
                        screenTool.pressBack()
                        delay(1000)
                    }
                }

                // PrivateAgent adaptive timing delays:
                val stepDelay = when (action.action) {
                    "open_app" -> 3000L
                    "type_text" -> 2000L
                    "click_text", "click_at", "click_element" -> 1500L
                    "press_enter" -> 1500L
                    "scroll", "swipe" -> 1000L
                    else -> 1000L
                }
                delay(stepDelay)
            }

            // On reaching max steps, verify if final screen state actually completed the goal
            val (isFinalVerified, finalDetails) = verifyTaskCompletion(
                goal = goal,
                reportedReason = "Reached step $maxSteps",
                taskSystemPrompt = taskSystemPrompt,
                isVisionCapable = isVisionCapable
            )
            val maxStepMsg = if (isFinalVerified) {
                finalDetails.ifEmpty { "Task completed successfully on final step." }
            } else {
                "Task Incomplete: Reached maximum allowed steps ($maxSteps) without completing goal. ${finalDetails.take(100)}"
            }
            TaskExecutionCoordinator.finishTask(maxStepMsg, success = isFinalVerified)
            onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
            maxStepMsg
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

    private fun formatActionTitle(action: AgentAction): String {
        return when (action.action) {
            AgentAction.ACTION_OPEN_APP -> "Opening ${action.getStringParamWithAliases("app_name", "name", "app", default = "App")}"
            AgentAction.ACTION_CLICK_TEXT -> "Tapping \"${action.getStringParamWithAliases("text", "label")}\""
            AgentAction.ACTION_CLICK_ELEMENT -> "Tapping element [${action.getIntParamWithAliases("element_id", "id", "index", default = 0)}]"
            AgentAction.ACTION_CLICK_AT -> "Tapping at (${action.getFloatParam("x")}, ${action.getFloatParam("y")})"
            AgentAction.ACTION_TYPE_TEXT -> "Typing \"${action.getStringParamWithAliases("text", "content")}\""
            AgentAction.ACTION_PRESS_ENTER -> "Submitting keyboard enter"
            AgentAction.ACTION_SCROLL -> "Scrolling ${action.getStringParam("direction", "down")}"
            AgentAction.ACTION_SWIPE -> "Swiping on screen"
            AgentAction.ACTION_PRESS_BACK -> "Pressing Back"
            AgentAction.ACTION_PRESS_HOME -> "Pressing Home"
            AgentAction.ACTION_DONE -> "Task finished"
            else -> "Executing ${action.action}"
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
        } catch (ce: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "Llm query cancelled by user.")
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "Llm query failed: ${e.message}", e)
            ""
        }
    }

    private fun executeSingleAction(action: AgentAction): Boolean {
        return try {
            when (action.action) {
                "click_text" -> {
                    val text = action.getStringParamWithAliases("text", "label")
                    screenTool.clickText(text)
                }
                "click_element", "click_id" -> {
                    val id = action.getIntParamWithAliases("element_id", "id", "index", default = -1)
                    if (id >= 0) screenTool.clickElement(id) else false
                }
                "click_at" -> {
                    val x = action.getFloatParam("x")
                    val y = action.getFloatParam("y")
                    screenTool.clickAt(x, y)
                }
                "type_text" -> {
                    val text = action.getStringParamWithAliases("text", "content")
                    val hint = action.getStringParamWithAliases("field_hint", "hint").ifEmpty { null }
                    screenTool.typeText(text, hint)
                }
                "press_enter" -> screenTool.pressEnter()
                "scroll" -> {
                    val dir = action.getStringParam("direction", "down")
                    screenTool.scroll(dir)
                }
                "swipe" -> {
                    val sx = action.getFloatParam("startX", 540f)
                    val sy = action.getFloatParam("startY", 1800f)
                    val ex = action.getFloatParam("endX", 540f)
                    val ey = action.getFloatParam("endY", 600f)
                    screenTool.swipe(sx, sy, ex, ey)
                }
                "press_back" -> screenTool.pressBack()
                "press_home" -> screenTool.pressHome()
                "open_app" -> {
                    val app = action.getStringParamWithAliases("app_name", "name", "app")
                    appLauncher.openApp(app).isSuccess
                }
                "wait" -> true
                else -> false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Action execution error: ${e.message}", e)
            false
        }
    }

    private fun buildStepPrompt(
        goal: String,
        step: Int,
        maxSteps: Int,
        screenDump: String,
        syntax: ActionCommandSyntax,
        warnFailure: Boolean
    ): String {
        val failureWarning = if (warnFailure) {
            "\nIMPORTANT NOTICE: Previous action did not succeed or change the screen. Try targeting a different element, using click_at(x, y), or scrolling to find the target.\n"
        } else ""

        return if (syntax == ActionCommandSyntax.JSON_BASED) {
            """
            GOAL: $goal
            $failureWarning
            CURRENT SCREEN:
            $screenDump

            Step $step/$maxSteps. Return ONLY a single JSON object with your action on one line:
            {"action": "click_element", "id": 5} or {"action": "click_text", "text": "target text"} or {"action": "click_at", "x": 500, "y": 800} or {"action": "type_text", "text": "search query"} or {"action": "press_enter"} or {"action": "scroll", "direction": "down"} or {"action": "open_app", "app_name": "App"} or {"action": "press_back"} or {"action": "done", "response": "summary"}
            """.trimIndent()
        } else {
            """
            GOAL: $goal
            $failureWarning
            CURRENT SCREEN:
            $screenDump

            Step $step/$maxSteps. Return ONLY a single action command on one line:
            - click_element(id)
            - click_text("exact text to click")
            - click_at(x, y)
            - type_text("search text", hint="optional field hint")
            - press_enter()
            - scroll("down")
            - open_app("AppName")
            - press_back()
            - done("Task summary")
            """.trimIndent()
        }
    }

    private fun detectFastShortcutApp(goal: String): String? {
        val lower = goal.lowercase()
        return when {
            lower.contains("youtube") -> "YouTube"
            lower.contains("settings") -> "Settings"
            lower.contains("chrome") || lower.contains("browser") -> "Chrome"
            lower.contains("maps") || lower.contains("navigate") -> "Maps"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("camera") -> "Camera"
            lower.contains("clock") || lower.contains("alarm") -> "Clock"
            lower.contains("calculator") -> "Calculator"
            else -> null
        }
    }

    private suspend fun verifyTaskCompletion(
        goal: String,
        reportedReason: String,
        taskSystemPrompt: String,
        isVisionCapable: Boolean
    ): Pair<Boolean, String> = verifier.verify(goal, reportedReason, taskSystemPrompt, isVisionCapable)
}
