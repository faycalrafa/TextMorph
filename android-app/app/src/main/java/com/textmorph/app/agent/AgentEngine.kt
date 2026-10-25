package com.textmorph.app.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentActionResult
import com.textmorph.app.agent.tools.AppLauncherTool
import com.textmorph.app.agent.tools.CommunicationTool
import com.textmorph.app.agent.tools.ContactsTool
import com.textmorph.app.agent.tools.DeviceControlTool
import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.service.overlay.ScreenAutomationController
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modular central coordinator for AI agent tool dispatching and task execution.
 */
class AgentEngine(
    private val context: Context,
    private val controllerProvider: () -> ScreenAutomationController? = {
        com.textmorph.app.service.overlay.TextMorphAccessibilityService.getScreenAutomationController()
    },
    private val serviceProvider: () -> AccessibilityService? = {
        com.textmorph.app.service.overlay.TextMorphAccessibilityService.getInstance()
    },
    private val engineProvider: () -> LlmEngine = { com.textmorph.app.llm.LiteRtLmEngineManager.getInstance(context) }
) {

    private val TAG = "AgentEngine"

    val contactsTool = ContactsTool(context)
    val appLauncher = AppLauncherTool(context)
    val communication = CommunicationTool(context, contactsTool)
    val deviceControl = DeviceControlTool(context)
    val screenAutomation = ScreenAutomationTool(controllerProvider, serviceProvider)
    val taskExecutor = TaskExecutor(appLauncher, screenAutomation, engineProvider)

    suspend fun execute(
        action: AgentAction,
        onProgress: ((String) -> Unit)? = null,
        onStepsUpdated: ((List<com.textmorph.app.agent.model.AgentTaskStep>) -> Unit)? = null
    ): AgentActionResult = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Executing agent action: ${action.action}")
        try {
            val initialStep = com.textmorph.app.agent.model.AgentTaskStep(
                stepIndex = 1,
                actionType = action.action,
                title = action.response.ifEmpty { "Executing ${action.action}" },
                status = com.textmorph.app.agent.model.AgentStepStatus.RUNNING
            )
            if (action.action != AgentAction.ACTION_EXECUTE_TASK) {
                onStepsUpdated?.invoke(listOf(initialStep))
            }
            val result = when (action.action) {
                AgentAction.ACTION_OPEN_APP -> {
                    val appName = action.getStringParamWithAliases("app_name", "name", "app", "target")
                    val res = appLauncher.openApp(appName)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Failed opening $appName" })
                }

                AgentAction.ACTION_LAUNCH_PACKAGE -> {
                    val pkg = action.getStringParam("package_name")
                    val res = appLauncher.openPackage(pkg)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Failed launching $pkg" })
                }

                AgentAction.ACTION_OPEN_URL -> {
                    val url = action.getStringParam("url")
                    val res = appLauncher.openUrl(url)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Failed opening URL $url" })
                }

                AgentAction.ACTION_MAKE_CALL -> {
                    val name = action.getStringParam("contact_name").ifEmpty { null }
                    val phone = action.getStringParam("phone_number").ifEmpty { null }
                    val res = communication.makeCall(name, phone)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Failed initiating call" })
                }

                AgentAction.ACTION_SEND_SMS -> {
                    val name = action.getStringParam("contact_name").ifEmpty { null }
                    val phone = action.getStringParam("phone_number").ifEmpty { null }
                    val msg = action.getStringParam("message")
                    val res = communication.sendSms(name, phone, msg)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Failed preparing SMS" })
                }

                AgentAction.ACTION_SEND_EMAIL -> {
                    val to = action.getStringParam("to")
                    val subject = action.getStringParam("subject").ifEmpty { null }
                    val body = action.getStringParam("body").ifEmpty { null }
                    val res = communication.sendEmail(to, subject, body)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Failed preparing email" })
                }

                AgentAction.ACTION_SEARCH_CONTACT -> {
                    val query = action.getStringParam("query")
                    val res = contactsTool.searchAndFormat(query)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Contact search failed" })
                }

                AgentAction.ACTION_SET_ALARM -> {
                    val hour = action.getIntParam("hour", 7)
                    val minute = action.getIntParam("minute", 0)
                    val label = action.getStringParam("label").ifEmpty { null }
                    val res = deviceControl.setAlarm(hour, minute, label)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Alarm setup failed" })
                }

                AgentAction.ACTION_SET_TIMER -> {
                    val seconds = action.getIntParam("seconds", 60)
                    val label = action.getStringParam("label").ifEmpty { null }
                    val res = deviceControl.setTimer(seconds, label)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Timer setup failed" })
                }

                AgentAction.ACTION_SET_VOLUME -> {
                    val level = action.getIntParam("level", 50)
                    val res = deviceControl.setVolume(level)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Volume change failed" })
                }

                AgentAction.ACTION_SET_BRIGHTNESS -> {
                    val level = action.getIntParam("level", 50)
                    val res = deviceControl.setBrightness(level)
                    AgentActionResult(action.action, res.isSuccess, res.getOrElse { it.message ?: "Brightness change failed" })
                }

                AgentAction.ACTION_READ_SCREEN -> {
                    val text = screenAutomation.readScreen()
                    AgentActionResult(action.action, true, "Captured screen hierarchy (${text.length} characters)", text)
                }

                AgentAction.ACTION_CLICK_TEXT -> {
                    val text = action.getStringParamWithAliases("text", "label", "target", "element")
                    val ok = screenAutomation.clickText(text)
                    AgentActionResult(action.action, ok, if (ok) "Clicked \"$text\"" else "Could not find \"$text\" to click")
                }

                AgentAction.ACTION_CLICK_AT -> {
                    val x = action.getFloatParam("x")
                    val y = action.getFloatParam("y")
                    val ok = screenAutomation.clickAt(x, y)
                    AgentActionResult(action.action, ok, if (ok) "Clicked at ($x, $y)" else "Click at coordinates failed")
                }

                AgentAction.ACTION_TYPE_TEXT -> {
                    val text = action.getStringParamWithAliases("text", "content", "value")
                    val hint = action.getStringParamWithAliases("field_hint", "hint").ifEmpty { null }
                    val ok = screenAutomation.typeText(text, hint)
                    AgentActionResult(action.action, ok, if (ok) "Typed \"$text\"" else "Could not type into field")
                }

                AgentAction.ACTION_PRESS_ENTER -> {
                    val ok = screenAutomation.pressEnter()
                    AgentActionResult(action.action, ok, if (ok) "Submitted keyboard action" else "Keyboard enter failed")
                }

                AgentAction.ACTION_SCROLL -> {
                    val dir = action.getStringParam("direction", "down")
                    val ok = screenAutomation.scroll(dir)
                    AgentActionResult(action.action, ok, if (ok) "Scrolled $dir" else "Scroll failed")
                }

                AgentAction.ACTION_SWIPE -> {
                    val sx = action.getFloatParam("startX", 540f)
                    val sy = action.getFloatParam("startY", 1800f)
                    val ex = action.getFloatParam("endX", 540f)
                    val ey = action.getFloatParam("endY", 600f)
                    val ok = screenAutomation.swipe(sx, sy, ex, ey)
                    AgentActionResult(action.action, ok, if (ok) "Swiped successfully" else "Swipe failed")
                }

                AgentAction.ACTION_PRESS_BACK -> {
                    val ok = screenAutomation.pressBack()
                    AgentActionResult(action.action, ok, if (ok) "Pressed Back" else "Press Back failed")
                }

                AgentAction.ACTION_PRESS_HOME -> {
                    val ok = screenAutomation.pressHome()
                    AgentActionResult(action.action, ok, if (ok) "Pressed Home" else "Press Home failed")
                }

                AgentAction.ACTION_OPEN_NOTIFICATIONS -> {
                    val ok = screenAutomation.openNotifications()
                    AgentActionResult(action.action, ok, if (ok) "Opened notifications" else "Failed opening notifications")
                }

                AgentAction.ACTION_EXECUTE_TASK -> {
                    val goal = action.getStringParam("goal").ifEmpty { action.response }
                    val summary = taskExecutor.executeTask(
                        goal = goal,
                        onProgress = { step, msg -> onProgress?.invoke("Step $step: $msg") },
                        onStepsUpdated = onStepsUpdated
                    )
                    val taskState = com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.state.value
                    val isSuccess = !taskState.isInterrupted && !taskState.isFailed &&
                            !summary.startsWith("Task Failed") && !summary.startsWith("Task Incomplete")
                    AgentActionResult(action.action, isSuccess, summary)
                }

                else -> {
                    AgentActionResult(action.action, true, action.response.ifEmpty { "Completed" })
                }
            }
            if (action.action != AgentAction.ACTION_EXECUTE_TASK) {
                val finalStatus = if (result.success) com.textmorph.app.agent.model.AgentStepStatus.COMPLETED else com.textmorph.app.agent.model.AgentStepStatus.FAILED
                onStepsUpdated?.invoke(listOf(initialStep.copy(status = finalStatus, detail = result.details)))
            }
            if (!result.success) {
                AppLogger.w(TAG, "Action '${action.action}' failed: ${result.details}")
            }
            result
        } catch (ce: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "Agent action '${action.action}' cancelled by user.")
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing action ${action.action}: ${e.message}", e)
            AgentActionResult(action.action, false, "Error: ${e.message}")
        }
    }

    fun cancelRunningTask() {
        taskExecutor.cancel()
    }
}
