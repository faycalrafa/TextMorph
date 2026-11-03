package com.textmorph.app.ui.chat.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.tools.registry.AgentToolRegistry

data class ToolMentionInfo(
    val id: String,
    val alias: String,
    val name: String,
    val description: String,
    val syntax: String,
    val icon: ImageVector,
    val tint: Color = Color(0xFF2DD4BF)
)

fun getAvailableToolMentions(context: Context?): List<ToolMentionInfo> {
    val list = mutableListOf(
        ToolMentionInfo(
            id = "get_weather",
            alias = "@weather",
            name = "Live Weather",
            description = "Checks current weather, temperature, humidity, and wind",
            syntax = "@weather <city or country>",
            icon = Icons.Default.AutoAwesome,
            tint = Color(0xFF38BDF8)
        ),
        ToolMentionInfo(
            id = "search_web",
            alias = "@web",
            name = "Web Search",
            description = "Searches the live web for real-time information",
            syntax = "@web <search query>",
            icon = Icons.Default.Search,
            tint = Color(0xFF38BDF8)
        ),
        ToolMentionInfo(
            id = "search_images",
            alias = "@images",
            name = "Search Images",
            description = "Searches for high-resolution images with citations",
            syntax = "@images <query>",
            icon = Icons.Default.Image,
            tint = Color(0xFFA78BFA)
        ),
        ToolMentionInfo(
            id = "fetch_page",
            alias = "@fetch",
            name = "Fetch Webpage",
            description = "Crawls a webpage, removes clutter, returns clean Markdown",
            syntax = "@fetch <url>",
            icon = Icons.Default.Language,
            tint = Color(0xFF34D399)
        ),
        ToolMentionInfo(
            id = "open_app",
            alias = "@app",
            name = "Open App",
            description = "Launches an installed Android app by name",
            syntax = "@app <app name>",
            icon = Icons.Default.PlayArrow,
            tint = Color(0xFFFBBF24)
        ),
        ToolMentionInfo(
            id = "make_call",
            alias = "@call",
            name = "Make Call",
            description = "Calls a contact name or phone number",
            syntax = "@call <contact or number>",
            icon = Icons.Default.Call,
            tint = Color(0xFF4ADE80)
        ),
        ToolMentionInfo(
            id = "send_sms",
            alias = "@sms",
            name = "Send SMS",
            description = "Sends an SMS message to a contact or phone number",
            syntax = "@sms <contact> <message>",
            icon = Icons.AutoMirrored.Filled.Send,
            tint = Color(0xFF60A5FA)
        ),
        ToolMentionInfo(
            id = "send_email",
            alias = "@email",
            name = "Send Email",
            description = "Prepares and opens email client with recipient and message",
            syntax = "@email <to> <subject>",
            icon = Icons.Default.Email,
            tint = Color(0xFFF472B6)
        )
    )

    // Add dynamically discovered custom JS tools
    val jsTools = AgentToolRegistry.getJsTools(context)
    for (jsTool in jsTools) {
        if (list.none { it.id == jsTool.id }) {
            list.add(
                ToolMentionInfo(
                    id = jsTool.id,
                    alias = "@${jsTool.id}",
                    name = jsTool.name,
                    description = jsTool.description,
                    syntax = "@${jsTool.id} <params>",
                    icon = Icons.Default.Code,
                    tint = Color(0xFF818CF8)
                )
            )
        }
    }

    return list
}

/**
 * Floating autocomplete popup shown when typing "@" in the chat input bar.
 */
@Composable
fun ToolAutocompleteCard(
    filterText: String,
    onSelectTool: (ToolMentionInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allTools = remember(context) { getAvailableToolMentions(context) }
    val cleanFilter = filterText.trim().lowercase()

    val matches = allTools.filter {
        it.alias.lowercase().startsWith(cleanFilter) ||
        it.id.lowercase().startsWith(cleanFilter.removePrefix("@")) ||
        it.name.lowercase().contains(cleanFilter.removePrefix("@")) ||
        cleanFilter == "@"
    }

    if (matches.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1F2937), // Dark slate
        border = BorderStroke(1.dp, Color(0xFF374151)),
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .verticalScroll(rememberScrollState())
        ) {
            matches.forEach { tool ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTool(tool) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF111827),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = tool.icon,
                                contentDescription = null,
                                tint = tool.tint,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tool.alias,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = tool.tint
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tool.name,
                                fontSize = 11.5.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                        Text(
                            text = tool.description,
                            fontSize = 11.sp,
                            color = Color(0xFF6B7280),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
