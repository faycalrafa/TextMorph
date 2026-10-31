package com.textmorph.app.agent.parser

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger

/**
 * High-performance, fault-tolerant Code-as-Action parser for on-device LLMs.
 * Standardizes exclusively on Code-as-Action syntax (e.g. click_text("..."), open_app("...")).
 */
object CodeActionParser {

    private const val TAG = "CodeActionParser"
    private val CALL_REGEX = Regex("""(?:^|[\s`])([a-zA-Z0-9_]+)\s*\(([\s\S]*?)\)""")

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
        val text = rawText
        val cmdMatch = Regex("""(?:```(?:cmd|python|code|js|bash)?\s*)?`?[a-zA-Z_][a-zA-Z0-9_]*\s*\([\s\S]*?\)`?(?:\s*```)?""").find(text)
        if (cmdMatch != null && cmdMatch.value.isNotBlank() && !text.contains("\n\n")) {
            return text.replace(cmdMatch.value, "").trim()
        }
        return text.trim()
    }

    fun cleanResponse(rawText: String): String = stripActionPayload(rawText)
}
