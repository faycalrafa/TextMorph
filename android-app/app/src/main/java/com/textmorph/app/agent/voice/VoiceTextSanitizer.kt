package com.textmorph.app.agent.voice

/**
 * Sanitizes raw LLM generation text into natural, spoken phrasing suitable for Android TTS:
 * 1. Strips <think>...</think> traces (and partial open think tags).
 * 2. Strips raw Code-as-Action blocks (click_at, type_text, open_app, done, etc.).
 * 3. Strips raw markdown code blocks, headers, bullet markers, and URLs.
 * 4. Converts LaTeX and mathematical expressions into spoken English words.
 */
object VoiceTextSanitizer {

    private val THINK_TAG_REGEX = Regex("<think>[\\s\\S]*?(</think>|$)", RegexOption.IGNORE_CASE)
    private val CODE_ACTION_REGEX = Regex(
        "(?:click_text|click_at|type_text|press_enter|scroll|open_app|done|back)\\s*\\([^)]*\\)",
        RegexOption.IGNORE_CASE
    )
    private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```", RegexOption.MULTILINE)
    private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")

    /**
     * Cleans and formats input text for TTS audio playback.
     */
    fun sanitizeForTts(rawText: String): String {
        if (rawText.isBlank()) return ""

        var text = rawText

        // 1. Strip think blocks
        text = THINK_TAG_REGEX.replace(text, "")

        // 2. Strip raw code action invocations
        text = CODE_ACTION_REGEX.replace(text, "")

        // 3. Strip fenced code blocks
        text = CODE_BLOCK_REGEX.replace(text, " ")

        // 4. Strip URLs
        text = URL_REGEX.replace(text, " ")

        // 5. Convert Markdown links: [Title](url) -> Title
        text = MARKDOWN_LINK_REGEX.replace(text, "$1")

        // 6. Convert Math / LaTeX formulas into natural spoken words
        text = convertMathToSpokenWords(text)

        // 7. Strip Markdown syntax formatting
        text = stripMarkdownFormatting(text)

        // 8. Normalize spacing and punctuation
        text = normalizeSpokenPunctuation(text)

        return text.trim()
    }

    private fun convertMathToSpokenWords(input: String): String {
        var res = input

        // Fractions: \frac{a}{b} -> "a over b"
        res = Regex("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}").replace(res) { match ->
            val numerator = match.groupValues[1].trim()
            val denominator = match.groupValues[2].trim()
            "$numerator over $denominator"
        }

        // Roots: \sqrt{x} -> "square root of x", \sqrt[n]{x} -> "nth root of x"
        res = Regex("\\\\sqrt\\[([0-9]+)\\]\\{([^}]+)\\}").replace(res) { match ->
            "${match.groupValues[1]}th root of ${match.groupValues[2]}"
        }
        res = Regex("\\\\sqrt\\{([^}]+)\\}").replace(res) { match ->
            "square root of ${match.groupValues[1]}"
        }

        // Powers: x^2 -> "x squared", x^3 -> "x cubed", x^{n} -> "x to the power of n"
        res = Regex("([a-zA-Z0-9]+)\\^2\\b").replace(res, "$1 squared")
        res = Regex("([a-zA-Z0-9]+)\\^3\\b").replace(res, "$1 cubed")
        res = Regex("([a-zA-Z0-9]+)\\^\\{([^}]+)\\}").replace(res, "$1 to the power of $2")
        res = Regex("([a-zA-Z0-9]+)\\^([0-9a-zA-Z]+)").replace(res, "$1 to the power of $2")

        // LaTeX math operators and symbols
        res = res
            .replace("\\times", " times ")
            .replace("\\cdot", " times ")
            .replace("\\div", " divided by ")
            .replace("\\pm", " plus or minus ")
            .replace("\\mp", " minus or plus ")
            .replace("\\approx", " approximately ")
            .replace("\\neq", " not equal to ")
            .replace("\\leq", " less than or equal to ")
            .replace("\\le", " less than or equal to ")
            .replace("\\geq", " greater than or equal to ")
            .replace("\\ge", " greater than or equal to ")
            .replace("\\infty", " infinity ")
            .replace("\\pi", " pi ")
            .replace("\\alpha", " alpha ")
            .replace("\\beta", " beta ")
            .replace("\\theta", " theta ")
            .replace("\\sum", " sum of ")
            .replace("\\int", " integral of ")

        // Math block delimiters $...$ and $$...$$ and \[ \] and \( \)
        res = res
            .replace("\$\$", " ")
            .replace("\$", " ")
            .replace("\\[", " ")
            .replace("\\]", " ")
            .replace("\\(", " ")
            .replace("\\)", " ")

        return res
    }

    private fun stripMarkdownFormatting(input: String): String {
        var res = input

        // Inline code `code` -> code
        res = Regex("`([^`]+)`").replace(res, "$1")

        // Bold and italic: **text**, *text*, __text__, _text_
        res = Regex("\\*\\*([^*]+)\\*\\*").replace(res, "$1")
        res = Regex("\\*([^*]+)\\*").replace(res, "$1")
        res = Regex("__([^_]+)__").replace(res, "$1")
        res = Regex("_([^_]+)_").replace(res, "$1")

        // Strikethrough ~~text~~ -> text
        res = Regex("~~([^~]+)~~").replace(res, "$1")

        // Headers: # Title, ## Title -> Title
        res = Regex("(?m)^\\s*#+\\s*").replace(res, "")

        // Bullet markers: -, *, +
        res = Regex("(?m)^\\s*[-*+]\\s+").replace(res, "")

        // Numbered list prefixes: 1. 2.
        res = Regex("(?m)^\\s*\\d+\\.\\s+").replace(res, "")

        // Blockquotes: > Quote
        res = Regex("(?m)^\\s*>+\\s*").replace(res, "")

        // Table rows / separator lines
        res = Regex("\\|[-:]+\\|").replace(res, "")
        res = res.replace("|", ", ")

        return res
    }

    private fun normalizeSpokenPunctuation(input: String): String {
        return input
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\\n{2,}"), ". ")
            .replace("\n", " ")
            .replace(Regex("[,;]{2,}"), ",")
            .replace(Regex("\\.{2,}"), ".")
            .trim()
    }
}
