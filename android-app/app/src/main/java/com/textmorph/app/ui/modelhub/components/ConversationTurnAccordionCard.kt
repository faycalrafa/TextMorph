package com.textmorph.app.ui.modelhub.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accordion card rendering an individual conversation turn with color-coded sections:
 * System Instruction, User Request, Naked LLM Output, and Action Diagnosis.
 */
@Composable
fun ConversationTurnAccordion(
    turn: ConversationDumpTurn,
    isLatest: Boolean,
    onCopy: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(isLatest) }
    val timeFormatted = remember(turn.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(turn.timestamp))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Clickable Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isLatest) MaterialTheme.colorScheme.primary else Color(0xFF334155)
                        ) {
                            Text(
                                text = if (isLatest) "Turn #${turn.turnNumber} (Latest)" else "Turn #${turn.turnNumber}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = timeFormatted,
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "👤 ${turn.userPrompt}",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (expanded) 3 else 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (turn.tokensPerSecond != null && turn.tokensPerSecond > 0f) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0F172A)
                        ) {
                            Text(
                                text = String.format("%.1f tk/s", turn.tokensPerSecond),
                                fontSize = 9.sp,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded Body: 4 Color-Coded Sections
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. 🟣 System Instruction Box (Purple/Indigo)
                    if (!turn.systemPrompt.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E1B4B),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "🟣 SYSTEM INSTRUCTION",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFA5B4FC)
                                    )
                                    IconButton(
                                        onClick = { onCopy("System Instruction", turn.systemPrompt) },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFFA5B4FC), modifier = Modifier.size(12.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = turn.systemPrompt,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFC7D2FE),
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    // 2. 🔵 User Request Box (Cyan/Blue)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0C4A6E),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "🔵 USER REQUEST",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF7DD3FC)
                                )
                                IconButton(
                                    onClick = { onCopy("User Request", turn.userPrompt) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF7DD3FC), modifier = Modifier.size(12.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = turn.userPrompt,
                                fontSize = 12.sp,
                                color = Color.White,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    // 3. 🟢 Naked LLM Output Box (Emerald/Green Monospace)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (turn.isActionFailed) Color(0xFF451A03) else Color(0xFF06281E),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (turn.isActionFailed) "⚠️ NAKED LLM OUTPUT (ACTION FAILED)" else "🟢 NAKED LLM OUTPUT (BYTE-FOR-BYTE)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (turn.isActionFailed) Color(0xFFFBBF24) else Color(0xFF4ADE80)
                                )
                                IconButton(
                                    onClick = { onCopy("Naked LLM Output", turn.rawNakedOutput) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = if (turn.isActionFailed) Color(0xFFFBBF24) else Color(0xFF4ADE80),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = turn.rawNakedOutput,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (turn.isActionFailed) Color(0xFFFEF3C7) else Color(0xFFD1FAE5),
                                lineHeight = 15.sp
                            )
                        }
                    }

                    // 4. 🟡 Action Diagnosis / Reasoning Block
                    if (turn.thoughtContent != null || turn.actionPayloadJson != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0F172A),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "🟡 DIAGNOSIS & INTERPRETATION",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFDE047)
                                )
                                if (turn.actionPayloadJson != null) {
                                    Text(
                                        text = "Parsed Action: ${turn.actionPayloadJson} (Status: ${turn.actionStatus ?: "N/A"})",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                }
                                if (turn.thoughtContent != null) {
                                    Text(
                                        text = "Reasoning: ${turn.thoughtContent}",
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
