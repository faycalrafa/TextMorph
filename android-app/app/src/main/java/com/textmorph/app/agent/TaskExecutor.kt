package com.textmorph.app.agent

import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.agent.tools.AppLauncherTool
import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Autonomous multi-step screen navigation and interaction routine.
 * Guided step-by-step by LLM analysis of accessibility tree dumps.
 */
class TaskExecutor(
    private val appLauncher: AppLauncherTool,
    private val screenTool: ScreenAutomationTool,
    private val engineProvider: () -> LlmEngine
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
                delay(2800)
            }

            var consecutiveFailures = 0
            var sameActionCount = 0

            for (step in 1..maxSteps) {
                if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) {
                    TaskExecutionCoordinator.interrupt()
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    return@withContext "Task stopped by user."
                }

                TaskExecutionCoordinator.beginStep(step, AgentAction.ACTION_READ_SCREEN, "Viewing screen…", "Inspecting visible UI elements")
                onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                onProgress(step, "Viewing screen (Step $step/$maxSteps)…")

                val dump = screenTool.readScreen(goal)

                TaskExecutionCoordinator.setThinking(true)
                onProgress(step, "Planning next action (Step $step/$maxSteps)…")

                val prompt = buildStepPrompt(goal, step, maxSteps, dump)

                val rawOutput = queryLlm(prompt)
                if (isCancelled || TaskExecutionCoordinator.state.value.isInterrupted) {
                    TaskExecutionCoordinator.interrupt()
                    onStepsUpdated?.invoke(TaskExecutionCoordinator.state.value.stepsHistory)
                    return@withContext "Task stopped by user."
                }

                val action = AgentActionParser.parseAction(rawOutput)
                if (action == null) {
                    AppLogger.w(TAG, "Failed parsing action JSON at step $step: $rawOutput")
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
                if (sameActionCount > 3 && (action.action == "scroll" || action.action == "press_enter")) {
                    onProgress(step, "Recovering: Action repeated too many times. Navigating…")
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
                        onProgress(step, "Recovering: Backing out…")
                        screenTool.pressBack()
                        delay(1000)
                    }
                }

                val stepDelay = when (action.action) {
                    "open_app" -> 2800L
                    "type_text" -> 1600L
                    "click_text", "click_at" -> 1400L
                    "scroll", "swipe" -> 900L
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

    private suspend fun queryLlm(prompt: String): String {
        return try {
            val engine = engineProvider()
            val sb = StringBuilder()
            engine.generateStream(prompt, AgentPrompts.TASK_SYSTEM_PROMPT).collect { token ->
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
                    val text = action.getStringParam("text")
                    screenTool.clickText(text)
                }
                "click_at" -> {
                    val x = action.getFloatParam("x")
                    val y = action.getFloatParam("y")
                    screenTool.clickAt(x, y)
                }
                "type_text" -> {
                    val text = action.getStringParam("text")
                    val hint = action.getStringParam("field_hint").ifEmpty { null }
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
                    val app = action.getStringParam("app_name")
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

    private fun buildStepPrompt(goal: String, step: Int, maxSteps: Int, screenDump: String): String {
        return """
            GOAL: $goal
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
