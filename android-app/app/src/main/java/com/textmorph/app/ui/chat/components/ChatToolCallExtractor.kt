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
        """(?:```(?:python|code|js)?\s*)?`?request_user_approval\s*\(\s*(?:(?:question|message)\s*=\s*)?(?:"([^"]*)"|'([^']*)')\s*,\s*(?:options\s*=\s*)?\[([\s\S]*?)\]\s*\)`?(?:\s*```)?""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val SUGGESTIONS_CODE_REGEX = Regex(
        """(?:```(?:python|code|js)?\s*)?`?suggest_next_actions\s*\(\s*(?:(?:suggestions|actions)\s*=\s*)?(?:\[([\s\S]*?)\]|([^\)]*?))\s*\)`?(?:\s*```)?""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val MARKDOWN_FOLLOW_UP_REGEX = Regex(
        """(?i)(?:^|\n+)\s*(?:###?\s*)?(?:Follow-up suggestions?|Next steps?|Suggested actions?|Suggested follow-ups?|Follow-up questions?|Next actions?|Recommended next steps?|Suggested questions?):\s*(?:[\r\n]+(?:\s*[-*•\d.]+[^\n]+)+|\s*$)"""
    )

    private val INTERACTIVE_VIEW_CODE_REGEX = Regex(
        """(?:```(?:python|code|js)?\s*)?present_interactive_view\s*\(\s*(?:title\s*=\s*"([^"]*)")?\s*,?\s*(?:html\s*=\s*)?""" +
                """(?:```html\s*)?([\s\S]*?)(?:```)?\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    data class BalancedCallMatch(
        val startIndex: Int,
        val endIndex: Int,
        val fullCall: String,
        val functionName: String,
        val rawArgs: String
    )

    fun findBalancedCall(text: String, targetFunctions: Set<String>): BalancedCallMatch? {
        if (text.isBlank()) return null
        for (func in targetFunctions) {
            val pattern = Regex("""(?:^|[\s`:>\]|])(""" + Regex.escape(func) + """)\s*\(""")
            val match = pattern.find(text) ?: continue
            val funcName = match.groupValues[1]
            val openParenIdx = text.indexOf('(', match.range.first)
            if (openParenIdx == -1) continue

            var depth = 0
            var inDoubleQuote = false
            var inSingleQuote = false
            var escape = false
            var closeParenIdx = -1

            for (i in openParenIdx until text.length) {
                val c = text[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote
                } else if (c == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote
                } else if (!inDoubleQuote && !inSingleQuote) {
                    if (c == '(') {
                        depth++
                    } else if (c == ')') {
                        depth--
                        if (depth == 0) {
                            closeParenIdx = i
                            break
                        }
                    }
                }
            }

            if (closeParenIdx != -1) {
                var start = match.range.first
                // Expand start backwards if enclosed in backticks or code fences
                if (start > 0 && text[start - 1] == '`') {
                    start--
                    if (start >= 2 && text.substring(start - 2, start + 1) == "```") {
                        start -= 2
                    }
                }
                var end = closeParenIdx + 1
                // Expand end forwards if enclosed in backticks or code fences
                if (end < text.length && text[end] == '`') {
                    end++
                    if (end + 2 <= text.length && text.substring(end - 1, end + 2) == "```") {
                        end += 2
                    }
                }

                val fullCall = text.substring(start, end)
                val rawArgs = text.substring(openParenIdx + 1, closeParenIdx).trim()
                return BalancedCallMatch(start, end, fullCall, funcName, rawArgs)
            }
        }
        return null
    }

    fun splitTopLevelArguments(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        var depthParen = 0
        var depthBracket = 0
        var depthBrace = 0
        var inDoubleQuote = false
        var inSingleQuote = false
        var escape = false
        var current = StringBuilder()

        for (i in 0 until trimmed.length) {
            val c = trimmed[i]
            if (escape) {
                escape = false
                current.append(c)
                continue
            }
            if (c == '\\') {
                escape = true
                current.append(c)
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                current.append(c)
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                current.append(c)
            } else if (!inDoubleQuote && !inSingleQuote) {
                when (c) {
                    '(' -> { depthParen++; current.append(c) }
                    ')' -> { depthParen--; current.append(c) }
                    '[' -> { depthBracket++; current.append(c) }
                    ']' -> { depthBracket--; current.append(c) }
                    '{' -> { depthBrace++; current.append(c) }
                    '}' -> { depthBrace--; current.append(c) }
                    ',' -> {
                        if (depthParen == 0 && depthBracket == 0 && depthBrace == 0) {
                            val token = current.toString().trim()
                            if (token.isNotBlank()) result.add(token)
                            current = StringBuilder()
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
            } else {
                current.append(c)
            }
        }
        val last = current.toString().trim()
        if (last.isNotBlank()) result.add(last)
        return result
    }

    fun extractApproval(content: String): UserApprovalRequest? {
        val trimmed = content.trim()
        val match = findBalancedCall(trimmed, setOf("request_user_approval", "user_approval"))
        if (match != null) {
            val preText = trimmed.substring(0, match.startIndex).trim()
            val postText = trimmed.substring(match.endIndex).trim()
            val rawArgs = match.rawArgs.trim()

            var question = ""
            var options = listOf<String>()

            // Look for named parameters
            val qNamed = Regex("""(?:question|message|prompt)\s*=\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)')""").find(rawArgs)
            if (qNamed != null) {
                question = (qNamed.groups[1]?.value ?: qNamed.groups[2]?.value ?: "").replace("\\\"", "\"").replace("\\'", "'").trim()
            }

            val optNamed = Regex("""(?:options|choices)\s*=\s*(?:\[([\s\S]*?)\]|\(([\s\S]*?)\))""").find(rawArgs)
            if (optNamed != null) {
                val listContent = optNamed.groups[1]?.value ?: optNamed.groups[2]?.value ?: ""
                options = splitTopLevelArguments(listContent).map { it.trim('"', '\'', '`', ' ') }.filter { it.isNotBlank() }
            }

            if (question.isBlank()) {
                val args = splitTopLevelArguments(rawArgs)
                if (args.isNotEmpty()) {
                    val first = args[0].removePrefix("question=").removePrefix("message=").removePrefix("prompt=").trim().trim('"', '\'', '`')
                    question = first
                    if (options.isEmpty()) {
                        if (args.size > 1) {
                            val second = args[1].trim()
                            if ((second.startsWith("[") && second.endsWith("]")) || (second.startsWith("(") && second.endsWith(")"))) {
                                val inner = second.substring(1, second.length - 1)
                                options = splitTopLevelArguments(inner).map { it.trim('"', '\'', '`', ' ') }.filter { it.isNotBlank() }
                            } else {
                                options = args.drop(1).map { it.trim('"', '\'', '`', ' ') }.filter { it.isNotBlank() }
                            }
                        }
                    }
                }
            }

            if (question.isNotBlank()) {
                if (options.isEmpty()) {
                    options = listOf("Yes", "No")
                }
                return UserApprovalRequest(question, options, preText, postText)
            }
        }

        // Regex fallback
        val codeMatch = APPROVAL_CODE_REGEX.find(trimmed)
        if (codeMatch != null) {
            val question = codeMatch.groupValues[1].ifEmpty { codeMatch.groupValues[2] }.trim()
            val rawOptions = codeMatch.groupValues[3]
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

        // 1. Check Code-as-Action suggest_next_actions via balanced call parser
        val match = findBalancedCall(trimmed, setOf("suggest_next_actions", "suggest_actions", "next_actions"))
        if (match != null) {
            var raw = match.rawArgs.trim()
            if (raw.startsWith("suggestions=", ignoreCase = true)) {
                raw = raw.substringAfter("=").trim()
            } else if (raw.startsWith("actions=", ignoreCase = true)) {
                raw = raw.substringAfter("=").trim()
            }
            if ((raw.startsWith("[") && raw.endsWith("]")) || (raw.startsWith("(") && raw.endsWith(")"))) {
                raw = raw.substring(1, raw.length - 1).trim()
            }
            val tokens = splitTopLevelArguments(raw)
            val options = tokens.map { parseFollowUpOption(it) }.filter { it.title.isNotBlank() }
            val remaining = (trimmed.substring(0, match.startIndex) + " " + trimmed.substring(match.endIndex)).trim()
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
        var trimmed = rawItem.trim().trim('`', '*', '-')
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length - 1).trim()
        }
        trimmed = trimmed.replace("\\\"", "\"").replace("\\'", "'")
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
