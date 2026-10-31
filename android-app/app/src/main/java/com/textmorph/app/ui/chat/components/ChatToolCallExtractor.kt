package com.textmorph.app.ui.chat.components

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
        return null
    }

    fun extractFollowUps(content: String): FollowUpSuggestionsRequest? {
        val trimmed = content.trim()
        val codeMatch = SUGGESTIONS_CODE_REGEX.find(trimmed)
        if (codeMatch != null) {
            val rawList = codeMatch.groupValues[1]
            val suggestions = parseListElements(rawList)
            val remaining = (trimmed.substring(0, codeMatch.range.first) + " " + trimmed.substring(codeMatch.range.last + 1)).trim()
            if (suggestions.isNotEmpty()) {
                return FollowUpSuggestionsRequest(suggestions, remaining)
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
}
