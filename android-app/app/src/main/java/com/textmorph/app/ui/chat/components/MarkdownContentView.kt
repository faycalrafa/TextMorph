package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.concurrency.ImageAssetRegistry

private sealed class MarkdownSegment {
    data class Text(val content: String) : MarkdownSegment()
    data class Image(val caption: String, val url: String) : MarkdownSegment()
}

/**
 * MarkdownContentView renders rich Markdown prose alongside embedded visual asset cards.
 * It automatically pre-resolves short image aliases (IMG_1, IMG_2) via ImageAssetRegistry,
 * extracts `![caption](url)` image references, and renders interactive MarkdownImageCards
 * with lightbox and tap-to-download support.
 */
@Composable
fun MarkdownContentView(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFE5E7EB),
    fontSize: TextUnit = 14.sp
) {
    // 1. Resolve any short-ID image aliases
    val resolvedMarkdown = remember(markdown) {
        ImageAssetRegistry.resolveMarkdownAliases(markdown)
    }

    // 2. Parse into alternating prose and image segments
    val segments = remember(resolvedMarkdown) {
        parseMarkdownSegments(resolvedMarkdown)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (segment in segments) {
            when (segment) {
                is MarkdownSegment.Text -> {
                    if (segment.content.isNotBlank()) {
                        MarkdownText(
                            markdown = segment.content,
                            color = color,
                            fontSize = fontSize
                        )
                    }
                }
                is MarkdownSegment.Image -> {
                    MarkdownImageCard(
                        caption = segment.caption,
                        imageUrl = segment.url
                    )
                }
            }
        }
    }
}

/**
 * Splits a markdown string into sequential text and image segments.
 */
private fun parseMarkdownSegments(markdown: String): List<MarkdownSegment> {
    if (markdown.isBlank()) return emptyList()

    val segments = mutableListOf<MarkdownSegment>()
    val imageRegex = Regex("""!\[(.*?)\]\((https?://[^\s)]+)\)""")
    var currentIndex = 0

    for (match in imageRegex.findAll(markdown)) {
        val matchStart = match.range.first
        val matchEnd = match.range.last + 1

        // Text before the image
        if (matchStart > currentIndex) {
            val textChunk = markdown.substring(currentIndex, matchStart).trim()
            if (textChunk.isNotEmpty()) {
                segments.add(MarkdownSegment.Text(textChunk))
            }
        }

        // The image itself
        val caption = match.groupValues[1]
        val imageUrl = match.groupValues[2]
        segments.add(MarkdownSegment.Image(caption = caption, url = imageUrl))

        currentIndex = matchEnd
    }

    // Trailing text after the last image
    if (currentIndex < markdown.length) {
        val trailingText = markdown.substring(currentIndex).trim()
        if (trailingText.isNotEmpty()) {
            segments.add(MarkdownSegment.Text(trailingText))
        }
    }

    return if (segments.isEmpty() && markdown.isNotBlank()) {
        listOf(MarkdownSegment.Text(markdown))
    } else {
        segments
    }
}
