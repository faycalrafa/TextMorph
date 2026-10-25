package com.textmorph.app.agent

import com.textmorph.app.agent.model.AgentAction
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
        onProgress: (step: Int, message: String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        isCancelled = false
        AppLogger.i(TAG, "Executing multi-step task: '$goal' (maxSteps=$maxSteps)")
        onProgress(0, "Starting: $goal")

        if (!screenTool.isAccessibilityReady()) {
            return@withContext "Accessibility Service is not enabled. Please enable TextMorph in Android Accessibility Settings to automate the screen."
        }

        // 1. Check for quick launcher shortcuts to accelerate cold starts
        val shortcutApp = detectFastShortcutApp(goal)
        var lastAction = ""
        if (shortcutApp != null) {
            onProgress(1, "Opening $shortcutApp…")
            appLauncher.openApp(shortcutApp)
            lastAction = "open_app"
            delay(2800)
        }

        var consecutiveFailures = 0
        var sameActionCount = 0

        for (step in 1..maxSteps) {
            if (isCancelled) return@withContext "Task cancelled by user."

            val dump = screenTool.readScreen(goal)
            val prompt = buildStepPrompt(goal, step, maxSteps, dump)

            onProgress(step, "Analyzing screen (Step $step/$maxSteps)…")
            val rawOutput = queryLlm(prompt)
            if (isCancelled) return@withContext "Task cancelled by user."

            val action = AgentActionParser.parseAction(rawOutput)
            if (action == null) {
                AppLogger.w(TAG, "Failed parsing action JSON at step $step: $rawOutput")
                if (++consecutiveFailures >= 3) {
                    return@withContext "Stopped: Model did not return valid actions."
                }
                delay(1200)
                continue
            }

            val reasoning = action.reasoning ?: action.response
            onProgress(step, reasoning.ifEmpty { "Executing ${action.action}" })

            if (action.isComplete || action.action == AgentAction.ACTION_DONE) {
                AppLogger.i(TAG, "Task complete in $step steps: $reasoning")
                return@withContext reasoning.ifEmpty { "Task completed successfully." }
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

        "Reached maximum allowed steps ($maxSteps). Task may be partially completed."
    }

    private suspend fun queryLlm(prompt: String): String {
        return try {
            val engine = engineProvider()
            val sb = StringBuilder()
            engine.generateStream(prompt, AgentPrompts.TASK_SYSTEM_PROMPT).collect { token ->
                sb.append(token)
            }
            sb.toString().trim()
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
            Step $step/$maxSteps. Return ONLY a single JSON object with your next action:
            {
              "action": "click_text | click_at | type_text | press_enter | scroll | swipe | open_app | press_back | done",
              "params": {"text": "...", "x": 540, "y": 960, "direction": "down"},
              "reasoning": "1 short sentence explaining next step",
              "is_complete": false
            }
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
