package com.textmorph.app.agent.parser

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.tools.registry.AgentToolRegistry
import com.textmorph.app.util.AppLogger

/**
 * High-performance, fault-tolerant Code-as-Action parser for on-device LLMs.
 * Standardizes exclusively on Code-as-Action syntax (e.g. click_text("..."), open_app("...")).
 * Features balanced quote/parenthesis parsing and strict guardrails preventing accidental
 * execution when commands are mentioned as parts of conversational prose or questions.
 */
object CodeActionParser {

    private const val TAG = "CodeActionParser"

    val KEYWORD_BLACKLIST = setOf(
        "if", "for", "while", "return", "val", "var", "def",
        "println", "print", "console", "log", "import", "package", "class", "interface", "fun",
        // CSS functions
        "rotate", "rotatex", "rotatey", "rotatez", "rotate3d",
        "scale", "scalex", "scaley", "scalez", "scale3d",
        "translate", "translatex", "translatey", "translatez", "translate3d",
        "skew", "skewx", "skewy", "matrix", "matrix3d",
        "calc", "min", "max", "clamp", "rgb", "rgba", "hsl", "hsla", "url", "attr",
        "linear-gradient", "radial-gradient", "conic-gradient", "drop-shadow", "blur",
        "polygon", "circle", "ellipse", "rect", "path",
        // JS DOM and built-ins
        "alert", "prompt", "confirm", "fetch", "eval", "require",
        "settimeout", "setinterval", "cleartimeout", "clearinterval",
        "addeventlistener", "removeeventlistener",
        "queryselector", "queryselectorall", "getelementbyid", "getelementsbyclassname",
        "createelement", "appendchild", "removechild", "setattribute", "getattribute",
        "preventdefault", "stoppropagation", "requestanimationframe"
    )

    private val QUESTION_TRIGGERS = listOf(
        "would you like", "should i ", "do you want me to", "can i ", "could i ",
        "how about", "shall i ", "want me to", "prefer me to"
    )

    private val EXPLANATORY_PHRASES = listOf(
        "e.g.", "for example", "you can use", "you could use", "try using",
        "try running", "such as", "syntax:", "example:", "command:"
    )

    val EXCLUDED_CODE_LANGUAGES = setOf(
        "html", "htm", "xml", "xhtml", "svg",
        "css", "scss", "sass", "less",
        "js", "javascript", "ts", "typescript", "jsx", "tsx",
        "php", "php3", "php4", "php5", "phtml",
        "go", "golang",
        "rust", "rs",
        "python", "py",
        "kotlin", "kt", "kts",
        "java",
        "c", "cpp", "cxx", "cc", "h", "hpp",
        "csharp", "cs",
        "ruby", "rb",
        "swift",
        "dart",
        "scala",
        "lua",
        "perl", "pl",
        "r",
        "sql", "mysql", "pgsql", "sqlite",
        "sh", "bash", "zsh", "shell", "powershell", "ps1", "bat", "cmd",
        "json", "json5", "yaml", "yml", "toml", "ini", "diff", "markdown", "md"
    )

    private val FENCED_BLOCK_REGEX = Regex("""```([a-zA-Z0-9_\-\+#]*)[ \t]*\r?\n?([\s\S]*?)```""")

    fun stripExcludedCodeFences(text: String): String {
        return FENCED_BLOCK_REGEX.replace(text) { match ->
            val lang = match.groupValues[1].trim().lowercase()
            val isExplicitToolFence = lang in setOf("tool", "action", "call", "cmd", "command")
            if (isExplicitToolFence) {
                match.value
            } else if (lang in EXCLUDED_CODE_LANGUAGES || lang.isNotEmpty()) {
                // Strips code blocks belonging to programming/markup languages (HTML, JS, PHP, etc.)
                "\n"
            } else {
                val inner = match.groupValues[2].trim()
                if (inner.startsWith("<!DOCTYPE") || inner.startsWith("<html") || inner.startsWith("<?php") ||
                    inner.contains("\nfunction ") || inner.contains("\nfn ") || inner.contains("\ndef ")
                ) {
                    "\n"
                } else {
                    match.value
                }
            }
        }
    }

