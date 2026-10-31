package com.textmorph.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ChatAttachment
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.util.AppLogger
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
    onInterruptAction: (() -> Unit)? = null,
    onSelectOption: ((String) -> Unit)? = null,
    onSelectSuggestion: ((String) -> Unit)? = null,
    onViewArtifact: ((com.textmorph.app.data.model.ArtifactMetadata) -> Unit)? = null,
    onExecutePlan: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    var showUserMenu by remember { mutableStateOf(false) }
    var showSourcesSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        if (isUser) {
            val attachments = remember(message.attachmentsJson) { message.getAttachments() }
            if (attachments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    attachments.forEach { item ->
                        UserAttachmentItem(item)
                    }
                }
            }

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
                                AppLogger.d("ChatMessageItemView", "Clipboard copy non-fatal error: ${e.message}", e)
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

                // 3. Step Trace Accordion (Unified for all multi-step tasks & tools)
                val stepTraces = remember(message.stepTracesJson, message.actionStepsJson) { message.getStepTraces() }
                if (stepTraces.isNotEmpty()) {
                    StepTraceAccordion(
                        traces = stepTraces,
                        isRunning = isGenerating,
                        onInterrupt = onInterruptAction
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 4. Artifact Summary Card (if document/report produced)
                val artifact = remember(message.artifactPayloadJson) { message.getArtifact() }
                if (artifact != null) {
                    ArtifactSummaryCard(
                        artifact = artifact,
                        onViewArtifact = { onViewArtifact?.invoke(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (artifact.type == "plan" && onExecutePlan != null) {
                        PlanExecutionActionCard(
                            planSummary = artifact.summary,
                            onExecutePlan = { onExecutePlan(artifact.content) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                val approval = remember(message.content) {
                    ChatToolCallExtractor.extractApproval(message.content)
                }
                val followUps = remember(message.content) {
                    ChatToolCallExtractor.extractFollowUps(message.content)
                }
                val interactiveView = remember(message.content) {
                    ChatToolCallExtractor.extractInteractiveViewHtml(message.content)
                }

                if (approval != null) {
                    if (approval.preText.isNotBlank()) {
                        AssistantTextBubble(text = approval.preText)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    UserApprovalCard(
                        question = approval.question,
                        options = approval.options,
                        onSelectOption = { onSelectOption?.invoke(it) }
                    )
                    if (approval.postText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        AssistantTextBubble(text = approval.postText)
                    }
                } else if (message.content.isNotBlank()) {
                    val rawDisplayContent = followUps?.remainingContent?.ifBlank { null } ?: message.content
                    val htmlToRender = interactiveView?.second
                    val segments = HtmlSnippetExtractor.extractSegments(
                        content = rawDisplayContent,
                        isDone = !isGenerating
                    )
                    val effectiveHtml = htmlToRender ?: segments.htmlContent

                    if (effectiveHtml != null) {
                        if (segments.preText.isNotBlank()) {
                            AssistantTextBubble(text = segments.preText)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        HtmlSandboxPreviewCard(
                            htmlContent = effectiveHtml,
                            isDone = segments.isHtmlComplete || !isGenerating
                        )

                        if (segments.postText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            AssistantTextBubble(text = segments.postText)
                        }
                    } else if (rawDisplayContent.isNotBlank()) {
                        AssistantTextBubble(text = rawDisplayContent)
                    }

                    if (followUps != null && followUps.options.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        FollowUpSuggestionsRow(
                            options = followUps.options,
                            onSelectOption = { option ->
                                onSelectSuggestion?.invoke(option.actionCommand ?: option.title)
                            }
                        )
                    }
                }

                // 5. Assistant Action Row (Copy, Share, Retry, Sources, Telemetry)
                if (message.content.isNotBlank() || stepTraces.isNotEmpty() || artifact != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AssistantActionRow(
                        message = message,
                        onRegenerate = if (onRetryPrompt != null) { { onRetryPrompt(message) } } else null,
                        onOpenSources = { showSourcesSheet = true }
                    )
                }

                // Sources Bottom Sheet
                val sources = remember(message.sourcesJson) { message.getSources() }
                if (showSourcesSheet && sources.isNotEmpty()) {
                    MessageSourcesBottomSheet(
                        sources = sources,
                        onDismiss = { showSourcesSheet = false }
                    )
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
    val sanitized = remember(text) {
        text.replace(Regex("<think>[\\s\\S]*?(?:</think>|$)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<thought>[\\s\\S]*?(?:</thought>|$)", RegexOption.IGNORE_CASE), "")
            .trim()
    }
    if (sanitized.isBlank()) return

    Surface(
        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        color = Color(0xFF1F2937), // Dark Slate
        border = BorderStroke(1.dp, Color(0xFF374151)),
        shadowElevation = 1.dp
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            MarkdownContentView(markdown = sanitized)
        }
    }
}

@Composable
private fun UserAttachmentItem(attachment: ChatAttachment) {
    val context = LocalContext.current
    val isImage = attachment.mimeType.startsWith("image/")

    if (isImage) {
        val bitmap = remember(attachment.localUri) {
            try {
                val uri = attachment.localUri?.let { Uri.parse(it) }
                if (uri != null) {
                    if (uri.scheme == "file") {
                        BitmapFactory.decodeFile(uri.path)
                    } else {
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    }
                } else null
            } catch (e: Exception) {
                null
            }
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.name,
                modifier = Modifier
                    .sizeIn(maxWidth = 200.dp, maxHeight = 160.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
    } else {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = Color(0xFF93C5FD),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = attachment.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}
