package com.textmorph.app.agent.parser

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * High-performance, fault-tolerant Code-as-Action parser for on-device LLMs.
 * Standardizes exclusively on Code-as-Action while providing resilient error recovery
 * and regex-based heuristic parameter extraction for legacy compatibility.
 */
object CodeActionParser {

    private const val TAG = "CodeActionParser"
    private val CALL_REGEX = Regex("""(?:^|[\s`])([a-zA-Z0-9_]+)\s*\(([\s\S]*?)\)""")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(rawText: String): AgentAction? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return null

        // 1. Dual-Syntax Priority: Try Code-as-Action first (ultra-low token footprint)
        val codeAction = parseCodeAction(trimmed)
        if (codeAction != null) {
            return codeAction
        }

        // 2. Fallback: Parse JSON syntax
        if (!trimmed.contains("\"action\"") && !trimmed.contains("'action'")) {
            if (trimmed.contains("```")) {
                val fenceContent = trimmed.substringAfter("```").substringBefore("```")
                val embeddedCode = parseCodeAction(fenceContent)
                if (embeddedCode != null) return embeddedCode
            }
            return null
        }

        val jsonStr = extractJsonString(trimmed)
        if (jsonStr.isNotBlank()) {
            // Strict JSON parse
            try {
                val rootObj = json.parseToJsonElement(jsonStr).jsonObject
                val actionName = rootObj["action"]?.jsonPrimitive?.contentOrNull
                if (!actionName.isNullOrBlank()) {
                    val responseText = rootObj["response"]?.jsonPrimitive?.contentOrNull ?: ""
                    val reasoning = rootObj["reasoning"]?.jsonPrimitive?.contentOrNull
                        ?: rootObj["reason"]?.jsonPrimitive?.contentOrNull
                    val isComplete = rootObj["is_complete"]?.jsonPrimitive?.booleanOrNull
                        ?: (actionName.trim().lowercase() == AgentAction.ACTION_DONE)

                    val paramsObj = (rootObj["params"] ?: rootObj["parameters"])
                    val paramsMap: Map<String, JsonElement> =
                        (paramsObj as? JsonObject)?.toMap() ?: emptyMap()

                    return AgentAction(
                        action = actionName.trim().lowercase(),
                        params = paramsMap,
                        response = responseText.ifEmpty { reasoning ?: "Executing $actionName" },
                        reasoning = reasoning,
                        isComplete = isComplete
                    )
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Strict JSON parse failed, attempting repair: ${e.message}")
            }

            // Structural repair attempt
            val repaired = repairMalformedJson(jsonStr)
            if (repaired != jsonStr) {
                try {
                    val rootObj = json.parseToJsonElement(repaired).jsonObject
                    val actionName = rootObj["action"]?.jsonPrimitive?.contentOrNull
                    if (!actionName.isNullOrBlank()) {
                        val responseText = rootObj["response"]?.jsonPrimitive?.contentOrNull ?: ""
                        val reasoning = rootObj["reasoning"]?.jsonPrimitive?.contentOrNull
                            ?: rootObj["reason"]?.jsonPrimitive?.contentOrNull
                        val isComplete = rootObj["is_complete"]?.jsonPrimitive?.booleanOrNull
                            ?: (actionName.trim().lowercase() == AgentAction.ACTION_DONE)

                        val paramsObj = (rootObj["params"] ?: rootObj["parameters"])
                        val paramsMap: Map<String, JsonElement> =
                            (paramsObj as? JsonObject)?.toMap() ?: emptyMap()

                        return AgentAction(
                            action = actionName.trim().lowercase(),
                            params = paramsMap,
                            response = responseText.ifEmpty { reasoning ?: "Executing $actionName" },
                            reasoning = reasoning,
                            isComplete = isComplete
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Repaired JSON parse failed: ${e.message}")
                }
            }
        }

        // Heuristic & regex recovery
        return parseHeuristicAction(trimmed)
    }

    fun parseAction(rawText: String): AgentAction? = parse(rawText)

    private fun parseCodeAction(text: String): AgentAction? {
        try {
            val match = CALL_REGEX.find(text) ?: return null
            val rawFunc = match.groupValues[1].lowercase().trim()
            val rawArgs = match.groupValues[2].trim()

            if (rawFunc in listOf(
                    "if", "for", "while", "return", "val", "var", "def",
                    "println", "print", "console", "log", "import", "package", "class", "interface", "fun"
                )) {
                return null
            }

            val parsedArgs = CodeActionMapper.parseArgumentTokens(rawArgs)
            return CodeActionMapper.mapToCanonicalAction(rawFunc, parsedArgs)
        } catch (e: Exception) {
            AppLogger.d(TAG, "CodeActionParser code parse error: ${e.message}")
            return null
        }
    }

    private fun extractJsonString(text: String): String {
        val codeBlockMatch = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```").find(text)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1].trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1).trim()
        }
        if (start != -1 && end == -1) {
            return text.substring(start).trim() + "\n}"
        }
        return ""
    }

    private fun repairMalformedJson(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("""("params"|"parameters")\s*:\s*\{([^}]*?)("reason(?:ing)?")\s*:""")) { match ->
            val paramKey = match.groupValues[1]
            var paramBody = match.groupValues[2].trim()
            if (paramBody.endsWith(",")) paramBody = paramBody.dropLast(1).trim()
            """$paramKey: {$paramBody}, ${match.groupValues[3]}:"""
        }
        s = s.replace(Regex("""("params"|"parameters")\s*:\s*\{([^}]*)\}\s*,\s*("hint"|"field_hint"|"label")\s*:\s*([^,}]+)\s*\}""")) { match ->
            val paramKey = match.groupValues[1]
            val first = match.groupValues[2].trim()
            val hintKey = match.groupValues[3]
            val hintVal = match.groupValues[4].trim()
            """$paramKey: {$first, $hintKey: $hintVal}"""
        }
        s = s.replace(Regex(""",\s*([\}\]])"""), "$1")
        val openCount = s.count { it == '{' }
        val closeCount = s.count { it == '}' }
        if (openCount > closeCount) {
            s += "}".repeat(openCount - closeCount)
        }
        return s
    }

    private fun parseHeuristicAction(rawText: String): AgentAction? {
        val actionMatch = Regex("""["']action["']\s*:\s*["']([a-zA-Z_]+)["']""").find(rawText) ?: return null
        val actionName = actionMatch.groupValues[1].trim().lowercase()

        val reasoningMatch = Regex("""["'](?:reasoning|reason)["']\s*:\s*["']((?:\\.|[^"'\\])*)["']""").find(rawText)
        val reasoning = reasoningMatch?.groupValues?.get(1)?.trim()

        val isCompleteMatch = Regex("""["']is_complete["']\s*:\s*(true|false)""").find(rawText)
        val isComplete = isCompleteMatch?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: (actionName == AgentAction.ACTION_DONE)

        val params = mutableMapOf<String, JsonElement>()

        fun extractStringParam(key: String, vararg aliases: String) {
            val allKeys = listOf(key) + aliases
            for (k in allKeys) {
                val match = Regex("""["']$k["']\s*:\s*["']((?:\\.|[^"'\\])*)["']""").find(rawText)
                if (match != null && match.groupValues[1].isNotBlank()) {
                    params[key] = JsonPrimitive(match.groupValues[1].trim())
                    return
                }
            }
        }

        fun extractNumberParam(key: String, vararg aliases: String) {
            val allKeys = listOf(key) + aliases
            for (k in allKeys) {
                val match = Regex("""["']$k["']\s*:\s*([0-9]+(?:\.[0-9]+)?)""").find(rawText)
                if (match != null) {
                    val numStr = match.groupValues[1]
                    val intVal = numStr.toIntOrNull()
                    if (intVal != null) {
                        params[key] = JsonPrimitive(intVal)
                    } else {
                        numStr.toFloatOrNull()?.let { params[key] = JsonPrimitive(it) }
                    }
                    return
                }
            }
        }

        when (actionName) {
            AgentAction.ACTION_TYPE_TEXT -> {
                extractStringParam("text", "content", "value")
                extractStringParam("field_hint", "hint")
                if (!params.containsKey("text")) {
                    val fallbackTextMatch = Regex("""(?:["']text["']\s*:\s*["']?|["']params["']\s*:\s*\{\s*["'])([^"':\}\n]+)""").find(rawText)
                    val textVal = fallbackTextMatch?.groupValues?.get(1)?.trim()
                    if (!textVal.isNullOrBlank() && !textVal.startsWith("action")) {
                        params["text"] = JsonPrimitive(textVal)
                    }
                }
            }
            AgentAction.ACTION_CLICK_TEXT -> extractStringParam("text", "label", "target", "element")
            AgentAction.ACTION_CLICK_ELEMENT -> extractNumberParam("element_id", "id", "index")
            AgentAction.ACTION_CLICK_AT -> {
                extractNumberParam("x")
                extractNumberParam("y")
            }
            AgentAction.ACTION_OPEN_APP -> extractStringParam("app_name", "name", "app", "target")
            AgentAction.ACTION_OPEN_URL -> extractStringParam("url", "link")
            AgentAction.ACTION_SCROLL -> extractStringParam("direction", "dir")
            AgentAction.ACTION_MAKE_CALL, AgentAction.ACTION_SEND_SMS -> {
                extractStringParam("contact_name", "name")
                extractStringParam("phone_number", "phone")
                extractStringParam("message", "text")
            }
            AgentAction.ACTION_EXECUTE_TASK -> extractStringParam("goal", "task", "prompt")
            AgentAction.ACTION_SET_VOLUME, AgentAction.ACTION_SET_BRIGHTNESS -> extractNumberParam("level")
            AgentAction.ACTION_SET_ALARM -> {
                extractNumberParam("hour")
                extractNumberParam("minute")
                extractStringParam("label")
            }
            AgentAction.ACTION_SET_TIMER -> {
                extractNumberParam("seconds")
                extractStringParam("label")
            }
            AgentAction.ACTION_DONE -> extractStringParam("summary", "response")
        }

        return AgentAction(
            action = actionName,
            params = params,
            response = reasoning ?: "Executing $actionName",
            reasoning = reasoning,
            isComplete = isComplete
        )
    }

    fun hasCompleteAction(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false

        // If a code fence is present, it must be closed
        if (trimmed.contains("```")) {
            val firstFence = trimmed.indexOf("```")
            val secondFence = trimmed.indexOf("```", firstFence + 3)
            if (secondFence == -1) return false
            val fencedBlock = trimmed.substring(firstFence, secondFence + 3)
            return CALL_REGEX.containsMatchIn(fencedBlock)
        }

        val match = CALL_REGEX.find(trimmed) ?: return false
        val func = match.groupValues[1].lowercase().trim()
        val keywords = listOf("if", "for", "while", "return", "val", "var", "def", "fun")
        if (func in keywords) return false
        return match.range.last <= trimmed.length - 1
    }

    fun stripActionPayload(rawText: String): String {
        var text = rawText
        val codeBlockMatch = Regex("```(?:json)?\\s*\\{[\\s\\S]*?\\}\\s*```").find(text)
        if (codeBlockMatch != null) {
            return text.replace(codeBlockMatch.value, "").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start && text.contains("\"action\"")) {
            return (text.substring(0, start) + text.substring(end + 1)).trim()
        }
        val cmdMatch = Regex("""(?:```(?:cmd|python|code|js|bash)?\s*)?`?[a-zA-Z_][a-zA-Z0-9_]*\s*\([\s\S]*?\)`?(?:\s*```)?""").find(text)
        if (cmdMatch != null && cmdMatch.value.isNotBlank() && !text.contains("\n\n")) {
            return text.replace(cmdMatch.value, "").trim()
        }
        return text.trim()
    }

    fun cleanResponse(rawText: String): String = stripActionPayload(rawText)
}