    fun stripControlTokens(text: String): String {
        var clean = text
        clean = clean.replace(Regex("""<\|tool_call>call:\s*"""), "")
        clean = clean.replace(Regex("""<\|tool_call>\s*"""), "")
        clean = clean.replace(Regex("""<\|tool_call_end>\s*"""), "")
        clean = clean.replace(Regex("""</?tool_call>\s*"""), "")
        clean = clean.replace(Regex("""</?execute_tool>\s*"""), "")
        clean = clean.replace(Regex("""</?execute_action>\s*"""), "")
        clean = clean.replace(Regex("""<\|im_start\|>\s*(?:assistant|model|tool)?\s*"""), "")
        clean = clean.replace(Regex("""<\|im_end\|>\s*"""), "")
        clean = clean.replace(Regex("""<\|end_of_turn\|>\s*"""), "")
        clean = clean.replace(Regex("""<\|eot_id\|>\s*"""), "")
        clean = clean.replace(Regex("""<end_of_turn>\s*"""), "")
        clean = clean.replace(Regex("""<start_of_turn>\s*(?:model|assistant)?\s*"""), "")
        clean = clean.replace(Regex("""<\|python_tag\|>\s*"""), "")
        clean = clean.replace(Regex("""\[/?(?:TOOL_CALL|ACTION)\]\s*""", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("""<call:([a-zA-Z0-9_]+)\s*\("""), "$1(")
        clean = clean.replace(Regex("""(?i)(?:^|[\s`])(?:call|action):\s*([a-zA-Z0-9_]+)\s*\("""), " $1(")
        return clean.trim()
    }

    fun parse(rawText: String): AgentAction? {
        val trimmed = stripControlTokens(rawText)
        if (trimmed.isBlank()) return null

        val withoutThinking = trimmed
            .replace(Regex("""(?s)<think>.*?</think>"""), "")
            .replace("<think>", "")
            .replace("</think>", "")
            .trim()
        if (withoutThinking.isBlank()) return null

        // Strip non-tool programming and markup fences (html, css, js, php, go, rust, etc.)
        val sanitized = stripExcludedCodeFences(withoutThinking)

        // 1. Direct Code-as-Action parse on sanitized text
        val codeAction = parseCodeAction(sanitized, originalFullText = trimmed)
        if (codeAction != null) {
            return codeAction
        }

        // 2. Unnest markdown code fence if explicitly a tool fence or valid unlabelled tool call
        if (trimmed.contains("```")) {
            for (match in FENCED_BLOCK_REGEX.findAll(trimmed)) {
                val lang = match.groupValues[1].trim().lowercase()
                if (lang !in EXCLUDED_CODE_LANGUAGES) {
                    val fenceContent = match.groupValues[2].trim()
                    val embeddedCode = parseCodeAction(stripControlTokens(fenceContent), originalFullText = trimmed)
                    if (embeddedCode != null) return embeddedCode
                }
            }
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
            val action = CodeActionMapper.mapToCanonicalAction(rawFunc, parsedArgs) ?: return null

            val callSubstring = call.third
            val preText = originalFullText.substringBefore(callSubstring)
                .substringBefore("```")
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .replace("<think>", "")
                .trim()
            val postText = originalFullText.substringAfter(callSubstring)
                .substringAfter("```")
                .trim()

            val explanation = when {
                preText.isNotBlank() -> preText
                postText.isNotBlank() && !postText.startsWith("`") -> postText
                else -> null
            }

            return if (!explanation.isNullOrBlank()) {
                action.copy(
                    reasoning = explanation,
                    response = if (action.response.isBlank()) explanation else action.response
                )
            } else {
                action
            }
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
        val idRegex = Regex("""(?:^|[\s`:>\]|])([a-zA-Z0-9_]+)\s*\(""")
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
        val trimmed = stripControlTokens(fullText).trim()
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
        val trimmed = stripControlTokens(text).trim()
        if (trimmed.isBlank()) return false

        val sanitized = stripExcludedCodeFences(trimmed)
        if (sanitized.isBlank()) return false

        // If an explicit tool code fence is present, it must be closed
        if (sanitized.contains("```")) {
            val firstFence = sanitized.indexOf("```")
            val secondFence = sanitized.indexOf("```", firstFence + 3)
            if (secondFence == -1) return false
            val fencedBlock = sanitized.substring(firstFence, secondFence + 3)
            val call = findFunctionCall(stripControlTokens(fencedBlock)) ?: return false
            val func = call.first.lowercase().trim()
            if (func in KEYWORD_BLACKLIST) return false
            return AgentToolRegistry.findTool(func) != null || CodeActionMapper.mapToCanonicalAction(func, emptyList()) != null
        }

        val call = findFunctionCall(sanitized) ?: return false
        val func = call.first.lowercase().trim()
        if (func in KEYWORD_BLACKLIST) return false
        return AgentToolRegistry.findTool(func) != null || CodeActionMapper.mapToCanonicalAction(func, emptyList()) != null
    }

    fun stripActionPayload(rawText: String): String {
        val cleaned = stripControlTokens(rawText)
        val sanitized = stripExcludedCodeFences(cleaned)
        val call = findFunctionCall(sanitized)
        if (call != null && !cleaned.contains("\n\n")) {
            return stripControlTokens(cleaned.replace(call.third, "")).trim('`', ' ', '\n')
        }
        return cleaned.trim()
    }

    fun cleanResponse(rawText: String): String = stripActionPayload(rawText)
}
