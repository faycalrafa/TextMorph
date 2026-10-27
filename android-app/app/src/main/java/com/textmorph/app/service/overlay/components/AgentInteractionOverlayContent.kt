package com.textmorph.app.service.overlay.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Transparent full-screen overlay rendering the agent's interaction feedback:
 * Leaves a small precision clicking trace dot at (x, y) that smoothly fades out over 2 seconds.
 * The edge laser glider has been removed.
 */
@Composable
fun AgentInteractionOverlayContent(
    isTaskRunning: Boolean,
    tapTarget: Pair<Float, Float>?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        // Precision Clicking Trace Dot at exact coordinates (tapX, tapY)
        if (tapTarget != null) {
            val (tapX, tapY) = tapTarget

            // Smooth 2.0-second fade out animation
            val tapAlpha = remember { Animatable(1f) }

            LaunchedEffect(tapTarget) {
                tapAlpha.snapTo(1f)
                tapAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 2000, easing = LinearEasing)
                )
            }

            val dotRadiusPx = with(density) { 6.dp.toPx() }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (tapX - dotRadiusPx).roundToInt(),
                            y = (tapY - dotRadiusPx).roundToInt()
                        )
                    }
                    .size(12.dp)
                    .alpha(tapAlpha.value)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8))
                    .border(1.5.dp, Color.White, CircleShape)
            )
        }
    }
}
