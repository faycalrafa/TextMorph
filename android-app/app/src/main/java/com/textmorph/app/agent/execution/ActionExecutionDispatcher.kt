package com.textmorph.app.agent.execution

import com.textmorph.app.agent.memory.ActionStep
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.tools.AppLauncherTool
import com.textmorph.app.agent.tools.ScreenAutomationTool

/**
 * Handles physical and accessibility execution of discrete agent actions
 * for live step runs and skill memory replays.
 */
class ActionExecutionDispatcher(
    private val appLauncher: AppLauncherTool,
    private val screenTool: ScreenAutomationTool
) {

    fun executeAction(action: AgentAction): Pair<Boolean, String> {
        return when (action.action) {
            "click_text" -> {
                val text = action.getStringParamWithAliases("text", "label")
                val ok = screenTool.clickText(text)
                Pair(ok, if (ok) "Clicked \"$text\"" else "Could not find \"$text\" to click")
            }
            "click_at" -> {
                val x = action.getFloatParam("x")
                val y = action.getFloatParam("y")
                val ok = screenTool.clickAt(x, y)
                Pair(ok, if (ok) "Clicked at ($x, $y)" else "Click failed")
            }
            "click_element", "click_id" -> {
                val id = action.getIntParamWithAliases("element_id", "id", "index", default = -1)
                val ok = if (id >= 0) screenTool.clickElement(id) else false
                Pair(ok, if (ok) "Clicked element [$id]" else "Click element failed")
            }
            "type_text" -> {
                val text = action.getStringParamWithAliases("text", "content")
                val hint = action.getStringParamWithAliases("field_hint", "hint").ifEmpty { null }
                val ok = screenTool.typeText(text, hint)
                Pair(ok, if (ok) "Typed \"$text\"" else "Could not type text")
            }
            "press_enter" -> {
                val ok = screenTool.pressEnter()
                Pair(ok, if (ok) "Submitted the focused search/form field" else "Could not submit the focused field")
            }
            "scroll" -> {
                val dir = action.getStringParam("direction", "down")
                val ok = screenTool.scroll(dir)
                Pair(ok, if (ok) "Scrolled $dir" else "Could not scroll $dir")
            }
            "swipe" -> {
                val sx = action.getFloatParam("startX", 540f)
                val sy = action.getFloatParam("startY", 1800f)
                val ex = action.getFloatParam("endX", 540f)
                val ey = action.getFloatParam("endY", 600f)
                val ok = screenTool.swipe(sx, sy, ex, ey)
                Pair(ok, "Swiped from ($sx,$sy) to ($ex,$ey)")
            }
            "press_back" -> Pair(screenTool.pressBack(), "Pressed back")
            "press_home" -> Pair(screenTool.pressHome(), "Pressed home")
            "open_app" -> {
                val app = action.getStringParamWithAliases("app_name", "name", "app")
                val res = appLauncher.openApp(app)
                Pair(res.isSuccess, if (res.isSuccess) "Opened $app" else "Failed opening $app")
            }
            "wait" -> Pair(true, "Waited")
            "done" -> Pair(true, "Task complete")
            else -> Pair(false, "Unknown action: ${action.action}")
        }
    }

    fun executeStep(step: ActionStep): Pair<Boolean, String> {
        return when (step.action) {
            "click_text" -> {
                val text = step.params["text"].orEmpty()
                val ok = screenTool.clickText(text)
                Pair(ok, if (ok) "Clicked \"$text\"" else "Could not find \"$text\" to click")
            }
            "click_at" -> {
                val x = step.params["x"]?.toFloatOrNull() ?: 0f
                val y = step.params["y"]?.toFloatOrNull() ?: 0f
                val ok = screenTool.clickAt(x, y)
                Pair(ok, if (ok) "Clicked at ($x, $y)" else "Click failed")
            }
            "type_text" -> {
                val text = step.params["text"].orEmpty()
                val hint = step.params["field_hint"]
                val ok = screenTool.typeText(text, hint)
                Pair(ok, if (ok) "Typed \"$text\"" else "Could not type text")
            }
            "press_enter" -> {
                val ok = screenTool.pressEnter()
                Pair(ok, if (ok) "Submitted field" else "Could not submit field")
            }
            "scroll" -> {
                val dir = step.params["direction"] ?: "down"
                val ok = screenTool.scroll(dir)
                Pair(ok, if (ok) "Scrolled $dir" else "Could not scroll $dir")
            }
            "swipe" -> {
                val sx = step.params["startX"]?.toFloatOrNull() ?: 540f
                val sy = step.params["startY"]?.toFloatOrNull() ?: 1800f
                val ex = step.params["endX"]?.toFloatOrNull() ?: 540f
                val ey = step.params["endY"]?.toFloatOrNull() ?: 600f
                val ok = screenTool.swipe(sx, sy, ex, ey)
                Pair(ok, "Swiped from ($sx,$sy) to ($ex,$ey)")
            }
            "press_back" -> Pair(screenTool.pressBack(), "Pressed back")
            "press_home" -> Pair(screenTool.pressHome(), "Pressed home")
            "open_app" -> {
                val app = step.params["app_name"].orEmpty()
                val res = appLauncher.openApp(app)
                Pair(res.isSuccess, if (res.isSuccess) "Opened $app" else "Failed opening $app")
            }
            "wait" -> Pair(true, "Waited")
            "done" -> Pair(true, "Done step reached")
            else -> Pair(false, "Unknown action: ${step.action}")
        }
    }

    fun formatActionTitle(action: AgentAction): String {
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
}
