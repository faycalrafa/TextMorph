package com.textmorph.app.agent.parser

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Robust extractor and parser for AgentAction JSON payloads from LLM generations.
 * Supports dual-syntax parsing (Code-as-Action and JSON) with resilient error recovery
 * and regex-based heuristic parameter extraction for malformed on-device LLM outputs.
 */
object AgentActionParser {

    private const val TAG = "AgentActionParser"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Attempts to parse an AgentAction from the response text.
     * Returns null if the text is natural language without an action block.
     */
    fun parseAction(rawText: String): AgentAction? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return null

        // 1. Dual-Syntax Priority: Try Code-as-Action first (ultra-low token footprint)
        val codeAction = CodeActionParser.parse(trimmed)
        if (codeAction != null) {
            return codeAction
        }

        // 2. Fallback: Parse JSON syntax
        if (!trimmed.contains("\"action\"") && !trimmed.contains("'action'")) {
            // Check if Code-as-Action is embedded somewhere in code fences
            if (trimmed.contains("```")) {
                val fenceContent = trimmed.substringAfter("```").substringBefore("```")
                val embeddedCode = CodeActionParser.parse(fenceContent)
                if (embeddedCode != null) return embeddedCode
            }
            return null
        }

        val jsonStr = extractJsonString(trimmed)
        if (jsonStr.isNotBlank()) {
            // 2a. Strict JSON parse
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
                        (paramsObj as? kotlinx.serialization.json.JsonObject)?.toMap() ?: emptyMap()

                    return AgentAction(
                        action = actionName.trim().lowercase(),
                        params = paramsMap,
                        response = responseText.ifEmpty { reasoning ?: "Executing $actionName" },
                        reasoning = reasoning,
                        isComplete = isComplete
                    )
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Strict JSON parse failed, attempting structural repair: ${e.message}")
            }

            // 2b. Structural JSON repair attempt
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
                            (paramsObj as? kotlinx.serialization.json.JsonObject)?.toMap() ?: emptyMap()

                        AppLogger.i(TAG, "Successfully parsed action '$actionName' after structural JSON repair.")
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

        // 3. Resilient Heuristic & Regex Recovery
        return parseHeuristicAction(trimmed)
    }

    fun parse(rawText: String): AgentAction? = parseAction(rawText)

    /**
     * Repairs common minor syntax errors emitted by small LLMs.
     */
    private fun repairMalformedJson(raw: String): String {
        var s = raw.trim()

        // Fix unclosed params object before reasoning:
        // e.g. "params": { "text": "...", "reasoning": "..." } -> "params": { "text": "..." }, "reasoning": "..."
        s = s.replace(Regex("""("params"|"parameters")\s*:\s*\{([^}]*?)("reason(?:ing)?")\s*:""")) { match ->
            val paramKey = match.groupValues[1]
            var paramBody = match.groupValues[2].trim()
            if (paramBody.endsWith(",")) paramBody = paramBody.dropLast(1).trim()
            """$paramKey: {$paramBody}, ${match.groupValues[3]}:"""
        }

        // Fix misplaced closing brace in params:
        // e.g. "params": {"text": "cats"} , "hint": "..." }, "reasoning" -> "params": {"text": "cats", "hint": "..."}, "reasoning"
        s = s.replace(Regex("""("params"|"parameters")\s*:\s*\{([^}]*)\}\s*,\s*("hint"|"field_hint"|"label")\s*:\s*([^,}]+)\s*\}""")) { match ->
            val paramKey = match.groupValues[1]
            val first = match.groupValues[2].trim()
            val hintKey = match.groupValues[3]
            val hintVal = match.groupValues[4].trim()
            """$paramKey: {$first, $hintKey: $hintVal}"""
        }

        // Remove trailing commas before closing braces
        s = s.replace(Regex(""",\s*([\}\]])"""), "$1")

        // Balance braces
        val openCount = s.count { it == '{' }
        val closeCount = s.count { it == '}' }
        if (openCount > closeCount) {
            s += "}".repeat(openCount - closeCount)
        }

        return s
    }

    /**
     * Fallback extractor using regex to extract action and parameters directly from malformed text.
     */
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
            AgentAction.ACTION_CLICK_TEXT -> {
                extractStringParam("text", "label", "target", "element")
            }
            AgentAction.ACTION_CLICK_ELEMENT -> {
                extractNumberParam("element_id", "id", "index")
            }
            AgentAction.ACTION_CLICK_AT -> {
                extractNumberParam("x")
                extractNumberParam("y")
            }
            AgentAction.ACTION_OPEN_APP -> {
                extractStringParam("app_name", "name", "app", "target")
            }
            AgentAction.ACTION_OPEN_URL -> {
                extractStringParam("url", "link")
            }
            AgentAction.ACTION_SCROLL -> {
                extractStringParam("direction", "dir")
            }
            AgentAction.ACTION_MAKE_CALL, AgentAction.ACTION_SEND_SMS -> {
                extractStringParam("contact_name", "name")
                extractStringParam("phone_number", "phone")
                extractStringParam("message", "text")
            }
            AgentAction.ACTION_EXECUTE_TASK -> {
                extractStringParam("goal", "task", "prompt")
            }
            AgentAction.ACTION_SET_VOLUME, AgentAction.ACTION_SET_BRIGHTNESS -> {
                extractNumberParam("level")
            }
            AgentAction.ACTION_SET_ALARM -> {
                extractNumberParam("hour")
                extractNumberParam("minute")
                extractStringParam("label")
            }
            AgentAction.ACTION_SET_TIMER -> {
                extractNumberParam("seconds")
                extractStringParam("label")
            }
            AgentAction.ACTION_DONE -> {
                extractStringParam("summary", "response")
            }
        }

        AppLogger.i(TAG, "Recovered action '$actionName' from malformed payload via heuristic parser.")
        return AgentAction(
            action = actionName,
            params = params,
            response = reasoning ?: "Executing $actionName",
            reasoning = reasoning,
            isComplete = isComplete
        )
    }

    fun stripActionPayload(rawText: String): String {
        val codeBlockMatch = Regex("```(?:json)?\\s*\\{[\\s\\S]*?\\}\\s*```").find(rawText)
        if (codeBlockMatch != null) {
            return rawText.replace(codeBlockMatch.value, "").trim()
        }
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start && rawText.contains("\"action\"")) {
            return (rawText.substring(0, start) + rawText.substring(end + 1)).trim()
        }
        // Strip Code-as-Action function command if isolated or wrapped in code fences
        val cmdMatch = Regex("""(?:```(?:python|code|js|bash)?\s*)?`?[a-zA-Z_][a-zA-Z0-9_]*\s*\([\s\S]*?\)`?(?:\s*```)?""").find(rawText)
        if (cmdMatch != null && cmdMatch.value.isNotBlank() && !rawText.contains("\n\n")) {
            return rawText.replace(cmdMatch.value, "").trim()
        }
        return rawText.trim()
    }

    fun cleanResponse(rawText: String): String = stripActionPayload(rawText)

    private fun extractJsonString(text: String): String {
        // 1. Check for markdown code blocks (```json ... ```)
        val codeBlockMatch = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```").find(text)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1].trim()
        }

        // 2. Fallback: Find outermost curly braces
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1).trim()
        }

        // 3. Handle model omitting closing brace
        if (start != -1 && end == -1) {
            return text.substring(start).trim() + "\n}"
        }

        return ""
    }
}
