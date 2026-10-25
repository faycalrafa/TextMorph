package com.textmorph.app.ui.chat.components

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

/**
 * Renders rich Markdown text (bold, italics, lists, inline code, code blocks, links)
 * using Markwon in a dark Charcoal Slate-styled TextView.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFE5E7EB),
    fontSize: TextUnit = 14.sp
) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val textColorArgb = color.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColorArgb)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value)
                setLineSpacing(0f, 1.25f)
                movementMethod = LinkMovementMethod.getInstance()
                isFocusable = false
                isClickable = false
                setLinkTextColor(android.graphics.Color.parseColor("#60A5FA"))
            }
        },
        update = { textView ->
            textView.setTextColor(textColorArgb)
            markwon.setMarkdown(textView, markdown)
        }
    )
}
