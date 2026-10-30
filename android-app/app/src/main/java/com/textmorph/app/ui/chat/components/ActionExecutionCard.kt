package com.textmorph.app.ui.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Animated UI action card displayed when LLM triggers autonomous device navigation.
 * Replaces raw JSON outputs with intuitive visual step indicators.
 */
@Composable
fun ActionExecutionCard(
    actionName: String,
    reasoning: String,
    status: String = "running", // "pending", "running", "success", "failed"
    stepNumber: Int? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ActionPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val isSearch = actionName.contains("search_web", ignoreCase = true)
    val isFetch = actionName.contains("fetch_page", ignoreCase = true)
    val isJsTool = actionName.contains("js_tool", ignoreCase = true) || actionName.startsWith("tool_")

    val (statusColor, statusIcon) = when {
        status.equals("success", ignoreCase = true) || status.equals("done", ignoreCase = true) ->
            Pair(Color(0xFF4ADE80), Icons.Default.CheckCircle)
        status.equals("failed", ignoreCase = true) || status.equals("error", ignoreCase = true) ->
            Pair(Color(0xFFF87171), Icons.Default.ErrorOutline)
        isSearch -> Pair(Color(0xFF38BDF8), Icons.Default.Search)
        isFetch -> Pair(Color(0xFF38BDF8), Icons.Default.Language)
        isJsTool -> Pair(Color(0xFFA78BFA), Icons.Default.Code)
        else -> Pair(Color(0xFFFBBF24), Icons.Default.TouchApp)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1F2937), // Elevated Charcoal Slate
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .scale(if (status == "running") pulseScale else 1f)
                    .background(statusColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (status == "running") {
                    CircularProgressIndicator(
                        color = statusColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Icon(
                    imageVector = statusIcon,
                    contentDescription = status,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val headerTitle = when {
                        status == "running" && isSearch -> "Searching Web…"
                        status == "running" && isFetch -> "Reading Webpage…"
                        status == "running" && isJsTool -> "Running Tool…"
                        status == "running" -> "Taking Action…"
                        isSearch -> "Web Search Complete"
                        isFetch -> "Webpage Read Complete"
                        isJsTool -> "Tool Execution Complete"
                        else -> "Action Complete"
                    }
                    Text(
                        text = headerTitle,
                        color = Color(0xFFF9FAFB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (stepNumber != null && stepNumber > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF374151),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = "Step $stepNumber",
                                color = Color(0xFFE5E7EB),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = reasoning.ifEmpty { "Executing $actionName" },
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    maxLines = 2
                )
            }
        }
    }
}
