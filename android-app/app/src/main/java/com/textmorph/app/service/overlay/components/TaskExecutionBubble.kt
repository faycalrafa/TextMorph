package com.textmorph.app.service.overlay.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.ui.AgentVisualCues

/**
 * Messenger-style living active agent floating companion bubble displaying:
 * 1. Subtle idle floating drift (gentle sinusoidal bobbing in air).
 * 2. Animated looking & blinking eyes (👀) when inspecting/reading the screen.
 * 3. Thinking emoji (🤔) strictly during LLM inference/planning (never while viewing screen).
 * 4. Action cues: ⌨️ keyboard for typing, 👆 finger for tapping, 👂 for listening.
 * 5. Smooth progress ring.
 * 6. Tap-to-interrupt functionality stopping the running task immediately.
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

    val infiniteTransition = rememberInfiniteTransition(label = "LivingBubbleAnimations")

    // Subtle sinusoidal floating drift
    val floatOffsetY by infiniteTransition.animateFloat(
        initialValue = -3.5f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FloatingBobbingY"
    )
    val floatOffsetX by infiniteTransition.animateFloat(
        initialValue = -2.0f,
        targetValue = 2.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FloatingBobbingX"
    )

    // Pulse scale for active action icons/emojis
    val emojiPulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "EmojiPulse"
    )

    Box(
        modifier = modifier.size(56.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer Progress Ring: smooth electric blue sweep
        CircularProgressIndicator(
            modifier = Modifier.size(52.dp),
            color = if (isThinking) Color(0xFFFBBF24) else Color(0xFF3B82F6),
            strokeWidth = 3.dp
        )

        // Inner Charcoal Slate Floating Companion Orb with living bobbing offset
        Surface(
            shape = CircleShape,
            color = Color(0xF2111827), // Charcoal Slate midnight
            shadowElevation = 8.dp,
            modifier = Modifier
                .offset(x = floatOffsetX.dp, y = floatOffsetY.dp)
                .size(42.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onInterrupt
                )
                .border(
                    width = 1.5.dp,
                    color = if (isThinking) Color(0xFFFBBF24) else Color(0xFF38BDF8),
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
                    if (cue.emoji == "👀") {
                        // Animated eyes that glance left/right and periodically blink
                        ScreenInspectingEyes(modifier = Modifier.size(24.dp))
                    } else if (cue.emoji != null) {
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

/**
 * Animated eyes that glance left-right and periodically blink when reading the screen.
 */
@Composable
fun ScreenInspectingEyes(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "InspectingEyesTransition")

    // Glancing look-around horizontal shift
    val lookOffsetX by transition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "EyesLookX"
    )

    // Periodic quick eyelid blink every 2.8s
    val blinkScaleY by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2800
                1.0f at 0
                1.0f at 2550
                0.08f at 2680
                1.0f at 2800
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "EyesBlink"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Text(
            text = "👀",
            fontSize = 20.sp,
            modifier = Modifier
                .offset(x = lookOffsetX.dp)
                .scale(scaleX = 1.05f, scaleY = blinkScaleY)
        )
    }
}
