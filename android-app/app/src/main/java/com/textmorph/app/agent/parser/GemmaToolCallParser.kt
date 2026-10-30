package com.textmorph.app.agent.parser

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resilient parser for Google Gemma native function calling control tokens:
 * `<|tool_call|>call:function_name{param:<|"|>value<|"|>}<tool_call|>`
 * as well as XML/Hermes format `<tool_call>...</tool_call>`.
 */
object GemmaToolCallParser {

    private const val TAG = "GemmaToolCallParser"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Matches Gemma native tool calling tags:
    // e.g. <|tool_call|>call:search_web{query:<|"|>Android 17 newest features<|"|>}<tool_call|>
    private val GEMMA_CALL_REGEX = Regex(
        """<\|?tool_call\|?>\s*(?:call:)?([a-zA-Z_][a-zA-Z0-9_]*)\s*\{([\s\S]*?)\}\s*(?:<tool_call\|?>|<\/tool_call>|<\|\/tool_call\|?>)?""",
        RegexOption.IGNORE_CASE
    )

    // Fallback for XML tool_call blocks
    private val XML_TOOL_CALL_REGEX = Regex(
        """<tool_call>\s*([\s\S]*?)\s*<\/tool_call>""",
        RegexOption.IGNORE_CASE
    )

    // Tokenizer regex for Gemma key-value pairs inside {...}
    // Key is followed by ':' and then either a <|"|>...<|"|> string, [...] array, "..." quote, or bare literal
    private val GEMMA_ARG_REGEX = Regex(
        """([a-zA-Z_][a-zA-Z0-9_]*)\s*:\s*(?:<\|"\|>([\s\S]*?)<\|"\|>|\[([\s\S]*?)\]|"([^"\\]*(?:\\.[^"\\]*)*)"|'([^'\\]*(?:\\.[^'\\]*)*)'|([^,}\s]+))"""
    )

    /**
     * Determines whether the raw text contains a Gemma or XML tool call token.
     */
    fun hasToolCall(text: String): Boolean {
        return text.contains("tool_call", ignoreCase = true) ||
            text.contains("<|tool_call", ignoreCase = true)
    }

