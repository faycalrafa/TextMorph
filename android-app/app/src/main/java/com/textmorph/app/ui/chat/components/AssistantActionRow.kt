package com.textmorph.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.util.AppLogger
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Responsive action row for assistant messages:
 * - Copy with feedback
 * - Share intent
 * - Regenerate
 * - Sources button pill (if sources available)
 * - Telemetry speed chip
 * Enforces Primary Rule 5 (Anti-Squish Contract) using FlowRow with spacedBy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantActionRow(
    message: ChatMessageEntity,
    onRegenerate: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }
    val sources = remember(message.sourcesJson) { message.getSources() }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Copy Action
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF1F2937),
            border = BorderStroke(1.dp, Color(0xFF374151)),
            modifier = Modifier.clickable {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Response", message.content))
                    isCopied = true
                    Toast.makeText(context, "Copied response", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        delay(2000)
                        isCopied = false
                    }
                } catch (t: Throwable) {
                    AppLogger.w("AssistantActionRow", "Copy error: ${t.message}")
                }
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(targetState = isCopied, label = "copyRow") { copied ->
                    if (copied) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Copied",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(13.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isCopied) "Copied" else "Copy",
                    fontSize = 11.5.sp,
                    color = Color(0xFF9CA3AF),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 2. Share Action
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF1F2937),
            border = BorderStroke(1.dp, Color(0xFF374151)),
            modifier = Modifier.clickable {
                try {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, message.content)
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, "Share response").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(shareIntent)
                } catch (t: Throwable) {
                    AppLogger.w("AssistantActionRow", "Share error: ${t.message}")
                }
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Share",
                    fontSize = 11.5.sp,
                    color = Color(0xFF9CA3AF),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 3. Regenerate Action (if callback provided)
        if (onRegenerate != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF1F2937),
                border = BorderStroke(1.dp, Color(0xFF374151)),
                modifier = Modifier.clickable { onRegenerate() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Regenerate",
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Retry",
                        fontSize = 11.5.sp,
                        color = Color(0xFF9CA3AF),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 4. Sources Pill Button
        if (sources.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF1E3A8A).copy(alpha = 0.5f), // Dark navy blue
                border = BorderStroke(1.dp, Color(0xFF3B82F6)),
                modifier = Modifier.clickable { onOpenSources?.invoke() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Sources (${sources.size})",
                        fontSize = 11.5.sp,
                        color = Color(0xFF93C5FD),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 5. Speed Telemetry Chip
        if (message.tokensPerSecond != null && message.tokensPerSecond > 0f) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF111827),
                border = BorderStroke(1.dp, Color(0xFF374151))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Speed",
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = buildString {
                            append(String.format(Locale.US, "%.1f tok/s", message.tokensPerSecond))
                            if (message.totalTokens != null && message.totalTokens > 0) {
                                append(" • ")
                                append(message.totalTokens)
                                append(" tok")
                            }
                            if (message.durationMs != null && message.durationMs > 0) {
                                append(" • ")
                                append(String.format(Locale.US, "%.1fs", message.durationMs / 1000f))
                            }
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF60A5FA)
                    )
                }
            }
        }
    }
}
