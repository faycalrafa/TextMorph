package com.textmorph.app.service.overlay.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Transparent full-screen overlay rendering:
 * 1. Side Screen Edge Blue Pulse Glider: Electric blue energy beam gliding up and down along
 *    the physical screen edge during agent execution.
 * 2. Finger Tap Indicator: Animated finger clicking icon and expanding ripple directly
 *    at (x, y) coordinates whenever the LLM interacts with the screen.
 */
@Composable
fun AgentInteractionOverlayContent(
    isTaskRunning: Boolean,
    tapTarget: Pair<Float, Float>?,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val screenHeightPx = with(density) { screenHeightDp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Edge Blue Pulse Glider: Travels smoothly along the right edge of the screen
        if (isTaskRunning) {
            val infiniteTransition = rememberInfiniteTransition(label = "ScreenEdgeGlider")
            val targetMaxOffset = (screenHeightPx - with(density) { 140.dp.toPx() }).coerceAtLeast(100f)

            val gliderY by infiniteTransition.animateFloat(
                initialValue = with(density) { 40.dp.toPx() },
                targetValue = targetMaxOffset,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "GliderY"
            )

            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.65f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "GliderAlpha"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(x = 0, y = gliderY.roundToInt()) }
                    .size(width = 5.dp, height = 110.dp)
                    .alpha(pulseAlpha)
                    .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x003B82F6),
                                Color(0xFF60A5FA),
                                Color(0xFF2563EB),
                                Color(0xFF3B82F6),
                                Color(0x003B82F6)
                            )
                        )
                    )
            )
        }

        // 2. Finger Clicking Indicator: Animated tap pulse & finger icon at coordinates (x, y)
        if (tapTarget != null) {
            val (tapX, tapY) = tapTarget

            // Scale and fade animations for the tap indicator
            val rippleScale = remember { Animatable(0.4f) }
            val tapAlpha = remember { Animatable(1f) }

            LaunchedEffect(tapTarget) {
                rippleScale.snapTo(0.4f)
                tapAlpha.snapTo(1f)
                rippleScale.animateTo(
                    targetValue = 1.8f,
                    animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
                )
            }

            LaunchedEffect(tapTarget) {
                tapAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing)
                )
            }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (tapX - with(density) { 24.dp.toPx() }).roundToInt(),
                            y = (tapY - with(density) { 24.dp.toPx() }).roundToInt()
                        )
                    }
                    .size(48.dp)
                    .alpha(tapAlpha.value),
                contentAlignment = Alignment.Center
            ) {
                // Expanding electric blue circular ripple
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .scale(rippleScale.value)
                        .clip(CircleShape)
                        .background(Color(0x333B82F6))
                        .border(1.5.dp, Color(0xFF60A5FA), CircleShape)
                )

                // Central finger clicking emoji / indicator
                Text(
                    text = "👆",
                    fontSize = 24.sp,
                    modifier = Modifier.scale(1.1f)
                )
            }
        }
    }
}
