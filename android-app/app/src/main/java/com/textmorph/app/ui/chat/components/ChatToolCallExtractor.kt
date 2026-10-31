package com.textmorph.app.ui.chat.components

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser

data class UserApprovalRequest(
    val question: String,
    val options: List<String>,
    val preText: String = "",
    val postText: String = ""
)

data class FollowUpOption(
    val title: String,
    val actionCommand: String? = null,
    val toolName: String? = null,
    val toolDisplayName: String? = null,
    val isAction: Boolean = false
)

data class FollowUpSuggestionsRequest(
    val options: List<FollowUpOption>,
    val remainingContent: String
) {
    // Backward compatibility property for existing tests and callers
    val suggestions: List<String> get() = options.map { it.title }

    companion object {
        fun fromStrings(legacySuggestions: List<String>, remainingContent: String): FollowUpSuggestionsRequest {
            return FollowUpSuggestionsRequest(
                options = legacySuggestions.map { ChatToolCallExtractor.parseFollowUpOption(it) },
                remainingContent = remainingContent
            )
        }
    }
}

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
        """(?:```(?:python|code|js)?\s*)?`?suggest_next_actions\s*\(\s*(?:(?:suggestions|actions)\s*=\s*)?(?:\[([\s\S]*?)\]|([\s\S]*?))\s*\)?`?(?:\s*```)?""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val MARKDOWN_FOLLOW_UP_REGEX = Regex(
        """(?i)(?:^|\n+)\s*(?:###?\s*)?(?:Follow-up suggestions?|Next steps?|Suggested actions?|Suggested follow-ups?|Follow-up questions?|Next actions?):\s*(?:[\r\n]+(?:\s*[-*•\d.]+[^\n]+)+|\s*$)"""
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

        // 1. Check Code-as-Action suggest_next_actions
        val codeMatch = SUGGESTIONS_CODE_REGEX.find(trimmed)
        if (codeMatch != null) {
            val rawList = codeMatch.groupValues[1].ifEmpty { codeMatch.groupValues[2] }
            val rawItems = parseListElements(rawList)
            val options = rawItems.map { parseFollowUpOption(it) }.filter { it.title.isNotBlank() }
            val remaining = (trimmed.substring(0, codeMatch.range.first) + " " + trimmed.substring(codeMatch.range.last + 1)).trim()
            if (options.isNotEmpty()) {
                return FollowUpSuggestionsRequest(options, remaining)
            }
        }

        // 2. Check Markdown follow-up block at the end of the text
        val mdMatch = MARKDOWN_FOLLOW_UP_REGEX.find(trimmed)
        if (mdMatch != null) {
            val matchedBlock = mdMatch.value
            val lines = matchedBlock.lines().map { it.trim() }.filter { it.isNotBlank() }
            val bulletItems = lines.filter { line ->
                line.startsWith("-") || line.startsWith("*") || line.startsWith("•") || Regex("""^\d+[.)]""").containsMatchIn(line)
            }.map { line ->
                line.replaceFirst(Regex("""^[-*•\d.)]+\s*"""), "").trim()
            }

            val options = bulletItems.map { parseFollowUpOption(it) }.filter { it.title.isNotBlank() }
            val remaining = trimmed.substring(0, mdMatch.range.first).trim()
            if (options.isNotEmpty()) {
                return FollowUpSuggestionsRequest(options, remaining)
            }
        }

        return null
    }

    fun parseFollowUpOption(rawItem: String): FollowUpOption {
        val trimmed = rawItem.trim().trim('"', '\'', '`', '*', '-')
        if (trimmed.isBlank()) {
            return FollowUpOption(title = "", isAction = false)
        }

        // Check if the item contains a Code-as-Action command (e.g. search_web(...), open_app(...))
        val action = CodeActionParser.parse(trimmed)
        if (action != null) {
            val toolName = action.action
            val toolDisplayName = getToolDisplayName(toolName)

            // Extract human title: prefer text before colon/hyphen, else derive from parameters
            val labelBefore = extractLabelBeforeAction(trimmed)
            val title = if (labelBefore.isNotBlank()) {
                labelBefore
            } else {
                deriveActionTitle(action)
            }

            val actionCommand = extractActionCallString(trimmed) ?: trimmed

            return FollowUpOption(
                title = title,
                actionCommand = actionCommand,
                toolName = toolName,
                toolDisplayName = toolDisplayName,
                isAction = true
            )
        }

        // Plain conversational follow-up suggestion
        return FollowUpOption(
            title = trimmed,
            actionCommand = null,
            toolName = null,
            toolDisplayName = null,
            isAction = false
        )
    }

    private fun extractLabelBeforeAction(text: String): String {
        val delimiters = listOf(":", " - ", " -> ", " – ")
        for (delim in delimiters) {
            if (text.contains(delim)) {
                val candidate = text.substringBefore(delim).trim('`', ' ', '"', '\'', '*')
                if (candidate.isNotBlank() && !candidate.contains("(") && !candidate.contains(")")) {
                    return candidate
                }
            }
        }
        return ""
    }

    private fun extractActionCallString(text: String): String? {
        val match = Regex("""`?([a-zA-Z0-9_]+\s*\([\s\S]*?\))`?""").find(text)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun deriveActionTitle(action: AgentAction): String {
        return when (action.action) {
            AgentAction.ACTION_SEARCH_WEB -> {
                val q = action.getStringParam("query").ifEmpty { action.getStringParam("q") }
                if (q.isNotBlank()) "Search \"$q\"" else "Web Search"
            }
            AgentAction.ACTION_SEARCH_IMAGES -> {
                val q = action.getStringParam("query").ifEmpty { action.getStringParam("q") }
                if (q.isNotBlank()) "Search images of \"$q\"" else "Image Search"
            }
            AgentAction.ACTION_FETCH_PAGE -> {
                val u = action.getStringParam("url").ifEmpty { action.getStringParam("link") }
                if (u.isNotBlank()) "Read $u" else "Read Webpage"
            }
            AgentAction.ACTION_OPEN_APP -> {
                val app = action.getStringParam("app_name").ifEmpty { action.getStringParam("name") }
                if (app.isNotBlank()) "Open $app" else "Open App"
            }
            AgentAction.ACTION_MAKE_CALL -> {
                val contact = action.getStringParam("contact_name").ifEmpty { action.getStringParam("phone_number") }
                if (contact.isNotBlank()) "Call $contact" else "Make Phone Call"
            }
            AgentAction.ACTION_SEND_SMS -> {
                val contact = action.getStringParam("contact_name").ifEmpty { action.getStringParam("recipient") }
                if (contact.isNotBlank()) "Text $contact" else "Send SMS"
            }
            AgentAction.ACTION_SET_ALARM -> {
                val hr = action.getStringParam("hour")
                val min = action.getStringParam("minute").padStart(2, '0')
                "Set alarm for $hr:$min"
            }
            AgentAction.ACTION_SET_TIMER -> {
                val sec = action.getStringParam("seconds")
                "Set timer for ${sec}s"
            }
            "get_weather" -> {
                val loc = action.getStringParam("location").ifEmpty { action.getStringParam("city") }
                if (loc.isNotBlank()) "Check weather in $loc" else "Check Weather"
            }
            else -> {
                action.response.ifBlank { action.action.replace('_', ' ').replaceFirstChar { it.uppercase() } }
            }
        }
    }

    fun getToolDisplayName(toolName: String): String {
        return when (toolName) {
            AgentAction.ACTION_SEARCH_WEB -> "Web Search"
            AgentAction.ACTION_SEARCH_IMAGES -> "Image Search"
            AgentAction.ACTION_FETCH_PAGE -> "Webpage Reader"
            AgentAction.ACTION_OPEN_APP -> "Open App"
            AgentAction.ACTION_MAKE_CALL -> "Phone Call"
            AgentAction.ACTION_SEND_SMS -> "Send SMS"
            AgentAction.ACTION_SET_ALARM -> "Set Alarm"
            AgentAction.ACTION_SET_TIMER -> "Timer"
            "get_weather" -> "Weather"
            "unit_converter" -> "Unit Converter"
            else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
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
        // Match string literals respecting escaped quotes
        val matches = Regex(""""((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'""").findAll(raw)
        val result = matches.map { m ->
            val v = m.groups[1]?.value ?: m.groups[2]?.value ?: ""
            v.replace("\\\"", "\"").replace("\\'", "'").trim()
        }.filter { it.isNotBlank() }.toList()

        if (result.isNotEmpty()) return result

        return raw.split(",")
            .map { it.trim().trim('"', '\'', '`', '[', ']') }
            .filter { it.isNotBlank() }
    }
}
