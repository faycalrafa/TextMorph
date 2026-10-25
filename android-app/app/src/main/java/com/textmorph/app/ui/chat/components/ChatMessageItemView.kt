package com.textmorph.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ChatMessageEntity
import java.util.Locale

/**
 * Standard chat message bubble styled in the Charcoal Slate design language:
 * - User: Electric Blue (#2563EB) with crisp white text. Long-press allows editing and retry rewinds.
 * - Assistant: Dark Slate (#1F2937) with #374151 border and #E5E7EB text.
 * - Transient Status Bubble: Displays live loading/thinking states without persisting.
 * - HTML Isolation: Displays pre/post prose cleanly and guards streaming preview.
 * - Speed Telemetry: Monospace chip displaying generation speed (tok/s) and duration.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItemView(
    message: ChatMessageEntity,
    isGenerating: Boolean,
    generationStatus: String? = null,
    onEditPrompt: ((ChatMessageEntity) -> Unit)? = null,
    onRetryPrompt: ((ChatMessageEntity) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    var showUserMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        if (isUser) {
            Box {
                Surface(
                    shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                    color = Color(0xFF2563EB), // Electric Blue
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showUserMenu = true }
                        )
                ) {
                    Text(
                        text = message.content,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }

                DropdownMenu(
                    expanded = showUserMenu,
                    onDismissRequest = { showUserMenu = false },
                    modifier = Modifier.background(Color(0xFF1F2937))
                ) {
                    if (onEditPrompt != null) {
                        DropdownMenuItem(
                            text = { Text("Edit & Resend", color = Color(0xFFF9FAFB), fontSize = 13.5.sp) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showUserMenu = false
                                onEditPrompt(message)
                            }
                        )
                    }
                    if (onRetryPrompt != null) {
                        DropdownMenuItem(
                            text = { Text("Retry from Here", color = Color(0xFFF9FAFB), fontSize = 13.5.sp) },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showUserMenu = false
                                onRetryPrompt(message)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Copy Text", color = Color(0xFFE5E7EB), fontSize = 13.5.sp) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.size(16.dp)) },
                        onClick = {
                            showUserMenu = false
                            try {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Prompt", message.content))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                // Ignore clipboard failure
                            }
                        }
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(0.94f)) {
                // 1. Transient status bubble when model is loading or starting thinking
                val isPlaceholder = isGenerating && message.content.isBlank() && message.thoughtContent.isNullOrBlank()
                if (isPlaceholder) {
                    AssistantStatusBubble(statusText = generationStatus ?: "Thinking…")
                }

                // 2. Thinking Accordion (if thoughts present)
                if (!message.thoughtContent.isNullOrBlank()) {
                    ThinkingAccordionCard(
                        thoughtContent = message.thoughtContent,
                        isThinking = isGenerating && message.content.isBlank()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // 3. Action or HTML or Standard Content
                val isAction = message.actionPayloadJson != null ||
                    message.role == "action" ||
                    (message.content.trim().startsWith("{\"action\":") && message.content.contains("}"))

                if (isAction) {
                    ActionExecutionCard(
                        actionName = "Autonomous Action",
                        reasoning = message.content,
                        status = message.actionStatus ?: "success"
                    )
                } else {
                    val segments = HtmlSnippetExtractor.extractSegments(
                        content = message.content,
                        isDone = !isGenerating
                    )

                    if (segments.htmlContent != null) {
                        // Render pre-text prose if present
                        if (segments.preText.isNotBlank()) {
                            AssistantTextBubble(text = segments.preText)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Render sandboxed HTML preview (or loading pulse card)
                        HtmlSandboxPreviewCard(
                            htmlContent = segments.htmlContent,
                            isDone = segments.isHtmlComplete
                        )

                        // Render post-text prose if present
                        if (segments.postText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            AssistantTextBubble(text = segments.postText)
                        }
                    } else if (message.content.isNotBlank()) {
                        AssistantTextBubble(text = message.content)
                    }
                }

                // 4. Speed & Performance Metrics Badge
                if (message.tokensPerSecond != null && message.tokensPerSecond > 0f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF111827), // Midnight slate
                        border = BorderStroke(1.dp, Color(0xFF374151))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
    }
}

@Composable
private fun AssistantStatusBubble(statusText: String) {
    Surface(
        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        color = Color(0xFF1F2937),
        border = BorderStroke(1.dp, Color(0xFF374151)),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                color = Color(0xFF60A5FA),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = statusText,
                color = Color(0xFF9CA3AF),
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

@Composable
private fun AssistantTextBubble(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        color = Color(0xFF1F2937), // Dark Slate
        border = BorderStroke(1.dp, Color(0xFF374151)),
        shadowElevation = 1.dp
    ) {
        Text(
            text = text,
            color = Color(0xFFE5E7EB),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
