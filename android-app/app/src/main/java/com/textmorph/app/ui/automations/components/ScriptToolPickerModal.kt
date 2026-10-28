package com.textmorph.app.ui.automations.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class AutomationToolItem(
    val signature: String,
    val category: String,
    val description: String,
    val template: String
)

val AVAILABLE_AUTOMATION_TOOLS = listOf(
    AutomationToolItem(
        signature = "wait(ms=1000)",
        category = "Timing & Synchronization",
        description = "Pause execution for milliseconds (crucial for app loading & UI animations)",
        template = "wait(1000)"
    ),
    AutomationToolItem(
        signature = "open_app(\"AppName\")",
        category = "Apps & System",
        description = "Launch an installed Android application by name (e.g. YouTube, Spotify)",
        template = "open_app(\"YouTube\")"
    ),
    AutomationToolItem(
        signature = "click_text(\"Text\")",
        category = "UI Touch & Screen",
        description = "Find and tap an on-screen element matching visible text or accessibility label",
        template = "click_text(\"Confirm\")"
    ),
    AutomationToolItem(
        signature = "click_at(x, y)",
        category = "UI Touch & Screen",
        description = "Tap exact screen coordinates (e.g. 540, 1200)",
        template = "click_at(540, 1200)"
    ),
    AutomationToolItem(
        signature = "type_text(\"Content\", hint=\"Search\")",
        category = "UI Touch & Screen",
        description = "Type text into the active focused field or field matching hint",
        template = "type_text(\"Text to type\")"
    ),
    AutomationToolItem(
        signature = "press_enter()",
        category = "Keyboard",
        description = "Submit the keyboard action or IME Enter key",
        template = "press_enter()"
    ),
    AutomationToolItem(
        signature = "scroll(\"down\")",
        category = "Gestures",
        description = "Scroll the screen down, up, left, or right",
        template = "scroll(\"down\")"
    ),
    AutomationToolItem(
        signature = "swipe(startX, startY, endX, endY)",
        category = "Gestures",
        description = "Perform a direct touch gesture swipe across coordinates",
        template = "swipe(540, 1800, 540, 600)"
    ),
    AutomationToolItem(
        signature = "press_back()",
        category = "System Navigation",
        description = "Press Android system Back button",
        template = "press_back()"
    ),
    AutomationToolItem(
        signature = "press_home()",
        category = "System Navigation",
        description = "Press Android system Home button",
        template = "press_home()"
    ),
    AutomationToolItem(
        signature = "open_notifications()",
        category = "System Navigation",
        description = "Pull down Android system notification shade",
        template = "open_notifications()"
    ),
    AutomationToolItem(
        signature = "set_volume(50)",
        category = "Device Control",
        description = "Set media volume percentage (0 to 100%)",
        template = "set_volume(50)"
    ),
    AutomationToolItem(
        signature = "set_brightness(75)",
        category = "Device Control",
        description = "Set screen brightness percentage (0 to 100%)",
        template = "set_brightness(75)"
    ),
    AutomationToolItem(
        signature = "set_timer(seconds, \"Label\")",
        category = "Device Control",
        description = "Set an active countdown timer in seconds",
        template = "set_timer(60, \"Timer\")"
    ),
    AutomationToolItem(
        signature = "set_alarm(hour, minute, \"Label\")",
        category = "Device Control",
        description = "Set a system clock alarm (24h format)",
        template = "set_alarm(7, 30, \"Morning\")"
    ),
    AutomationToolItem(
        signature = "make_call(\"Contact or Phone\")",
        category = "Communication",
        description = "Initiate a phone call to contact name or phone number",
        template = "make_call(\"Brother Nouh\")"
    ),
    AutomationToolItem(
        signature = "send_sms(\"Contact\", \"Message\")",
        category = "Communication",
        description = "Send SMS message to contact name or phone number",
        template = "send_sms(\"+123456789\", \"I am on my way!\")"
    ),
    AutomationToolItem(
        signature = "open_url(\"https://...\")",
        category = "Apps & System",
        description = "Open a website URL in the default web browser",
        template = "open_url(\"https://www.google.com\")"
    ),
    AutomationToolItem(
        signature = "read_screen()",
        category = "UI Touch & Screen",
        description = "Read current visible screen accessibility hierarchy",
        template = "read_screen()"
    ),
    AutomationToolItem(
        signature = "done(\"Task finished\")",
        category = "Agent Flow",
        description = "Signal that the automation script has completed",
        template = "done(\"Automation finished\")"
    )
)

@Composable
fun ScriptToolPickerModal(
    onDismiss: () -> Unit,
    onSelectTool: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredTools = remember(searchQuery) {
        if (searchQuery.isBlank()) AVAILABLE_AUTOMATION_TOOLS
        else AVAILABLE_AUTOMATION_TOOLS.filter {
            it.signature.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true) ||
            it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF374151)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Code-as-Action Tools",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Select a tool to insert into your script",
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF9CA3AF))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search tools (e.g. wait, click, open…)", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF1F2937),
                        unfocusedContainerColor = Color(0xFF1F2937)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable Tools List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTools, key = { it.signature }) { tool ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1F2937),
                            border = BorderStroke(1.dp, Color(0xFF374151)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectTool(tool.template)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = tool.signature,
                                            color = Color(0xFF93C5FD),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = tool.category,
                                            color = Color(0xFF9CA3AF),
                                            fontSize = 10.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = tool.description,
                                        color = Color(0xFFD1D5DB),
                                        fontSize = 11.5.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        onSelectTool(tool.template)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Insert", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Insert", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
