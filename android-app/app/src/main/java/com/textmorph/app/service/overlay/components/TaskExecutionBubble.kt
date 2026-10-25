package com.textmorph.app.service.overlay.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.ui.AgentVisualCues
import kotlin.math.roundToInt

/**
 * Messenger-style active agent floating bubble displaying:
 * 1. Leading spinning / thinking circle animation.
 * 2. Center visual cue: thinking emoji 🤔 during planning, or action-specific icon during execution.
 * 3. Edge Blue Pulse Glider: An electric blue vertical glowing pulse traveling up & down on screen edge.
 * 4. Tap-to-interrupt functionality stopping the running task immediately.
 */
@Composable
fun TaskExecutionBubble(
    currentStep: AgentTaskStep?,
    isThinking: Boolean,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val actionType = currentStep?.actionType ?: "thinking"
    val visualCue = AgentVisualCues.resolve(actionType, isThinking = isThinking)

    val infiniteTransition = rememberInfiniteTransition(label = "TaskBubbleAnimations")

    val emojiPulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "EmojiPulse"
    )

    Box(
        modifier = modifier.size(54.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer Thinking Ring: smooth progress sweep
        CircularProgressIndicator(
            modifier = Modifier.size(52.dp),
            color = Color(0xFF3B82F6),
            strokeWidth = 3.dp
        )

        // Inner Charcoal Slate Pill with Electric Blue border
        Surface(
            shape = CircleShape,
            color = Color(0xF2111827), // Charcoal Slate dark midnight
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(42.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onInterrupt
                )
                .border(
                    width = 1.5.dp,
                    color = Color(0xFF3B82F6),
                    shape = CircleShape
                )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(42.dp)
            ) {
                AnimatedContent(
                    targetState = visualCue,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                    label = "BubbleCueTransition"
                ) { cue ->
                    if (cue.emoji != null) {
                        Text(
                            text = cue.emoji,
                            fontSize = 19.sp,
                            modifier = Modifier.scale(emojiPulseScale)
                        )
                    } else if (cue.icon != null) {
                        Icon(
                            imageVector = cue.icon,
                            contentDescription = cue.contentDescription,
                            tint = cue.color,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
