package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ContextUsageMetrics

/**
 * Charcoal Slate top app bar for the AI Chat playground:
 * - Hamburger menu drawer toggle
 * - Interactive model quick-switcher pill
 * - Compact context capacity ring gauge
 * - (+) New chat button
 * - Overflow menu for session management (Rename, All Conversations, Clear)
 */
@Composable
fun AiChatTopBar(
    activeModelName: String,
    contextMetrics: ContextUsageMetrics,
    hasMessages: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenModelSwitch: () -> Unit,
    onOpenCapacitySheet: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSessions: () -> Unit,
    onRenameSession: (() -> Unit)? = null,
    onClearChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = Color(0xFF111827), // Elevated midnight
        border = BorderStroke(1.dp, Color(0xFF1F2937)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color(0xFFE5E7EB))
                }

                // Quick Model Switcher Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1F2937))
                        .border(1.dp, Color(0xFF374151), RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenModelSwitch)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activeModelName,
                        color = Color(0xFFF9FAFB),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Context Capacity Gauge Ring Icon
                ContextCapacityRingIcon(
                    metrics = contextMetrics,
                    onClick = onOpenCapacitySheet
                )

                // (+) New Chat Button
                IconButton(onClick = onNewChat) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New chat",
                        tint = Color(0xFF60A5FA)
                    )
                }

                // Overflow Menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = Color(0xFFE5E7EB)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(Color(0xFF1F2937))
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Conversations", color = Color(0xFFF9FAFB), fontSize = 13.5.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                            },
                            onClick = {
                                showMenu = false
                                onOpenSessions()
                            }
                        )

                        if (onRenameSession != null) {
                            DropdownMenuItem(
                                text = { Text("Rename Session", color = Color(0xFFF9FAFB), fontSize = 13.5.sp) },
                                leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    onRenameSession()
                                }
                            )
                        }

                        if (hasMessages) {
                            DropdownMenuItem(
                                text = { Text("Clear Messages", color = Color(0xFFEF4444), fontSize = 13.5.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    onClearChat()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
