package com.textmorph.app.ui.chat.components

import com.textmorph.app.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class UserApprovalRequest(
    val question: String,
    val options: List<String>,
    val preText: String = "",
    val postText: String = ""
)

data class FollowUpSuggestionsRequest(
    val suggestions: List<String>,
    val remainingContent: String = ""
)

/**
 * Robust extractor for interactive tool invocations in normal chat mode:
 * 1. Human-in-the-Loop Confirmation: request_user_approval
 * 2. Follow-Up Action Suggesters: suggest_next_actions
 * 3. Interactive Artifact Renderer: present_interactive_view
 * Supports both Code-as-Action and JSON syntaxes.
 */
object ChatToolCallExtractor {

    private const val TAG = "ChatToolCallExtractor"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val APPROVAL_CODE_REGEX = Regex(
        """request_user_approval\s*\(\s*(?:question\s*=\s*)?"([^"]+)"\s*,\s*(?:options\s*=\s*)?\[(.*?)\]\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val SUGGESTIONS_CODE_REGEX = Regex(
        """suggest_next_actions\s*\(\s*(?:suggestions\s*=\s*)?\[(.*?)\]\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val INTERACTIVE_VIEW_CODE_REGEX = Regex(
        """present_interactive_view\s*\(\s*(?:title\s*=\s*)?"([^"]*)"\s*,\s*(?:html\s*=\s*)?""" +
                """(?:```html\s*)?([\s\S]*?)(?:```)?\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun extractApproval(content: String): UserApprovalRequest? {
        val trimmed = content.trim()

        // 1. Check Gemma tool calling syntax (<|tool_call|>call:request_user_approval{...}<tool_call|>)
        if (com.textmorph.app.agent.parser.GemmaToolCallParser.hasToolCall(trimmed)) {
            val gemmaAction = com.textmorph.app.agent.parser.GemmaToolCallParser.parse(trimmed)
            if (gemmaAction != null && gemmaAction.action == "request_user_approval") {
                val question = gemmaAction.getStringParamWithAliases("question", "q")
                val options = (gemmaAction.params["options"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val remaining = com.textmorph.app.agent.parser.GemmaToolCallParser.stripGemmaToolCall(trimmed)
                if (question.isNotEmpty() && options.isNotEmpty()) {
                    return UserApprovalRequest(question, options, preText = remaining, postText = "")
                }
            }
        }

        // 2. Check Code-as-Action syntax
        val codeMatch = APPROVAL_CODE_REGEX.find(trimmed)
        if (codeMatch != null) {
            val question = codeMatch.groupValues[1].trim()
            val rawOptions = codeMatch.groupValues[2]
            val options = parseListElements(rawOptions)
            val preText = trimmed.substring(0, codeMatch.range.first).trim()
            val postText = trimmed.substring(codeMatch.range.last + 1).trim()
            if (question.isNotEmpty() && options.isNotEmpty()) {
                return UserApprovalRequest(question, options, preText, postText)
            }
        }

        // 2. Check JSON syntax
        if (trimmed.contains("request_user_approval")) {
            val jsonCandidate = extractJsonObject(trimmed, "request_user_approval")
            if (jsonCandidate != null) {
                try {
                    val root = json.parseToJsonElement(jsonCandidate.first).jsonObject
                    val question = root["question"]?.jsonPrimitive?.content ?: ""
                    val options = root["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                    val preText = trimmed.substring(0, jsonCandidate.second).trim()
                    val postText = trimmed.substring(jsonCandidate.third).trim()
                    if (question.isNotEmpty() && options.isNotEmpty()) {
                        return UserApprovalRequest(question, options, preText, postText)
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Failed parsing user approval JSON candidate: ${e.message}", e)
                }
            }
        }

        return null
    }

    fun extractFollowUps(content: String): FollowUpSuggestionsRequest? {
        val trimmed = content.trim()

        // 1. Check Gemma tool calling syntax (<|tool_call|>call:suggest_next_actions{...}<tool_call|>)
        if (com.textmorph.app.agent.parser.GemmaToolCallParser.hasToolCall(trimmed)) {
            val gemmaAction = com.textmorph.app.agent.parser.GemmaToolCallParser.parse(trimmed)
            if (gemmaAction != null && gemmaAction.action == "suggest_next_actions") {
                val suggestions = (gemmaAction.params["suggestions"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val remaining = com.textmorph.app.agent.parser.GemmaToolCallParser.stripGemmaToolCall(trimmed)
                if (suggestions.isNotEmpty()) {
                    return FollowUpSuggestionsRequest(suggestions, remaining)
                }
            }
        }

        // 2. Check Code-as-Action syntax
        val codeMatch = SUGGESTIONS_CODE_REGEX.find(trimmed)
        if (codeMatch != null) {
            val rawList = codeMatch.groupValues[1]
            val suggestions = parseListElements(rawList)
            val remaining = (trimmed.substring(0, codeMatch.range.first) + " " + trimmed.substring(codeMatch.range.last + 1)).trim()
            if (suggestions.isNotEmpty()) {
                return FollowUpSuggestionsRequest(suggestions, remaining)
            }
        }

        // 3. Check JSON syntax
        if (trimmed.contains("suggest_next_actions")) {
            val jsonCandidate = extractJsonObject(trimmed, "suggest_next_actions")
            if (jsonCandidate != null) {
                try {
                    val root = json.parseToJsonElement(jsonCandidate.first).jsonObject
                    val suggestions = root["suggestions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                    val remaining = (trimmed.substring(0, jsonCandidate.second) + " " + trimmed.substring(jsonCandidate.third)).trim()
                    if (suggestions.isNotEmpty()) {
                        return FollowUpSuggestionsRequest(suggestions, remaining)
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Failed parsing follow-up suggestions JSON candidate: ${e.message}", e)
                }
            }
        }

        return null
    }

    fun extractInteractiveViewHtml(content: String): Pair<String?, String>? {
        val trimmed = content.trim()

        // 1. Check Gemma tool calling syntax (<|tool_call|>call:present_interactive_view{...}<tool_call|>)
        if (com.textmorph.app.agent.parser.GemmaToolCallParser.hasToolCall(trimmed)) {
            val gemmaAction = com.textmorph.app.agent.parser.GemmaToolCallParser.parse(trimmed)
            if (gemmaAction != null && gemmaAction.action == "present_interactive_view") {
                val title = gemmaAction.getStringParamWithAliases("title").ifEmpty { null }
                val html = gemmaAction.getStringParamWithAliases("html", "code")
                val cleanedHtml = html.removeSurrounding("```html", "```").trim()
                if (cleanedHtml.isNotEmpty()) {
                    return Pair(title, cleanedHtml)
                }
            }
        }

        val codeMatch = INTERACTIVE_VIEW_CODE_REGEX.find(trimmed)
        if (codeMatch != null) {
            val title = codeMatch.groupValues[1].trim().ifEmpty { null }
            val rawHtml = codeMatch.groupValues[2].trim()
            val cleanedHtml = rawHtml.removeSurrounding("```html", "```").trim()
            if (cleanedHtml.isNotEmpty()) {
                return Pair(title, cleanedHtml)
            }
        }

        if (trimmed.contains("present_interactive_view")) {
            val jsonCandidate = extractJsonObject(trimmed, "present_interactive_view")
            if (jsonCandidate != null) {
                try {
                    val root = json.parseToJsonElement(jsonCandidate.first).jsonObject
                    val title = root["title"]?.jsonPrimitive?.content
                    val html = root["html"]?.jsonPrimitive?.content ?: root["code"]?.jsonPrimitive?.content
                    if (!html.isNullOrBlank()) {
                        return Pair(title, html)
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Failed parsing interactive view JSON candidate: ${e.message}", e)
                }
            }
        }

        return null
    }

    private fun parseListElements(raw: String): List<String> {
        val matches = Regex(""""([^"]+)"""").findAll(raw)
        val result = matches.map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
        if (result.isNotEmpty()) return result

        return raw.split(",")
            .map { it.trim().trim('"', '\'', '`', '[', ']') }
            .filter { it.isNotBlank() }
    }

    private fun extractJsonObject(text: String, keyword: String): Triple<String, Int, Int>? {
        val keywordIdx = text.indexOf(keyword)
        if (keywordIdx == -1) return null

        val start = text.lastIndexOf('{', keywordIdx)
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escape = false

        for (i in start until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, i + 1)
                        return Triple(candidate, start, i + 1)
                    }
                }
            }
        }

        return null
    }
}
