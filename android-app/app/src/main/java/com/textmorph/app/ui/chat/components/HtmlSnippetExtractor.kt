package com.textmorph.app.ui.chat.components

/**
 * Encapsulates partitioned segments of a chat message containing an embedded HTML document.
 */
data class HtmlSegments(
    val preText: String,
    val htmlContent: String?,
    val isHtmlComplete: Boolean,
    val postText: String
)

/**
 * Extracts embedded HTML documents from assistant responses while isolating
 * any preceding preamble prose and succeeding postscript text.
 */
object HtmlSnippetExtractor {

    fun extractSegments(content: String, isDone: Boolean): HtmlSegments {
        val trimmed = content.trim()
        val htmlFenceIndex = trimmed.indexOf("```html", ignoreCase = true)

        if (htmlFenceIndex != -1) {
            val preText = trimmed.substring(0, htmlFenceIndex).trim()
            val codeStart = htmlFenceIndex + 7
            val closingFenceIndex = trimmed.indexOf("```", codeStart)

            return if (closingFenceIndex != -1) {
                val htmlContent = trimmed.substring(codeStart, closingFenceIndex).trim()
                val postText = trimmed.substring(closingFenceIndex + 3).trim()
                HtmlSegments(
                    preText = preText,
                    htmlContent = htmlContent,
                    isHtmlComplete = true,
                    postText = postText
                )
            } else {
                val partialHtml = trimmed.substring(codeStart).trim()
                val hasClosingTag = partialHtml.contains("</html>", ignoreCase = true)
                HtmlSegments(
                    preText = preText,
                    htmlContent = partialHtml,
                    isHtmlComplete = isDone && hasClosingTag,
                    postText = ""
                )
            }
        }

        // Check for raw HTML tags (e.g., <!DOCTYPE html or <html>)
        val docTypeIndex = trimmed.indexOf("<!DOCTYPE html", ignoreCase = true)
        val htmlTagIndex = if (docTypeIndex == -1) trimmed.indexOf("<html", ignoreCase = true) else docTypeIndex

        if (htmlTagIndex != -1) {
            val preText = trimmed.substring(0, htmlTagIndex).trim()
            val closingTagIndex = trimmed.indexOf("</html>", htmlTagIndex, ignoreCase = true)

            return if (closingTagIndex != -1) {
                val endTagClose = closingTagIndex + 7
                val htmlContent = trimmed.substring(htmlTagIndex, endTagClose).trim()
                val postText = trimmed.substring(endTagClose).trim()
                HtmlSegments(
                    preText = preText,
                    htmlContent = htmlContent,
                    isHtmlComplete = true,
                    postText = postText
                )
            } else {
                val partialHtml = trimmed.substring(htmlTagIndex).trim()
                HtmlSegments(
                    preText = preText,
                    htmlContent = partialHtml,
                    isHtmlComplete = isDone,
                    postText = ""
                )
            }
        }

        // Plain text message (no HTML detected)
        return HtmlSegments(
            preText = trimmed,
            htmlContent = null,
            isHtmlComplete = true,
            postText = ""
        )
    }
}
