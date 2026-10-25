package com.textmorph.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.model.AgentStepStatus
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.ui.AgentVisualCues
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.theme.DangerRed

/**
 * Interactive step-by-step agent task execution timeline card.
 * Replaces static action boxes with clear, live step progression and interruptibility.
 */
@Composable
fun AgentTaskTimelineCard(
    message: ChatMessageEntity,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = remember(message.actionStepsJson) { message.getActionSteps() }
    val isRunning = message.actionStatus == "running"
    val isInterrupted = message.actionStatus == "interrupted"
    val isFailed = message.actionStatus == "failed"
    var isExpanded by remember { mutableStateOf(true) }

    val infiniteTransition = rememberInfiniteTransition(label = "TimelinePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TimelinePulseScale"
    )

    val (headerBorder, headerIcon, headerColor) = when {
        isRunning -> Triple(Color(0xFF3B82F6), null, Color(0xFF3B82F6))
        isInterrupted -> Triple(DangerRed, Icons.Default.StopCircle, DangerRed)
        isFailed -> Triple(DangerRed, Icons.Default.ErrorOutline, DangerRed)
        else -> Triple(Color(0xFF10B981), Icons.Default.CheckCircle, Color(0xFF10B981))
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2937), // Elevated Charcoal Slate
        border = BorderStroke(1.dp, headerBorder.copy(alpha = 0.5f)),
        shadowElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .scale(if (isRunning) pulseScale else 1f)
                            .background(headerColor.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = headerColor,
                                strokeWidth = 2.5.dp
                            )
                        } else if (headerIcon != null) {
                            Icon(
                                imageVector = headerIcon,
                                contentDescription = null,
                                tint = headerColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = when {
                                isRunning -> "Agent Automating Task…"
                                isInterrupted -> "Task Stopped by User"
                                isFailed -> "Task Failed"
                                else -> "Task Completed"
                            },
                            color = Color(0xFFF9FAFB),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (steps.isNotEmpty()) {
                            Text(
                                text = "${steps.size} steps recorded",
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning) {
                        Button(
                            onClick = onInterrupt,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DangerRed,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF374151),
                        modifier = Modifier
                            .size(26.dp)
                            .clickable { isExpanded = !isExpanded }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Timeline",
                                tint = Color(0xFFE5E7EB),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Summary description
            if (message.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message.content,
                    color = Color(0xFFD1D5DB),
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            }

            // Expandable Step Timeline
            AnimatedVisibility(visible = isExpanded && steps.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    steps.forEachIndexed { index, step ->
                        StepTimelineRow(
                            step = step,
                            isLast = index == steps.lastIndex
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTimelineRow(
    step: AgentTaskStep,
    isLast: Boolean
) {
    val visualCue = AgentVisualCues.resolve(step.actionType)
    val isStepRunning = step.status == AgentStepStatus.RUNNING
    val isStepInterrupted = step.status == AgentStepStatus.INTERRUPTED
    val isStepFailed = step.status == AgentStepStatus.FAILED

    Row(modifier = Modifier.fillMaxWidth()) {
        // Vertical step node & connector line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(visualCue.color.copy(alpha = 0.2f), CircleShape)
                    .border(1.dp, visualCue.color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isStepRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = visualCue.color,
                        strokeWidth = 2.dp
                    )
                } else if (visualCue.icon != null) {
                    Icon(
                        imageVector = visualCue.icon,
                        contentDescription = null,
                        tint = visualCue.color,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(28.dp)
                        .background(Color(0xFF374151))
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Step content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 2.dp else 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = step.title.ifEmpty { visualCue.label },
                    color = Color(0xFFF9FAFB),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        isStepRunning -> Color(0x333B82F6)
                        isStepInterrupted -> Color(0x33EF4444)
                        isStepFailed -> Color(0x33EF4444)
                        else -> Color(0x3310B981)
                    }
                ) {
                    Text(
                        text = when {
                            isStepRunning -> "In Progress"
                            isStepInterrupted -> "Stopped"
                            isStepFailed -> "Failed"
                            else -> "Done"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            isStepRunning -> Color(0xFF60A5FA)
                            isStepInterrupted -> DangerRed
                            isStepFailed -> DangerRed
                            else -> Color(0xFF34D399)
                        },
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            if (step.detail.isNotBlank() && step.detail != step.title) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = step.detail,
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
