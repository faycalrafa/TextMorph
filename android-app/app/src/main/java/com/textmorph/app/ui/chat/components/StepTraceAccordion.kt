package com.textmorph.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AgentStepTrace
import java.util.Locale

/**
 * Unified Step Progress Accordion.
 * Consolidates all multi-step actions (web/image search, JS tools, screen automation, /plan, /goal).
 * - Expanded by default while running.
 * - Automatically collapses upon completion into a clean summary header.
 * - Tappable to re-expand and view step diagnostics, inputs, and failure reasons.
 */
@Composable
fun StepTraceAccordion(
    traces: List<AgentStepTrace>,
    isRunning: Boolean,
    onInterrupt: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (traces.isEmpty()) return

    val hasFailed = traces.any { it.status == "failed" }
    val hasCancelled = traces.any { it.status == "cancelled" || it.status == "interrupted" }

    var isExpanded by remember { mutableStateOf(isRunning || hasFailed) }

    // Auto-collapse when finished successfully, keep expanded if failed
    LaunchedEffect(isRunning) {
        if (!isRunning) {
            isExpanded = hasFailed || hasCancelled
        } else {
            isExpanded = true
        }
    }

    val totalDurationMs = remember(traces) {
        traces.sumOf { it.durationMs }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111827), // Charcoal Slate midnight
        border = BorderStroke(
            1.dp,
            when {
                hasFailed -> Color(0xFFEF4444).copy(alpha = 0.4f)
                hasCancelled -> Color(0xFFF59E0B).copy(alpha = 0.4f)
                isRunning -> Color(0xFF3B82F6).copy(alpha = 0.5f)
                else -> Color(0xFF374151)
            }
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .alpha(pulseAlpha)
                                .background(Color(0xFF3B82F6), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Executing step ${traces.size}: ${traces.lastOrNull()?.toolName.orEmpty()}…",
                            color = Color(0xFF60A5FA),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        val headerIcon = when {
                            hasFailed -> Icons.Default.ErrorOutline
                            hasCancelled -> Icons.Default.StopCircle
                            else -> Icons.Default.CheckCircle
                        }
                        val headerColor = when {
                            hasFailed -> Color(0xFFF87171)
                            hasCancelled -> Color(0xFFFBBF24)
                            else -> Color(0xFF4ADE80)
                        }
                        Icon(
                            imageVector = headerIcon,
                            contentDescription = null,
                            tint = headerColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val durationStr = if (totalDurationMs > 0) String.format(Locale.US, " • %.1fs", totalDurationMs / 1000f) else ""
                        val statusLabel = when {
                            hasFailed -> "Halted with error"
                            hasCancelled -> "Stopped by user"
                            else -> "Completed in ${traces.size} step${if (traces.size > 1) "s" else ""}$durationStr"
                        }
                        Text(
                            text = statusLabel,
                            color = Color(0xFFE5E7EB),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning && onInterrupt != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x33EF4444),
                            border = BorderStroke(1.dp, Color(0xFFEF4444)),
                            modifier = Modifier
                                .clickable(onClick = onInterrupt)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = "Stop",
                                color = Color(0xFFF87171),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Expandable Step List
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    traces.forEach { step ->
                        StepItemRow(
                            step = step,
                            isRunning = isRunning && step == traces.lastOrNull() && step.status == "running",
                            pulseAlpha = pulseAlpha
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun StepItemRow(
    step: AgentStepTrace,
    isRunning: Boolean,
    pulseAlpha: Float
) {
    val (icon, tint) = resolveToolIconAndTint(step.toolName)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1F2937),
        border = BorderStroke(1.dp, if (step.status == "failed") Color(0xFFEF4444).copy(alpha = 0.5f) else Color(0xFF374151)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Step ${step.stepIndex}: ${step.toolName}",
                        color = Color(0xFFF9FAFB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (step.inputQuery.isNotBlank()) {
                        Text(
                            text = " (${step.inputQuery})",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .alpha(pulseAlpha)
                                .background(Color(0xFF3B82F6), CircleShape)
                        )
                    } else {
                        val statusText = when (step.status) {
                            "success" -> if (step.durationMs > 0) "${step.durationMs}ms" else "Done"
                            "failed" -> "Failed"
                            "cancelled", "interrupted" -> "Cancelled"
                            else -> step.status
                        }
                        val statusColor = when (step.status) {
                            "success" -> Color(0xFF4ADE80)
                            "failed" -> Color(0xFFF87171)
                            "cancelled", "interrupted" -> Color(0xFFFBBF24)
                            else -> Color(0xFF9CA3AF)
                        }
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Diagnostic failure message (if failed)
            val failure = step.failureReason
            if (!failure.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2A1515),
                    border = BorderStroke(1.dp, Color(0xFF7F1D1D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = failure,
                        color = Color(0xFFFCA5A5),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

private fun resolveToolIconAndTint(toolName: String): Pair<ImageVector, Color> {
    val lower = toolName.lowercase()
    return when {
        lower.contains("search_web") || lower.contains("search") -> Pair(Icons.Default.Search, Color(0xFF38BDF8))
        lower.contains("fetch_page") || lower.contains("page") -> Pair(Icons.Default.Language, Color(0xFF38BDF8))
        lower.contains("search_images") || lower.contains("image") -> Pair(Icons.Default.Image, Color(0xFF38BDF8))
        lower.contains("js_tool") || lower.contains("weather") || lower.contains("calc") || lower.contains("converter") || lower.startsWith("tool_") ->
            Pair(Icons.Default.Code, Color(0xFFA78BFA))
        lower.contains("click") || lower.contains("type") || lower.contains("scroll") || lower.contains("press") || lower.contains("open_app") ->
            Pair(Icons.Default.TouchApp, Color(0xFFFBBF24))
        else -> Pair(Icons.Default.PlayCircleFilled, Color(0xFF60A5FA))
    }
}
