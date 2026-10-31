package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SlashCommandInfo(
    val command: String,
    val description: String,
    val syntax: String,
    val icon: ImageVector
)

val AVAILABLE_SLASH_COMMANDS = listOf(
    SlashCommandInfo(
        command = "/plan",
        description = "Decompose task into structured sub-steps (supports --workers <1..4>)",
        syntax = "/plan [--workers N] <objective>",
        icon = Icons.AutoMirrored.Filled.ListAlt
    ),
    SlashCommandInfo(
        command = "/goal",
        description = "Autonomous loop running until objective achieved",
        syntax = "/goal <objective>",
        icon = Icons.Default.Flag
    ),
    SlashCommandInfo(
        command = "/deepsearch",
        description = "Iterative deep research with multi-source synthesis (supports --workers <1..4>)",
        syntax = "/deepsearch [--workers N] <topic>",
        icon = Icons.Default.AutoAwesome
    )
)

/**
 * Floating autocomplete popup shown when typing a slash command in the chat input bar.
 */
@Composable
fun SlashCommandAutocompleteCard(
    filterText: String,
    onSelectCommand: (SlashCommandInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val cleanFilter = filterText.trim().lowercase()
    val matches = AVAILABLE_SLASH_COMMANDS.filter {
        it.command.lowercase().startsWith(cleanFilter) ||
            cleanFilter.removePrefix("/").isEmpty()
    }

    if (matches.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1F2937), // Dark slate
        border = BorderStroke(1.dp, Color(0xFF374151)),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            matches.forEach { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCommand(cmd) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF111827),
                        modifier = Modifier.size(28.dp)
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = cmd.icon,
                                contentDescription = null,
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = cmd.command,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF60A5FA)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = cmd.syntax,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                color = Color(0xFF6B7280)
                            )
                        }
                        Text(
                            text = cmd.description,
                            fontSize = 11.5.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }
            }
        }
    }
}
