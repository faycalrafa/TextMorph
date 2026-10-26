package com.textmorph.app.agent

import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.tools.AppLauncherTool
import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.data.model.ActionCommandSyntax
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.ScreenPerceptionMode
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
        val isTextOnly = when (currentEngine) {
            is LiteRtLmEngine -> !currentEngine.sessionState.value.isVisionCapable
            is LlamaCppEngine -> !currentEngine.sessionState.value.isVisionCapable
            else -> false
        }
        val perceptionMode = if (isTextOnly) {
            AppLogger.i(TAG, "Active engine model is text-only. Reverting screen perception to Accessibility Tree.")
            ScreenPerceptionMode.ACCESSIBILITY_TREE
        } else {
            settings.screenPerceptionMode
        }
        val taskSystemPrompt = AgentPrompts.getTaskSystemPrompt(commandSyntax)

        AppLogger.i(TAG, "Task configured with syntax=$commandSyntax, perceptionMode=$perceptionMode (isTextOnly=$isTextOnly)")

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

                var imageBytes: ByteArray? = null
                val screenDump: String = when (perceptionMode) {
                    ScreenPerceptionMode.SCREEN_CAPTURE -> {
                        val captured = screenTool.captureCleanScreenshotBytes()
                        if (captured != null) {
                            imageBytes = captured
                            "[Clean Screen Screenshot Attached - Inspect visually]"
                        } else {
                            AppLogger.w(TAG, "Clean screenshot capture unavailable, falling back to semantic tree.")
                            screenTool.readScreenHierarchy()
                        }
                    }
                    ScreenPerceptionMode.PRIVATE_AGENT -> {
                        screenTool.readScreenPrivateAgent(goal)
                    }
                    ScreenPerceptionMode.ACCESSIBILITY_TREE -> {
                        screenTool.readScreenHierarchy()
                    }
                }

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

                val prompt = buildStepPrompt(goal, step, maxSteps, screenDump, commandSyntax, consecutiveFailures >= 2)

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
                    AppLogger.i(TAG, "Task complete in $step steps: $reasoning")
                    val finishSummary = reasoning.ifEmpty { "Task completed successfully." }
                    TaskExecutionCoordinator.finishTask(finishSummary, success = true)
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    return@withContext finishSummary
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
                    "click_text", "click_at" -> 1500L
                    "press_enter" -> 1500L
                    "scroll", "swipe" -> 1000L
                    else -> 1000L
                }
                delay(stepDelay)
            }

            val maxStepMsg = "Task Incomplete: Reached maximum allowed steps ($maxSteps) without completing goal."
            TaskExecutionCoordinator.finishTask(maxStepMsg, success = false)
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
            {"action": "click_text", "text": "target text"} or {"action": "click_at", "x": 500, "y": 800} or {"action": "type_text", "text": "search query"} or {"action": "press_enter"} or {"action": "scroll", "direction": "down"} or {"action": "open_app", "app_name": "App"} or {"action": "press_back"} or {"action": "done", "response": "summary"}
            """.trimIndent()
        } else {
            """
            GOAL: $goal
            $failureWarning
            CURRENT SCREEN:
            $screenDump

            Step $step/$maxSteps. Return ONLY a single action command on one line:
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
}
