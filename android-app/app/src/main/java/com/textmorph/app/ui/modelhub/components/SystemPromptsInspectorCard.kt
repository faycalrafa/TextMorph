package com.textmorph.app.ui.modelhub.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AppSettings

/**
 * Clean inspector card displayed in the Model Hub exposing the active system prompts
 * for both Chat Mode and Agent Mode.
 */
@Composable
fun SystemPromptsInspectorCard(
    settings: AppSettings,
    onOpenAiConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var selectedPromptTab by remember { mutableStateOf(0) } // 0: Chat Mode, 1: Agent Mode

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "System Prompts (Chat & Agent)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Inspect prompts directing model and agent behavior",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // Pill Tabs: Chat Mode vs Agent Mode
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF111827))
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selectedPromptTab == 0) Color(0xFF1F2937) else Color.Transparent,
                            modifier = Modifier.clickable { selectedPromptTab = 0 }
                        ) {
                            Text(
                                text = "💬 Chat Mode Prompt",
                                fontSize = 12.sp,
                                fontWeight = if (selectedPromptTab == 0) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selectedPromptTab == 0) Color(0xFFF9FAFB) else Color(0xFF9CA3AF),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selectedPromptTab == 1) Color(0xFF1F2937) else Color.Transparent,
                            modifier = Modifier.clickable { selectedPromptTab = 1 }
                        ) {
                            Text(
                                text = "✦ Agent Mode Prompt",
                                fontSize = 12.sp,
                                fontWeight = if (selectedPromptTab == 1) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selectedPromptTab == 1) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Prompt Content Display Box
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF111827),
                        border = BorderStroke(1.dp, Color(0xFF374151)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val promptText = if (selectedPromptTab == 0) {
                            settings.defaultSystemPrompt
                        } else {
                            settings.agentSystemPrompt
                        }

                        Text(
                            text = promptText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFD1D5DB),
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onOpenAiConfig,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit System Prompts", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
