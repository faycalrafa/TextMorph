package com.textmorph.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Modern code block card in Charcoal Slate theme:
 * - Language badge in header bar
 * - One-tap copy with animated checkmark feedback
 * - Horizontally scrollable monospace code body
 */
@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF111827), // Midnight Slate
        border = BorderStroke(1.dp, Color(0xFF374151)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // Header Bar
            Surface(
                color = Color(0xFF1F2937),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language.ifBlank { "code" }.lowercase(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF9CA3AF)
                    )

                    IconButton(
                        onClick = {
                            try {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Code", code))
                                isCopied = true
                                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    delay(2000)
                                    isCopied = false
                                }
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        AnimatedContent(targetState = isCopied, label = "copyAnim") { copied ->
                            if (copied) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Copied",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(15.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy code",
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Code Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFFF3F4F6)
                )
            }
        }
    }
}