    /**
     * Attempts to parse a Gemma or XML tool call from model output.
     */
    fun parse(rawText: String): AgentAction? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank() || !hasToolCall(trimmed)) return null

        try {
            // 1. Check Gemma native tool call format
            val gemmaMatch = GEMMA_CALL_REGEX.find(trimmed)
            if (gemmaMatch != null) {
                val funcName = gemmaMatch.groupValues[1].lowercase().trim()
                val rawArgs = gemmaMatch.groupValues[2].trim()
                val params = parseGemmaArgs(rawArgs, funcName)
                val action = mapToAgentAction(funcName, params)
                AppLogger.d(TAG, "Parsed Gemma native tool call: $funcName with ${params.size} params")
                return action
            }

            // 2. Check XML / Hermes tool call format (<tool_call>...</tool_call>)
            val xmlMatch = XML_TOOL_CALL_REGEX.find(trimmed)
            if (xmlMatch != null) {
                val body = xmlMatch.groupValues[1].trim()
                val fromXml = parseXmlToolCallBody(body)
                if (fromXml != null) {
                    AppLogger.d(TAG, "Parsed XML tool call: ${fromXml.action}")
                    return fromXml
                }
            }
        } catch (t: Throwable) {
            AppLogger.d(TAG, "GemmaToolCallParser failed: ${t.message}")
        }

        return null
    }

    /**
     * Strips Gemma tool calling tokens and XML tags from model output.
     */
    fun stripGemmaToolCall(text: String): String {
        var result = text.replace(GEMMA_CALL_REGEX, "").trim()
        result = result.replace(XML_TOOL_CALL_REGEX, "").trim()
        return result
    }

    private fun parseGemmaArgs(argsStr: String, funcName: String): Map<String, JsonElement> {
        val params = mutableMapOf<String, JsonElement>()
        if (argsStr.isBlank()) return params

        val matches = GEMMA_ARG_REGEX.findAll(argsStr).toList()
        for (m in matches) {
            val key = m.groupValues[1].trim()
            val gemmaStringVal = m.groupValues[2]
            val arrayVal = m.groupValues[3]
            val doubleQuoteVal = m.groupValues[4]
            val singleQuoteVal = m.groupValues[5]
            val literalVal = m.groupValues[6]

            val element: JsonElement = when {
                m.groupValues[2].isNotEmpty() || argsStr.contains("$key:<|\"|><|\"|>") -> {
                    JsonPrimitive(gemmaStringVal)
                }
                arrayVal.isNotEmpty() || argsStr.contains("$key:[]") -> {
                    parseArrayElements(arrayVal)
                }
                doubleQuoteVal.isNotEmpty() -> {
                    JsonPrimitive(unescapeString(doubleQuoteVal))
                }
                singleQuoteVal.isNotEmpty() -> {
                    JsonPrimitive(singleQuoteVal)
                }
                literalVal.isNotEmpty() -> {
                    val lit = literalVal.trim()
                    lit.toIntOrNull()?.let { JsonPrimitive(it) }
                        ?: lit.toFloatOrNull()?.let { JsonPrimitive(it) }
                        ?: lit.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                        ?: JsonPrimitive(lit)
                }
                else -> JsonPrimitive("")
            }
            params[key] = element
        }

        // Fallback for single unnamed argument e.g. {<|"|>Android 17 newest features<|"|>}
        if (params.isEmpty()) {
            val singleStringMatch = Regex("""<\|"\|>([\s\S]*?)<\|"\|>""").find(argsStr)
            val fallbackStr = singleStringMatch?.groupValues?.get(1) ?: argsStr.trim().removeSurrounding("\"")
            if (fallbackStr.isNotBlank()) {
                val defaultKey = when (funcName) {
                    "search_web" -> "query"
                    "fetch_page" -> "url"
                    "open_app" -> "app_name"
                    "click_text" -> "text"
                    "execute_task" -> "goal"
                    else -> "value"
                }
                params[defaultKey] = JsonPrimitive(fallbackStr)
            }
        }

        return params
    }

    private fun parseArrayElements(rawArray: String): JsonArray {
        val elements = mutableListOf<JsonElement>()
        val gemmaItems = Regex("""<\|"\|>([\s\S]*?)<\|"\|>""").findAll(rawArray).map { it.groupValues[1] }.toList()
        if (gemmaItems.isNotEmpty()) {
            gemmaItems.forEach { elements.add(JsonPrimitive(it.trim())) }
            return JsonArray(elements)
        }

        val quotedItems = Regex(""""([^"\\]*(?:\\.[^"\\]*)*)"|'([^'\\]*(?:\\.[^'\\]*)*)'""").findAll(rawArray)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        if (quotedItems.isNotEmpty()) {
            quotedItems.forEach { elements.add(JsonPrimitive(it.trim())) }
            return JsonArray(elements)
        }

        rawArray.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            elements.add(JsonPrimitive(it))
        }
        return JsonArray(elements)
    }

    private fun parseXmlToolCallBody(body: String): AgentAction? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") && trimmed.contains("\"name\"")) {
            return try {
                val root = json.parseToJsonElement(trimmed).jsonObject
                val name = root["name"]?.jsonPrimitive?.contentOrNull ?: return null
                val argsObj = root["arguments"]
                val argsMap: Map<String, JsonElement> = (argsObj as? JsonObject)?.toMap() ?: emptyMap()
                mapToAgentAction(name, argsMap)
            } catch (e: Exception) {
                AppLogger.d("GemmaToolCallParser", "Failed parsing XML tool call body JSON: ${e.message}", e)
                null
            }
        }

        if (trimmed.startsWith("call:") || GEMMA_CALL_REGEX.containsMatchIn(trimmed)) {
            val gemmaMatch = GEMMA_CALL_REGEX.find(trimmed)
            if (gemmaMatch != null) {
                val funcName = gemmaMatch.groupValues[1].lowercase().trim()
                val rawArgs = gemmaMatch.groupValues[2].trim()
                val params = parseGemmaArgs(rawArgs, funcName)
                return mapToAgentAction(funcName, params)
            }
        }

        return null
    }

    private fun mapToAgentAction(funcName: String, params: Map<String, JsonElement>): AgentAction {
        val canonicalName = when (funcName.lowercase()) {
            "search_web", "web_search", "google", "ddg" -> AgentAction.ACTION_SEARCH_WEB
            "fetch_page", "fetch_url", "crawl_url", "read_page" -> AgentAction.ACTION_FETCH_PAGE
            "create_js_tool", "add_js_tool" -> AgentAction.ACTION_CREATE_JS_TOOL
            "open_app", "launch_app", "app" -> AgentAction.ACTION_OPEN_APP
            "open_url", "url_open" -> AgentAction.ACTION_OPEN_URL
            "make_call", "call" -> AgentAction.ACTION_MAKE_CALL
            "send_sms", "sms" -> AgentAction.ACTION_SEND_SMS
            "search_contact", "contact" -> AgentAction.ACTION_SEARCH_CONTACT
            "click_text", "tap_text" -> AgentAction.ACTION_CLICK_TEXT
            "click_at", "tap_at" -> AgentAction.ACTION_CLICK_AT
            "type_text", "type" -> AgentAction.ACTION_TYPE_TEXT
            "press_enter" -> AgentAction.ACTION_PRESS_ENTER
            "scroll" -> AgentAction.ACTION_SCROLL
            "swipe" -> AgentAction.ACTION_SWIPE
            "press_back" -> AgentAction.ACTION_PRESS_BACK
            "press_home" -> AgentAction.ACTION_PRESS_HOME
            "set_volume" -> AgentAction.ACTION_SET_VOLUME
            "set_brightness" -> AgentAction.ACTION_SET_BRIGHTNESS
            "execute_task", "task" -> AgentAction.ACTION_EXECUTE_TASK
            "done", "complete" -> AgentAction.ACTION_DONE
            else -> funcName.lowercase()
        }

        val response = when (canonicalName) {
            AgentAction.ACTION_SEARCH_WEB -> {
                val q = (params["query"] ?: params["q"])?.jsonPrimitive?.contentOrNull ?: ""
                "Searching web for '$q'"
            }
            AgentAction.ACTION_FETCH_PAGE -> {
                val u = (params["url"] ?: params["link"])?.jsonPrimitive?.contentOrNull ?: ""
                "Fetching webpage $u"
            }
            AgentAction.ACTION_OPEN_APP -> {
                val app = (params["app_name"] ?: params["name"] ?: params["app"])?.jsonPrimitive?.contentOrNull ?: ""
                "Opening $app"
            }
            AgentAction.ACTION_EXECUTE_TASK -> {
                val goal = (params["goal"] ?: params["task"])?.jsonPrimitive?.contentOrNull ?: ""
                "Executing multi-step task: $goal"
            }
            AgentAction.ACTION_CLICK_TEXT -> {
                val text = (params["text"] ?: params["label"])?.jsonPrimitive?.contentOrNull ?: ""
                "Tapping \"$text\""
            }
            else -> "Executing $canonicalName"
        }

        return AgentAction(
            action = canonicalName,
            params = params,
            response = response,
            isComplete = canonicalName == AgentAction.ACTION_DONE
        )
    }

    private fun unescapeString(str: String): String {
        return str.replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}
