package com.textmorph.app.agent.parser

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Robust extractor and parser for AgentAction JSON payloads from LLM generations.
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
        if (!trimmed.contains("\"action\"")) return null

        try {
            val jsonStr = extractJsonString(trimmed)
            if (jsonStr.isBlank()) return null

            val rootObj = json.parseToJsonElement(jsonStr).jsonObject
            val actionName = rootObj["action"]?.jsonPrimitive?.contentOrNull ?: return null
            val responseText = rootObj["response"]?.jsonPrimitive?.contentOrNull ?: ""
            val reasoning = rootObj["reasoning"]?.jsonPrimitive?.contentOrNull
            val isComplete = rootObj["is_complete"]?.jsonPrimitive?.booleanOrNull ?: (actionName == AgentAction.ACTION_DONE)

            val paramsObj = (rootObj["params"] ?: rootObj["parameters"])
            val paramsMap: Map<String, kotlinx.serialization.json.JsonElement> =
                (paramsObj as? kotlinx.serialization.json.JsonObject)?.toMap() ?: emptyMap()

            return AgentAction(
                action = actionName.trim().lowercase(),
                params = paramsMap,
                response = responseText.ifEmpty { reasoning ?: "Executing $actionName" },
                reasoning = reasoning,
                isComplete = isComplete
            )
        } catch (e: Exception) {
            AppLogger.d(TAG, "Not a valid action JSON payload: ${e.message}")
            return null
        }
    }

    fun parse(rawText: String): AgentAction? = parseAction(rawText)

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
