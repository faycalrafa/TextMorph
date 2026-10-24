package com.textmorph.app.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ContextColorState
import com.textmorph.app.data.model.ContextUsageMetrics

/**
 * Top bar pill and circular gauge displaying real-time context window token consumption.
 * Dynamic Forest Clay color states:
 * - Muted Sage (#3F4F44): < 60% capacity
 * - Amber / Warm Clay (#A27B5C): 60% – 85% capacity
 * - Subtle Crimson (#E57373): > 85% capacity
 */
@Composable
fun ContextCapacityMeterPill(
    metrics: ContextUsageMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedFraction by animateFloatAsState(
        targetValue = metrics.usageFraction,
        animationSpec = tween(400),
        label = "contextFraction"
    )

    val activeColor = when (metrics.colorState) {
        ContextColorState.SAGE -> Color(0xFF7D9D8B) // Slightly brighter sage for contrast
        ContextColorState.AMBER -> Color(0xFFA27B5C)
        ContextColorState.CRIMSON -> Color(0xFFE57373)
    }

    val animatedColor by animateColorAsState(
        targetValue = activeColor,
        animationSpec = tween(400),
        label = "contextColor"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2C3930))
            .border(1.dp, animatedColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Circular Gauge Ring
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background track
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.size(16.dp),
                color = Color(0xFF3F4F44).copy(alpha = 0.4f),
                strokeWidth = 2.5.dp
            )
            // Active usage fill
            CircularProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.size(16.dp),
                color = animatedColor,
                strokeWidth = 2.5.dp,
                strokeCap = StrokeCap.Round
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Formatted Token Counter (e.g. 1.2k / 2.0k)
        Text(
            text = "${formatTokens(metrics.totalUsedTokens)} / ${formatTokens(metrics.maxContextTokens)}",
            color = Color(0xFFDCD7C9),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Compact circular ring gauge designed for floating overlays or tight title bars.
 */
@Composable
fun ContextCapacityRingIcon(
    metrics: ContextUsageMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedFraction by animateFloatAsState(
        targetValue = metrics.usageFraction,
        animationSpec = tween(400),
        label = "contextFractionCompact"
    )

    val activeColor = when (metrics.colorState) {
        ContextColorState.SAGE -> Color(0xFF7D9D8B)
        ContextColorState.AMBER -> Color(0xFFA27B5C)
        ContextColorState.CRIMSON -> Color(0xFFE57373)
    }

    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(20.dp),
            color = Color(0xFF3F4F44).copy(alpha = 0.4f),
            strokeWidth = 2.5.dp
        )
        CircularProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier.size(20.dp),
            color = activeColor,
            strokeWidth = 2.5.dp,
            strokeCap = StrokeCap.Round
        )
    }
}

private fun formatTokens(tokens: Int): String {
    return if (tokens >= 1000) {
        String.format(java.util.Locale.US, "%.1fk", tokens / 1000f)
    } else {
        tokens.toString()
    }
}
