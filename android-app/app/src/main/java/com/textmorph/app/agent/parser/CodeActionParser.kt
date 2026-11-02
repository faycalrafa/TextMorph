package com.textmorph.app.agent.parser

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger

/**
 * High-performance, fault-tolerant Code-as-Action parser for on-device LLMs.
 * Standardizes exclusively on Code-as-Action syntax (e.g. click_text("..."), open_app("...")).
 * Features balanced quote/parenthesis parsing and strict guardrails preventing accidental
 * execution when commands are mentioned as parts of conversational prose or questions.
 */
object CodeActionParser {

    private const val TAG = "CodeActionParser"

    private val KEYWORD_BLACKLIST = setOf(
        "if", "for", "while", "return", "val", "var", "def",
        "println", "print", "console", "log", "import", "package", "class", "interface", "fun"
    )

    private val QUESTION_TRIGGERS = listOf(
        "would you like", "should i ", "do you want me to", "can i ", "could i ",
        "how about", "shall i ", "want me to", "prefer me to"
    )

    private val EXPLANATORY_PHRASES = listOf(
        "e.g.", "for example", "you can use", "you could use", "try using",
        "try running", "such as", "syntax:", "example:", "command:"
    )

    fun parse(rawText: String): AgentAction? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return null

        // 1. Direct Code-as-Action parse
        val codeAction = parseCodeAction(trimmed)
        if (codeAction != null) {
            return codeAction
        }

        // 2. Unnest markdown code fence if present
        if (trimmed.contains("```")) {
            val fenceContent = trimmed.substringAfter("```").substringBefore("```")
            val embeddedCode = parseCodeAction(fenceContent)
            if (embeddedCode != null) return embeddedCode
        }

        return null
    }

    fun parseAction(rawText: String): AgentAction? = parse(rawText)

    private fun parseCodeAction(text: String, originalFullText: String = text): AgentAction? {
        try {
            val call = findFunctionCall(text) ?: return null
            val rawFunc = call.first.lowercase().trim()
            val rawArgs = call.second.trim()

            if (rawFunc in KEYWORD_BLACKLIST) {
                return null
            }

            if (isConversationalOrQuestionMention(originalFullText, rawFunc, call.third)) {
                return null
            }

            val parsedArgs = CodeActionMapper.parseArgumentTokens(rawArgs)
            return CodeActionMapper.mapToCanonicalAction(rawFunc, parsedArgs)
        } catch (e: Exception) {
            AppLogger.d(TAG, "CodeActionParser code parse error: ${e.message}")
            return null
        }
    }

    /**
     * Extracts function name, arguments string, and the exact matched call substring
     * while respecting string literals and balanced parentheses.
     */
    fun findFunctionCall(text: String): Triple<String, String, String>? {
        val idRegex = Regex("""(?:^|[\s`])([a-zA-Z0-9_]+)\s*\(""")
        val match = idRegex.find(text) ?: return null
        val funcName = match.groupValues[1]

        val parenIndex = text.indexOf('(', match.range.first)
        if (parenIndex == -1) return null

        var depth = 0
        var inDoubleQuote = false
        var inSingleQuote = false
        var escape = false
        var endIndex = -1

        for (i in parenIndex until text.length) {
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
                if (c == '(') depth++
                else if (c == ')') {
                    depth--
                    if (depth == 0) {
                        endIndex = i
                        break
                    }
                }
            }
        }

        if (endIndex != -1) {
            val args = text.substring(parenIndex + 1, endIndex)
            val callStr = text.substring(parenIndex - funcName.length, endIndex + 1).trim('`', ' ')
            return Triple(funcName, args, callStr)
        }
        return null
    }

    private fun isConversationalOrQuestionMention(fullText: String, funcName: String, matchedCall: String): Boolean {
        val trimmed = fullText.trim()
        val cleanCall = matchedCall.trim('`', ' ')

        // 1. If the match is practically the whole text (or whole code block), it's an intended command
        if (trimmed == cleanCall || trimmed == "`$cleanCall`" || (trimmed.startsWith("```") && trimmed.endsWith("```"))) {
            return false
        }

        // 2. Question check: if text contains question indicators or line/sentence ends in '?'
        val lower = fullText.lowercase()
        if (QUESTION_TRIGGERS.any { lower.contains(it) }) {
            return true
        }

        val lines = fullText.lines()
        val matchLine = lines.firstOrNull { it.contains(cleanCall) } ?: fullText
        val trimmedLine = matchLine.trim().removePrefix("-").removePrefix("*").replace(Regex("""^\d+[.)]\s*"""), "").trim()

        if (trimmedLine.endsWith("?")) {
            return true
        }

        // 3. Explanatory phrase check
        if (EXPLANATORY_PHRASES.any { lower.contains(it) }) {
            return true
        }

        // 4. Label check: If the line is in "Label: action(...)" format (e.g. action option or bullet), it is NOT running prose
        if (trimmedLine.contains(":") && trimmedLine.substringAfter(":").trim().startsWith(cleanCall)) {
            return false
        }

        // 5. Running prose check: If the line has significant text surrounding the call (>= 4 words)
        val withoutMatch = trimmedLine.replace(cleanCall, "").trim('`', ' ', ':', '-')
        val surroundingWords = withoutMatch.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (surroundingWords.size >= 4) {
            return true
        }

        return false
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
            return findFunctionCall(fencedBlock) != null
        }

        val call = findFunctionCall(trimmed) ?: return false
        val func = call.first.lowercase().trim()
        return func !in KEYWORD_BLACKLIST
    }

    fun stripActionPayload(rawText: String): String {
        val text = rawText
        val call = findFunctionCall(text)
        if (call != null && !text.contains("\n\n")) {
            return text.replace(call.third, "").trim('`', ' ', '\n')
        }
        return text.trim()
    }

    fun cleanResponse(rawText: String): String = stripActionPayload(rawText)
}
