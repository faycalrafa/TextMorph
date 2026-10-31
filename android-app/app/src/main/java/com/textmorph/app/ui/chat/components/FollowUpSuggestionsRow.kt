package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.model.AgentAction

/**
 * Follow-up suggestion pill cluster styled in the Charcoal Slate design language.
 * Adheres strictly to Primary Rule 5 (Anti-Squish Contract): wraps chips responsively
 * using FlowRow so chips can jump lines freely on narrow mobile viewports.
 *
 * Visually distinguishes actionable tools with tool-specific icons and category badges
 * ([⚡ Web Search], [⚡ Open App], [⚡ Weather]), waiting for the user's tap before running.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FollowUpSuggestionsRow(
    options: List<FollowUpOption>,
    onSelectOption: (FollowUpOption) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Suggestions",
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "Suggested follow-ups",
                color = Color(0xFF9CA3AF),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                if (option.isAction) {
                    ActionFollowUpChip(
                        option = option,
                        onClick = { onSelectOption(option) }
                    )
                } else {
                    ConversationalFollowUpChip(
                        option = option,
                        onClick = { onSelectOption(option) }
                    )
                }
            }
        }
    }
}

/**
 * Backward-compatible overload accepting List<String>.
 */
@JvmName("FollowUpSuggestionsRowStringList")
@Composable
fun FollowUpSuggestionsRow(
    suggestions: List<String>,
    onSelectSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = suggestions.map { ChatToolCallExtractor.parseFollowUpOption(it) }
    FollowUpSuggestionsRow(
        options = options,
        onSelectOption = { onSelectSuggestion(it.actionCommand ?: it.title) },
        modifier = modifier
    )
}

@Composable
private fun ActionFollowUpChip(
    option: FollowUpOption,
    onClick: () -> Unit
) {
    val (icon, iconTint) = resolveToolIconAndTint(option.toolName)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0x223B82F6),
        border = BorderStroke(1.dp, Color(0x7760A5FA)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = option.toolDisplayName,
                tint = iconTint,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = option.title,
                color = Color(0xFFF3F4F6),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
            if (!option.toolDisplayName.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0x331E40AF)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(9.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = option.toolDisplayName,
                            color = Color(0xFF93C5FD),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Trigger tool",
                tint = Color(0xFF60A5FA),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun ConversationalFollowUpChip(
    option: FollowUpOption,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0x331F2937),
        border = BorderStroke(1.dp, Color(0x55374151)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = option.title,
                color = Color(0xFFE5E7EB),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Send",
                tint = Color(0xFF60A5FA),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

private fun resolveToolIconAndTint(toolName: String?): Pair<ImageVector, Color> {
    return when (toolName) {
        AgentAction.ACTION_SEARCH_WEB -> Icons.Default.Search to Color(0xFF38BDF8)
        AgentAction.ACTION_SEARCH_IMAGES -> Icons.Default.Image to Color(0xFF818CF8)
        AgentAction.ACTION_FETCH_PAGE -> Icons.AutoMirrored.Filled.Article to Color(0xFFFBBF24)
        AgentAction.ACTION_OPEN_APP -> Icons.AutoMirrored.Filled.OpenInNew to Color(0xFF34D399)
        else -> Icons.Default.Bolt to Color(0xFFF59E0B)
    }
}
