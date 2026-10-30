package com.textmorph.app.agent.parser

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * High-performance parser for Code-as-Action (executable function calling syntax).
 * Enables LLMs to emit concise commands like `call("nouh")` or `open_app("YouTube")`
 * instead of bulky JSON, reducing token generation overhead by up to 70%.
 */
object CodeActionParser {

    private const val TAG = "CodeActionParser"

    // Regex matching command pattern: actionName(...) with optional surrounding code fences
    private val CALL_REGEX = Regex(
        """(?:```(?:python|code|js|bash)?\s*)?`?([a-zA-Z_][a-zA-Z0-9_]*)\s*\(([\s\S]*?)\)`?(?:\s*```)?"""
    )

    fun parse(rawText: String): AgentAction? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank() || trimmed.startsWith("{")) return null

        try {
            // Find first line or block containing a function call pattern
            val match = CALL_REGEX.find(trimmed) ?: return null
            val rawFunc = match.groupValues[1].lowercase().trim()
            val rawArgs = match.groupValues[2].trim()

            // If it matches common non-action programming keywords, skip
            if (rawFunc in listOf(
                    "if", "for", "while", "return", "val", "var", "def", "function",
                    "println", "print", "console", "log", "import", "package", "class", "interface", "fun"
                )) {
                return null
            }

            val parsedArgs = parseArgumentTokens(rawArgs)
            val action = mapToCanonicalAction(rawFunc, parsedArgs)
            if (action != null) {
                AppLogger.d(TAG, "Successfully parsed Code-as-Action: $rawFunc -> ${action.action}")
            }
            return action
        } catch (e: Exception) {
            AppLogger.d(TAG, "CodeActionParser failed on input: ${e.message}")
            return null
        }
    }

    private fun mapToCanonicalAction(funcName: String, args: List<ArgToken>): AgentAction? {
        val positional = args.filter { it.name == null }.map { it.value }
        val named = args.filter { it.name != null }.associate { it.name!! to it.value }

        fun getArg(posIndex: Int, vararg namedKeys: String, default: String = ""): String {
            for (key in namedKeys) {
                named[key]?.let { if (it.isNotBlank()) return it }
            }
            return if (posIndex < positional.size) positional[posIndex] else default
        }

        fun toJsonParams(map: Map<String, String>): Map<String, JsonElement> {
            return map.filterValues { it.isNotBlank() }.mapValues { (_, v) ->
                v.toIntOrNull()?.let { JsonPrimitive(it) }
                    ?: v.toFloatOrNull()?.let { JsonPrimitive(it) }
                    ?: v.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                    ?: JsonPrimitive(v)
            }
        }

        return when (funcName) {
            "open_app", "launch", "launch_app", "app" -> {
                val appName = getArg(0, "app_name", "name", "app")
                AgentAction(
                    action = AgentAction.ACTION_OPEN_APP,
                    params = toJsonParams(mapOf("app_name" to appName, "name" to appName)),
                    response = "Opening $appName"
                )
            }

            "open_url", "url_open", "browse", "url" -> {
                val url = getArg(0, "url", "link", "target")
                AgentAction(
                    action = AgentAction.ACTION_OPEN_URL,
                    params = toJsonParams(mapOf("url" to url)),
                    response = "Opening $url"
                )
            }

            "launch_package" -> {
                val pkg = getArg(0, "package_name", "package", "pkg")
                AgentAction(
                    action = AgentAction.ACTION_LAUNCH_PACKAGE,
                    params = toJsonParams(mapOf("package_name" to pkg)),
                    response = "Launching package $pkg"
                )
            }

            "call", "make_call" -> {
                val target = getArg(0, "contact_name", "name", "phone_number", "phone")
                val isPhone = target.all { it.isDigit() || it == '+' || it == ' ' || it == '-' } && target.any { it.isDigit() }
                val params = if (isPhone) mapOf("phone_number" to target) else mapOf("contact_name" to target)
                AgentAction(
                    action = AgentAction.ACTION_MAKE_CALL,
                    params = toJsonParams(params),
                    response = "Calling $target"
                )
            }

            "send_sms", "sms" -> {
                val target = getArg(0, "recipient", "contact_name", "name", "phone_number", "phone", "to")
                val msg = getArg(1, "message", "text", "body")
                val isPhone = target.all { it.isDigit() || it == '+' || it == ' ' || it == '-' } && target.any { it.isDigit() }
                val params = mutableMapOf("message" to msg, "recipient" to target)
                if (isPhone) params["phone_number"] = target else params["contact_name"] = target
                AgentAction(
                    action = AgentAction.ACTION_SEND_SMS,
                    params = toJsonParams(params),
                    response = "Sending message to $target"
                )
            }

            "send_email", "email" -> {
                val to = getArg(0, "to", "recipient")
                val subject = getArg(1, "subject", "title")
                val body = getArg(2, "body", "content", "text")
                AgentAction(
                    action = AgentAction.ACTION_SEND_EMAIL,
                    params = toJsonParams(mapOf("to" to to, "subject" to subject, "body" to body)),
                    response = "Preparing email to $to"
                )
            }

            "search_contact", "contact", "find_contact" -> {
                val query = getArg(0, "query", "name", "search")
                AgentAction(
                    action = AgentAction.ACTION_SEARCH_CONTACT,
                    params = toJsonParams(mapOf("query" to query)),
                    response = "Searching contacts for '$query'"
                )
            }

            "click_text", "tap_text" -> {
                val text = getArg(0, "text", "label", "target")
                AgentAction(
                    action = AgentAction.ACTION_CLICK_TEXT,
                    params = toJsonParams(mapOf("text" to text)),
                    response = "Tapping \"$text\""
                )
            }

            "click_element", "click_id", "tap_element" -> {
                val id = getArg(0, "id", "index", "element_id").filter { it.isDigit() }
                AgentAction(
                    action = AgentAction.ACTION_CLICK_ELEMENT,
                    params = toJsonParams(mapOf("element_id" to id)),
                    response = "Tapping element [$id]"
                )
            }

            "click_at", "tap_at", "tap" -> {
                val x = getArg(0, "x")
                val y = getArg(1, "y")
                AgentAction(
                    action = AgentAction.ACTION_CLICK_AT,
                    params = toJsonParams(mapOf("x" to x, "y" to y)),
                    response = "Tapping screen at ($x, $y)"
                )
            }

            "click" -> {
                val arg0 = getArg(0, "text", "x", "id")
                val arg1 = getArg(1, "y")
                if (arg1.isNotBlank()) {
                    AgentAction(
                        action = AgentAction.ACTION_CLICK_AT,
                        params = toJsonParams(mapOf("x" to arg0, "y" to arg1)),
                        response = "Tapping at ($arg0, $arg1)"
                    )
                } else if (arg0.all { it.isDigit() } && arg0.toIntOrNull() != null && arg0.toInt() < 100) {
                    AgentAction(
                        action = AgentAction.ACTION_CLICK_ELEMENT,
                        params = toJsonParams(mapOf("element_id" to arg0)),
                        response = "Tapping element [$arg0]"
                    )
                } else if (arg0.all { it.isDigit() || it == '.' }) {
                    AgentAction(
                        action = AgentAction.ACTION_CLICK_AT,
                        params = toJsonParams(mapOf("x" to arg0, "y" to arg1)),
                        response = "Tapping at ($arg0, $arg1)"
                    )
                } else {
                    AgentAction(
                        action = AgentAction.ACTION_CLICK_TEXT,
                        params = toJsonParams(mapOf("text" to arg0)),
                        response = "Tapping \"$arg0\""
                    )
                }
            }

            "type_text", "type" -> {
                val text = getArg(0, "text", "content", "value")
                val hint = getArg(1, "field_hint", "hint")
                AgentAction(
                    action = AgentAction.ACTION_TYPE_TEXT,
                    params = toJsonParams(mapOf("text" to text, "field_hint" to hint)),
                    response = "Typing \"$text\""
                )
            }

            "press_enter", "enter" -> AgentAction(
                action = AgentAction.ACTION_PRESS_ENTER,
                response = "Submitting keyboard action"
            )

            "scroll" -> {
                val dir = getArg(0, "direction", "dir", default = "down")
                AgentAction(
                    action = AgentAction.ACTION_SCROLL,
                    params = toJsonParams(mapOf("direction" to dir)),
                    response = "Scrolling $dir"
                )
            }

            "swipe" -> {
                val sx = getArg(0, "startX", "sx", default = "540")
                val sy = getArg(1, "startY", "sy", default = "1800")
                val ex = getArg(2, "endX", "ex", default = "540")
                val ey = getArg(3, "endY", "ey", default = "600")
                AgentAction(
                    action = AgentAction.ACTION_SWIPE,
                    params = toJsonParams(mapOf("startX" to sx, "startY" to sy, "endX" to ex, "endY" to ey)),
                    response = "Swiping screen"
                )
            }

            "press_back", "back" -> AgentAction(
                action = AgentAction.ACTION_PRESS_BACK,
                response = "Pressing Back"
            )

            "press_home", "home" -> AgentAction(
                action = AgentAction.ACTION_PRESS_HOME,
                response = "Pressing Home"
            )

            "open_notifications", "notifications" -> AgentAction(
                action = AgentAction.ACTION_OPEN_NOTIFICATIONS,
                response = "Opening notifications"
            )

            "read_screen", "view_screen", "inspect_screen", "screen" -> AgentAction(
                action = AgentAction.ACTION_READ_SCREEN,
                response = "Inspecting visible screen"
            )

            "set_alarm", "alarm" -> {
                val hr = getArg(0, "hour", "h", default = "7")
                val min = getArg(1, "minute", "m", default = "0")
                val label = getArg(2, "label", "title", default = "Alarm")
                AgentAction(
                    action = AgentAction.ACTION_SET_ALARM,
                    params = toJsonParams(mapOf("hour" to hr, "minute" to min, "label" to label)),
                    response = "Setting alarm for $hr:$min"
                )
            }

            "set_timer", "timer" -> {
                val sec = getArg(0, "seconds", "sec", "s", default = "60")
                val label = getArg(1, "label", "title", default = "Timer")
                AgentAction(
                    action = AgentAction.ACTION_SET_TIMER,
                    params = toJsonParams(mapOf("seconds" to sec, "label" to label)),
                    response = "Setting timer for ${sec}s"
                )
            }

            "set_volume", "volume" -> {
                val lvl = getArg(0, "level", "lvl", default = "50")
                AgentAction(
                    action = AgentAction.ACTION_SET_VOLUME,
                    params = toJsonParams(mapOf("level" to lvl)),
                    response = "Setting volume to $lvl%"
                )
            }

            "set_brightness", "brightness" -> {
                val lvl = getArg(0, "level", "lvl", default = "50")
                AgentAction(
                    action = AgentAction.ACTION_SET_BRIGHTNESS,
                    params = toJsonParams(mapOf("level" to lvl)),
                    response = "Setting brightness to $lvl%"
                )
            }

            "execute_task", "task" -> {
                val goal = getArg(0, "goal", "task", "prompt")
                AgentAction(
                    action = AgentAction.ACTION_EXECUTE_TASK,
                    params = toJsonParams(mapOf("goal" to goal)),
                    response = "Executing multi-step task: $goal"
                )
            }

            "done", "complete", "finish" -> {
                val summary = getArg(0, "summary", "response", "message", default = "Task complete.")
                AgentAction(
                    action = AgentAction.ACTION_DONE,
                    params = toJsonParams(mapOf("summary" to summary)),
                    response = summary,
                    isComplete = true
                )
            }

            "wait", "sleep", "delay" -> {
                val ms = getArg(0, "ms", "duration", "duration_ms", "time", default = "1000")
                AgentAction(
                    action = AgentAction.ACTION_WAIT,
                    params = toJsonParams(mapOf("ms" to ms)),
                    response = "Waiting ${ms}ms"
                )
            }

            "search_web", "web_search", "google", "ddg" -> {
                val query = getArg(0, "query", "q", "search")
                AgentAction(
                    action = AgentAction.ACTION_SEARCH_WEB,
                    params = toJsonParams(mapOf("query" to query)),
                    response = "Searching web for '$query'"
                )
            }

            "fetch_page", "fetch_url", "crawl_url", "read_page" -> {
                val url = getArg(0, "url", "link", "target")
                val charLimit = getArg(1, "char_limit", "limit", default = "3000")
                AgentAction(
                    action = AgentAction.ACTION_FETCH_PAGE,
                    params = toJsonParams(mapOf("url" to url, "char_limit" to charLimit)),
                    response = "Fetching webpage $url"
                )
            }

            "create_js_tool", "add_js_tool" -> {
                val id = getArg(0, "id", "tool_id", "name")
                val name = getArg(1, "name", "tool_name", default = id)
                val desc = getArg(2, "description", "desc")
                val sig = getArg(3, "signature", "code_signature", default = "$id()")
                val code = getArg(4, "code", "script")
                AgentAction(
                    action = AgentAction.ACTION_CREATE_JS_TOOL,
                    params = toJsonParams(mapOf("id" to id, "name" to name, "description" to desc, "signature" to sig, "code" to code)),
                    response = "Creating custom JavaScript tool '$name'"
                )
            }

            else -> {
                val toolDef = com.textmorph.app.agent.tools.registry.AgentToolRegistry.findTool(funcName)
                val paramsMap = mutableMapOf<String, String>()
                if (toolDef != null) {
                    toolDef.parameters.forEachIndexed { idx, param ->
                        val value = getArg(idx, param.name, default = param.defaultValue)
                        if (value.isNotBlank()) paramsMap[param.name] = value
                    }
                } else {
                    named.forEach { (k, v) -> paramsMap[k] = v }
                    positional.forEachIndexed { idx, v ->
                        paramsMap["arg$idx"] = v
                    }
                    if (positional.isNotEmpty()) {
                        if (!paramsMap.containsKey("query")) paramsMap["query"] = positional[0]
                        if (!paramsMap.containsKey("location")) paramsMap["location"] = positional[0]
                        if (!paramsMap.containsKey("value")) paramsMap["value"] = positional[0]
                        if (!paramsMap.containsKey("text")) paramsMap["text"] = positional[0]
                    }
                }
                AgentAction(
                    action = toolDef?.id ?: funcName,
                    params = toJsonParams(paramsMap),
                    response = "Executing ${toolDef?.name ?: funcName}"
                )
            }
        }
    }

    private data class ArgToken(val name: String?, val value: String)

    private fun parseArgumentTokens(argsStr: String): List<ArgToken> {
        if (argsStr.isBlank()) return emptyList()

        val tokens = mutableListOf<ArgToken>()
        var i = 0
        val len = argsStr.length

        while (i < len) {
            while (i < len && (argsStr[i].isWhitespace() || argsStr[i] == ',')) i++
            if (i >= len) break

            var argName: String? = null
            val start = i

            // Check if argument is named (e.g. name="value" or key=123)
            val equalsIndex = argsStr.indexOf('=', i)
            val commaIndex = argsStr.indexOf(',', i)
            val quoteIndex = minOf(
                argsStr.indexOf('"', i).takeIf { it != -1 } ?: Int.MAX_VALUE,
                argsStr.indexOf('\'', i).takeIf { it != -1 } ?: Int.MAX_VALUE
            )

            if (equalsIndex != -1 && equalsIndex < quoteIndex && (commaIndex == -1 || equalsIndex < commaIndex)) {
                argName = argsStr.substring(i, equalsIndex).trim()
                i = equalsIndex + 1
                while (i < len && argsStr[i].isWhitespace()) i++
            }

            if (i >= len) break

            // Parse argument value (quoted string vs raw literal)
            val charAt = argsStr[i]
            val value = if (charAt == '"' || charAt == '\'') {
                val quote = charAt
                i++
                val valStart = i
                val sb = StringBuilder()
                while (i < len) {
                    if (argsStr[i] == '\\' && i + 1 < len) {
                        sb.append(argsStr[i + 1])
                        i += 2
                    } else if (argsStr[i] == quote) {
                        break
                    } else {
                        sb.append(argsStr[i])
                        i++
                    }
                }
                if (i < len && argsStr[i] == quote) i++
                sb.toString()
            } else {
                val valStart = i
                while (i < len && argsStr[i] != ',' && argsStr[i] != ')') {
                    i++
                }
                argsStr.substring(valStart, i).trim()
            }

            tokens.add(ArgToken(argName, value))
        }

        return tokens
    }
}
