package com.textmorph.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.util.AppLogger
import java.util.Locale

/**
 * Responsive modal dialog displaying complete, untruncated details of any agent action or tool call.
 * Displays the full LLM command/input, reasoning thought, complete output, and error traces.
 * Conforms strictly to Charcoal Slate design standards and the anti-squish FlowRow contract.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepDetailsDialog(
    step: AgentStepTrace,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF111827), // Charcoal Slate midnight surface
            border = BorderStroke(1.dp, Color(0xFF374151))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // 1. Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1F2937),
                            border = BorderStroke(1.dp, Color(0xFF374151)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (step.status == "failed") Icons.Default.ErrorOutline else Icons.Default.Code,
                                    contentDescription = null,
                                    tint = if (step.status == "failed") Color(0xFFF87171) else Color(0xFF60A5FA),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Step ${step.stepIndex}: ${step.toolName}",
                                color = Color(0xFFF9FAFB),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusBadge(status = step.status)
                                if (step.durationMs > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = String.format(Locale.US, "%.2fs", step.durationMs / 1000f),
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 520.dp)
                        .verticalScroll(scrollState)
                ) {
                    // Section A: LLM Command / Input
                    if (step.inputQuery.isNotBlank()) {
                        SectionLabel(title = "Command / Input Query")
                        Spacer(modifier = Modifier.height(6.dp))
                        CodeBlockCard(
                            content = step.inputQuery,
                            onCopy = { copyToClipboard(context, "Command Input", step.inputQuery) }
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Section B: Step Thought / Rationale
                    val thoughtText = step.thought?.ifBlank { null } ?: step.stepResponse?.ifBlank { null }
                    if (!thoughtText.isNullOrBlank()) {
                        SectionLabel(title = "Reasoning & Rationale")
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1F2937),
                            border = BorderStroke(1.dp, Color(0xFF374151)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = thoughtText,
                                color = Color(0xFFE5E7EB),
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Section C: Failure Reason (if failed)
                    if (!step.failureReason.isNullOrBlank()) {
                        SectionLabel(title = "Failure Diagnostics", isError = true)
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2A1515),
                            border = BorderStroke(1.dp, Color(0xFF7F1D1D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = step.failureReason ?: "",
                                color = Color(0xFFFCA5A5),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Section D: Complete Output / Response
                    val output = step.outputSnippet
                    if (!output.isNullOrBlank()) {
                        SectionLabel(title = "Complete Output / Response")
                        Spacer(modifier = Modifier.height(6.dp))
                        CodeBlockCard(
                            content = output,
                            onCopy = { copyToClipboard(context, "Tool Output", output) }
                        )
                    } else if (step.status == "running") {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1F2937),
                            border = BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Action is currently executing in the background…",
                                color = Color(0xFF60A5FA),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Responsive Footer Action Row (Rule 5 Anti-Squish FlowRow)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!step.outputSnippet.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, "Full Output", step.outputSnippet.orEmpty()) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF3B82F6)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF60A5FA))
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Output", fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val allData = buildString {
                                appendLine("Tool: ${step.toolName}")
                                appendLine("Step: ${step.stepIndex}")
                                appendLine("Status: ${step.status}")
                                appendLine("Duration: ${step.durationMs}ms")
                                if (step.inputQuery.isNotBlank()) appendLine("Input: ${step.inputQuery}")
                                if (!step.outputSnippet.isNullOrBlank()) appendLine("Output:\n${step.outputSnippet}")
                                if (!step.failureReason.isNullOrBlank()) appendLine("Error: ${step.failureReason}")
                            }
                            copyToClipboard(context, "Step Details", allData)
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF374151)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE5E7EB))
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Full Trace", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF4B5563)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9CA3AF))
                    ) {
                        Text("Close", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, isError: Boolean = false) {
    Text(
        text = title,
        color = if (isError) Color(0xFFF87171) else Color(0xFF9CA3AF),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun StatusBadge(status: String) {
    val (color, icon, text) = when (status) {
        "success" -> Triple(Color(0xFF4ADE80), Icons.Default.CheckCircle, "Completed")
        "failed" -> Triple(Color(0xFFF87171), Icons.Default.ErrorOutline, "Failed")
        "cancelled", "interrupted" -> Triple(Color(0xFFFBBF24), Icons.Default.StopCircle, "Cancelled")
        else -> Triple(Color(0xFF60A5FA), Icons.Default.PlayCircleFilled, "Running")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            color = color,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CodeBlockCard(
    content: String,
    onCopy: () -> Unit
) {
    val hScroll = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0B1020), // Charcoal Slate deepest code background
        border = BorderStroke(1.dp, Color(0xFF374151)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Text(
                text = content,
                color = Color(0xFFE5E7EB),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll)
                    .padding(end = 28.dp)
            )

            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy snippet",
                    tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
    } catch (t: Throwable) {
        AppLogger.w("StepDetailsDialog", "Failed copying to clipboard: ${t.message}")
    }
}
