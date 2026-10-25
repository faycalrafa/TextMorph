package com.textmorph.app.service.overlay.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ContextUsageMetrics
import com.textmorph.app.ui.chat.components.ContextCapacityRingIcon
import com.textmorph.app.util.AppLogger

/**
 * Top control dock for the floating chat companion overlay:
 * - Brand badge & Agent Mode switcher
 * - Context capacity meter gauge
 * - Full-screen expander, ribbon toggle, and dismiss button
 */
@Composable
fun FloatingChatBubbleHeader(
    context: Context,
    isAgentMode: Boolean,
    contextMetrics: ContextUsageMetrics,
    onToggleAgentMode: () -> Unit,
    onContextClick: () -> Unit,
    onOpenFullChat: () -> Unit,
    onSwitchToToolbar: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1F2937)) // Elevated Slate
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2563EB)),
                contentAlignment = Alignment.Center
            ) {
                Text("✦", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "TextMorph",
                color = Color(0xFFF9FAFB),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isAgentMode) Color(0x333B82F6) else Color(0x22374151),
                border = BorderStroke(1.dp, if (isAgentMode) Color(0xFF3B82F6) else Color(0x444B5563)),
                onClick = onToggleAgentMode
            ) {
                Text(
                    text = if (isAgentMode) "✦ Agent" else "💬 Chat",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isAgentMode) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ContextCapacityRingIcon(
                metrics = contextMetrics,
                onClick = onContextClick
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onOpenFullChat,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Open Full Chat",
                    tint = Color(0xFFE5E7EB),
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onSwitchToToolbar,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ViewStream,
                    contentDescription = "Toolbar",
                    tint = Color(0xFFE5E7EB),
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
