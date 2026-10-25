package com.textmorph.app.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact toggle row positioned directly above the floating input bar,
 * inspired by Gemini mobile's [Chat] | [Agent] pills.
 */
@Composable
fun ChatAgentModeToggleRow(
    isAgentMode: Boolean,
    onModeChanged: (isAgent: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val outerShape = RoundedCornerShape(24.dp)
        val pillShape = RoundedCornerShape(20.dp)

        Row(
            modifier = Modifier
                .clip(outerShape)
                .background(Color(0xFF111827).copy(alpha = 0.85f))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chat Pill
            val chatBg by animateColorAsState(
                targetValue = if (!isAgentMode) Color(0xFF1F2937) else Color.Transparent,
                animationSpec = tween(200),
                label = "chatBg"
            )
            val chatTextColor by animateColorAsState(
                targetValue = if (!isAgentMode) Color(0xFFF9FAFB) else Color(0xFF9CA3AF),
                animationSpec = tween(200),
                label = "chatText"
            )
            Box(
                modifier = Modifier
                    .clip(pillShape)
                    .background(chatBg)
                    .clickable { onModeChanged(false) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "💬 Chat",
                    color = chatTextColor,
                    fontSize = 12.sp,
                    fontWeight = if (!isAgentMode) FontWeight.SemiBold else FontWeight.Normal
                )
            }

            // Agent Pill
            val agentBg by animateColorAsState(
                targetValue = if (isAgentMode) Color(0xFF1F2937) else Color.Transparent,
                animationSpec = tween(200),
                label = "agentBg"
            )
            val agentTextColor by animateColorAsState(
                targetValue = if (isAgentMode) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
                animationSpec = tween(200),
                label = "agentText"
            )
            Box(
                modifier = Modifier
                    .clip(pillShape)
                    .background(agentBg)
                    .clickable { onModeChanged(true) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✦ Agent",
                    color = agentTextColor,
                    fontSize = 12.sp,
                    fontWeight = if (isAgentMode) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
