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
    val remainingContent: String
)

/**
 * Extracts structured presentation requests (approval dialogs, follow-up chips,
 * and interactive view cards) embedded in AI responses via Code-as-Action syntax.
 */
object ChatToolCallExtractor {

    private const val TAG = "ChatToolCallExtractor"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val APPROVAL_CODE_REGEX = Regex(
        """(?:```(?:python|code|js)?\s*)?request_user_approval\s*\(\s*question\s*=\s*"([^"]+)"\s*,\s*options\s*=\s*\[(.*?)\]\s*\)(?:\s*```)?""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val SUGGESTIONS_CODE_REGEX = Regex(
        """(?:```(?:python|code|js)?\s*)?suggest_next_actions\s*\(\s*suggestions\s*=\s*\[(.*?)\]\s*\)(?:\s*```)?""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val INTERACTIVE_VIEW_CODE_REGEX = Regex(
        """(?:```(?:python|code|js)?\s*)?present_interactive_view\s*\(\s*(?:title\s*=\s*"([^"]*)")?\s*,?\s*(?:html\s*=\s*)?""" +
                """(?:```html\s*)?([\s\S]*?)(?:```)?\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun extractApproval(content: String): UserApprovalRequest? {
        val trimmed = content.trim()

        // 1. Check Code-as-Action syntax
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

        // 2. Legacy fallback for existing history
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

        // 1. Check Code-as-Action syntax
        val codeMatch = SUGGESTIONS_CODE_REGEX.find(trimmed)
        if (codeMatch != null) {
            val rawList = codeMatch.groupValues[1]
            val suggestions = parseListElements(rawList)
            val remaining = (trimmed.substring(0, codeMatch.range.first) + " " + trimmed.substring(codeMatch.range.last + 1)).trim()
            if (suggestions.isNotEmpty()) {
                return FollowUpSuggestionsRequest(suggestions, remaining)
            }
        }

        // 2. Legacy fallback for existing history
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
