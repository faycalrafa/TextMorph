package com.textmorph.app.llm.context

import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.util.AppLogger
import java.util.regex.Pattern

/**
 * Strips and collapses large raw payloads, HTML documents, action logs,
 * and internal thinking traces from conversation history for token compaction.
 */
object ContextPruner {

    private const val TAG = "ContextPruner"

    private val HTML_DOC_PATTERN = Pattern.compile("(?is)<(!doctype\\s+html|html)[^>]*>.*?</html\\s*>")
    private val SVG_DOC_PATTERN = Pattern.compile("(?is)<svg[^>]*>.*?</svg\\s*>")
    private val THINK_TAG_PATTERN = Pattern.compile("(?is)<think>.*?</think>")

    /**
     * Collapses raw artifacts and strips thinking traces from a message content string
     * for injection into the LLM context prompt.
     */
    fun pruneForInferenceContext(message: ChatMessageEntity): String {
        try {
            var text = message.content

            // 1. Strip <think> blocks
            text = THINK_TAG_PATTERN.matcher(text).replaceAll("").trim()

            // 2. Collapse full HTML documents to concise summary tokens
            text = collapseHtmlDocuments(text)

            // 3. Collapse standalone SVGs
            text = SVG_DOC_PATTERN.matcher(text).replaceAll("[Generated SVG Vector Graphic]")

            // 4. Collapse action payloads
            text = collapseActionPayload(text, message.actionPayloadJson, message.actionStatus)

            // 5. Truncate extreme long code or log blocks (e.g., > 1200 characters)
            text = truncateLongLogs(text)

            return text.trim()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error pruning message context for ${message.id}: ${e.message}", e)
            return message.content
        }
    }

    /**
     * Collapses HTML content inside markdown fences or raw tags.
     */
    private fun collapseHtmlDocuments(text: String): String {
        var result = text

        val matcher = HTML_DOC_PATTERN.matcher(result)
        if (matcher.find()) {
            val html = matcher.group()
            val title = extractHtmlTitle(html) ?: "Interactive Web App"
            val replacement = "[Generated HTML page: $title]"
            result = matcher.replaceAll(replacement)
        }

        // Also check if entire content is marked with ```html ... ```
        if (result.contains("```html", ignoreCase = true)) {
            result = result.replace(
                Regex("(?is)```html\\s*<!DOCTYPE.*?>.*?```"),
                "[Generated HTML Document]"
            )
        }

        return result
    }

    private fun extractHtmlTitle(html: String): String? {
        val titleMatcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html)
        if (titleMatcher.find()) {
            val title = titleMatcher.group(1)?.trim()
            if (!title.isNullOrBlank()) return title
        }
        val h1Matcher = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>").matcher(html)
        if (h1Matcher.find()) {
            val h1 = h1Matcher.group(1)?.trim()
            if (!h1.isNullOrBlank()) return h1
        }
        return null
    }

    /**
     * Appends concise action summary token if action payload is present.
     */
    private fun collapseActionPayload(text: String, actionPayload: String?, status: String?): String {
        if (!actionPayload.isNullOrBlank()) {
            val statusStr = if (!status.isNullOrBlank()) " [$status]" else ""
            val summary = "[Action: $actionPayload$statusStr]"
            return if (text.isBlank()) summary else "$text\n$summary"
        }
        return text
    }

    /**
     * Truncates long terminal or stacktrace log outputs.
     */
    private fun truncateLongLogs(text: String): String {
        if (text.length <= 1500) return text
        val lines = text.lines()
        if (lines.size > 50) {
            val head = lines.take(15).joinToString("\n")
            val tail = lines.takeLast(10).joinToString("\n")
            val omitted = lines.size - 25
            return "$head\n\n[... $omitted log lines collapsed for context ...]\n\n$tail"
        }
        return text
    }

    /**
     * Strips large artifacts permanently from a message entity if requested by the user.
     */
    fun pruneMessageEntity(message: ChatMessageEntity): ChatMessageEntity {
        val prunedContent = pruneForInferenceContext(message)
        return message.copy(
            content = prunedContent,
            thoughtContent = null // Discard internal thoughts during permanent pruning
        )
    }
}
