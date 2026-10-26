package com.textmorph.app.service.overlay.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.service.overlay.FloatingButtonState

/**
 * Neat 30x30dp square icon button for the floating companion toolbar.
 */
@Composable
fun CompanionSquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color(0xFFE5E7EB),
    backgroundColor: Color = Color(0x331F2937),
    borderColor: Color = Color(0x44374151)
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.size(30.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Modern, low-footprint chip for prompt transform actions in Charcoal Slate styling.
 * Supports native Material Vector Icons, text labels, and legacy emojis.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompanionActionChip(
    icon: ImageVector? = null,
    label: String? = null,
    emoji: String? = null,
    accent: Boolean = false,
    grayedOut: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val backgroundColor = when {
        grayedOut -> Color(0x1A1F2937)
        accent -> Color(0x442563EB)
        else -> Color(0x331F2937)
    }
    val borderColor = when {
        grayedOut -> Color(0x22374151)
        accent -> Color(0xFF3B82F6)
        else -> Color(0x44374151)
    }
    val textColor = when {
        grayedOut -> Color(0x449CA3AF)
        accent -> Color.White
        else -> Color(0xFFE5E7EB)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = textColor,
                    modifier = Modifier.size(14.dp)
                )
            } else if (!emoji.isNullOrBlank()) {
                Text(
                    text = emoji,
                    fontSize = 12.sp
                )
            }
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (accent && !grayedOut) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Collapsed Edge Pill (36dp, Charcoal Slate styled) for minimized companion overlay.
 */
@Composable
fun FloatingEdgePill(
    state: FloatingButtonState,
    isDragging: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xF2111827),
            shadowElevation = if (isDragging) 12.dp else 6.dp,
            modifier = Modifier
                .size(36.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFF3B82F6),
                            Color(0xFF2563EB)
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    FloatingButtonState.GENERATING -> {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    FloatingButtonState.REVERT_WINDOW -> {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Revert",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    FloatingButtonState.LISTENING -> {
                        Text(
                            text = "👂",
                            fontSize = 17.sp
                        )
                    }
                    FloatingButtonState.IDLE -> {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Expand TextMorph",
                            tint = Color(0xFFE5E7EB),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
