package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centered empty-state view presenting mode guidance and 2 elegant, vertically stacked
 * example prompt buttons that disappear as soon as conversation begins.
 */
@Composable
fun ChatEmptyStateStarters(
    isAgentMode: Boolean,
    activeModelName: String,
    onSelectStarter: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Mode Hero Icon Pill
            Surface(
                shape = CircleShape,
                color = if (isAgentMode) Color(0xFF1E3A8A).copy(alpha = 0.5f) else Color(0xFF0C4A6E).copy(alpha = 0.5f),
                border = BorderStroke(1.dp, if (isAgentMode) Color(0xFF3B82F6) else Color(0xFF0284C7)),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isAgentMode) Icons.Default.AutoAwesome else Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = if (isAgentMode) Color(0xFF60A5FA) else Color(0xFF38BDF8),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isAgentMode) "Autonomous Agent Workspace" else "AI Conversation Workspace",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isAgentMode) {
                    "Direct the agent to automate multi-step device tasks on $activeModelName"
                } else {
                    "Ask questions, brainstorm ideas, or transform text on $activeModelName"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 2 Elegant Suggestion Buttons (Strictly stacked vertically one on top of the other)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isAgentMode) {
                    StarterSuggestionButton(
                        icon = Icons.Default.PlayArrow,
                        title = "open youtube and search for orange cats",
                        subtitle = "Autonomous app launch & search",
                        onClick = { onSelectStarter("open youtube and search for orange cats") }
                    )
                    StarterSuggestionButton(
                        icon = Icons.Default.BatteryStd,
                        title = "open settings and check battery",
                        subtitle = "Device navigation & status inspection",
                        onClick = { onSelectStarter("open settings and check battery") }
                    )
                } else {
                    StarterSuggestionButton(
                        icon = Icons.Default.Lightbulb,
                        title = "Summarize quantum computing in 3 key points",
                        subtitle = "Concise conceptual breakdown",
                        onClick = { onSelectStarter("Summarize quantum computing in 3 key points") }
                    )
                    StarterSuggestionButton(
                        icon = Icons.Default.Email,
                        title = "Draft a professional follow-up email",
                        subtitle = "Polite corporate inquiry",
                        onClick = { onSelectStarter("Draft a professional follow-up email") }
                    )
                }
            }
        }
    }
}

@Composable
private fun StarterSuggestionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF1F5F9),
                    lineHeight = 17.sp
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
