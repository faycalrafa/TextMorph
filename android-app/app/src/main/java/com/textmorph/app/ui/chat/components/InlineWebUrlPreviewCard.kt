package com.textmorph.app.ui.chat.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.util.AppLogger

/**
 * Clean inline web preview card for cited or generated URLs.
 */
@Composable
fun InlineWebUrlPreviewCard(
    url: String,
    title: String? = null,
    snippet: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val domain = try {
        Uri.parse(url).host ?: url
    } catch (_: Exception) {
        url
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF111827),
        border = BorderStroke(1.dp, Color(0xFF374151)),
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    AppLogger.w("InlineWebUrlPreview", "Failed opening url $url: ${t.message}")
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1F2937),
                modifier = Modifier.size(36.dp)
            ) {
                BoxWithIcon()
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title ?: domain,
                    color = Color(0xFFF9FAFB),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!snippet.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = snippet,
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = domain,
                    color = Color(0xFF60A5FA),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.OpenInBrowser,
                contentDescription = "Open in browser",
                tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun BoxWithIcon() {
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = Color(0xFF60A5FA),
            modifier = Modifier.size(20.dp)
        )
    }
}
