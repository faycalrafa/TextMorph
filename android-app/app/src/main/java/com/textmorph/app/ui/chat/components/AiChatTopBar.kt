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
import androidx.compose.material3.HorizontalDivider
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

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator

/**
 * Charcoal Slate top app bar for the AI Chat playground:
 * - Hamburger menu drawer toggle
 * - Interactive model quick-switcher pill with circular benchmark score badge
 * - Local engine Start / Stop toggle
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
    isLocalEngine: Boolean = false,
    isLocalEngineLoaded: Boolean = false,
    isLocalEngineLoading: Boolean = false,
    onToggleEngine: () -> Unit = {},
    activeModelScore: Int? = null,
    onNavigateToBenchmark: () -> Unit = {},
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

                Spacer(modifier = Modifier.width(4.dp))

                // Quick Model Switcher Pill
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1F2937))
                        .border(1.dp, Color(0xFF374151), RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenModelSwitch)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activeModelName,
                        color = Color(0xFFF9FAFB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    // Small Circular Score Badge (takes space from model name)
                    val badgeColor = when {
                        activeModelScore == null -> Color(0xFF4B5563)
                        activeModelScore >= 85 -> Color(0xFF10B981)
                        activeModelScore >= 65 -> Color(0xFF3B82F6)
                        activeModelScore >= 45 -> Color(0xFFF59E0B)
                        else -> Color(0xFFEF4444)
                    }

                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(badgeColor.copy(alpha = if (activeModelScore != null) 0.2f else 0.15f))
                            .border(1.dp, badgeColor, CircleShape)
                            .clickable(onClick = onNavigateToBenchmark),
                        contentAlignment = Alignment.Center
                    ) {
                        if (activeModelScore != null) {
                            Text(
                                text = "$activeModelScore",
                                color = badgeColor,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = "Benchmark Untested",
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Context Capacity Gauge Ring Icon
                ContextCapacityRingIcon(
                    metrics = contextMetrics,
                    onClick = onOpenCapacitySheet
                )

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
                        if (isLocalEngine) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isLocalEngineLoaded) "Stop Engine (Offload)" else if (isLocalEngineLoading) "Starting Engine..." else "Start Engine",
                                        color = if (isLocalEngineLoaded) Color(0xFFEF4444) else Color(0xFF22C55E),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    if (isLocalEngineLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF3B82F6)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (isLocalEngineLoaded) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = if (isLocalEngineLoaded) Color(0xFFEF4444) else Color(0xFF22C55E),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                enabled = !isLocalEngineLoading,
                                onClick = {
                                    showMenu = false
                                    onToggleEngine()
                                }
                            )

                            HorizontalDivider(
                                color = Color(0xFF374151),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("New Conversation", color = Color(0xFFF9FAFB), fontSize = 13.5.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                            },
                            onClick = {
                                showMenu = false
                                onNewChat()
                            }
                        )

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
