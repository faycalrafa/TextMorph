package com.textmorph.app.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.AgentEngine
import com.textmorph.app.agent.model.AgentActionResult
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolRegistry
import com.textmorph.app.service.overlay.TextMorphAccessibilityService
import com.textmorph.app.ui.tools.components.JsToolImportCard
import com.textmorph.app.ui.tools.components.ToolItemCard
import kotlinx.coroutines.launch

/**
 * Agent Tools Hub screen accessible from the navigation sidebar.
 * Displays all built-in actions, dynamic Code-as-Action playground, and future JS plugins.
 */
@Composable
fun AgentToolsScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Dedicated AgentEngine instance for tool testing
    val agentEngine = remember { AgentEngine(context) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<AgentToolCategory?>(null) }
    var showJsTabOnly by remember { mutableStateOf(false) }

    // Quick Command Tester state
    var commandInput by remember { mutableStateOf("open_app(\"Settings\")") }
    var commandRunning by remember { mutableStateOf(false) }
    var commandOutput by remember { mutableStateOf<String?>(null) }

    val allTools = remember { AgentToolRegistry.getAllTools() }

    val filteredTools = remember(searchQuery, selectedCategory, showJsTabOnly) {
        if (showJsTabOnly) emptyList()
        else {
            allTools.filter { tool ->
                val matchesCategory = selectedCategory == null || tool.category == selectedCategory
                val matchesSearch = searchQuery.isBlank() ||
                        tool.name.contains(searchQuery, ignoreCase = true) ||
                        tool.description.contains(searchQuery, ignoreCase = true) ||
                        tool.codeSignature.contains(searchQuery, ignoreCase = true)
                matchesCategory && matchesSearch
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1020)) // Dark Charcoal Midnight
    ) {
        // 1. Top Header Bar
        Surface(
            color = Color(0xFF111827),
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open Drawer",
                        tint = Color(0xFFE5E7EB)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Agent Tools Hub",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${allTools.size} built-in actions • Code-as-Action tester",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 2. Code-as-Action Raw Command Runner
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1F2937),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Code-as-Action Direct Runner",
                            color = Color(0xFFF9FAFB),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Test direct commands: e.g. open_app(\"YouTube\"), call(\"Mom\"), or scroll(\"down\")",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.5.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = commandInput,
                                onValueChange = { commandInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF4B5563),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    if (commandRunning) return@Button
                                    commandRunning = true
                                    commandOutput = null
                                    scope.launch {
                                        val action = AgentActionParser.parse(commandInput)
                                        if (action != null) {
                                            val res = agentEngine.execute(action)
                                            commandOutput = "${if (res.success) "SUCCESS" else "FAILED"}: ${res.details}"
                                        } else {
                                            commandOutput = "Invalid syntax. Format: action_name(\"value\")"
                                        }
                                        commandRunning = false
                                    }
                                },
                                enabled = !commandRunning && commandInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(52.dp)
                            ) {
                                if (commandRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Run")
                                }
                            }
                        }

                        commandOutput?.let { out ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (out.startsWith("SUCCESS")) Color(0x2210B981) else Color(0x22DC2626),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = out,
                                    color = if (out.startsWith("SUCCESS")) Color(0xFF6EE7B7) else Color(0xFFFCA5A5),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Category Filter Chips & Search
            item {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter actions or signatures…", fontSize = 12.5.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color(0xFF9CA3AF), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF374151),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategory == null && !showJsTabOnly,
                            onClick = {
                                selectedCategory = null
                                showJsTabOnly = false
                            },
                            label = { Text("All (${allTools.size})", fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2563EB),
                                selectedLabelColor = Color.White
                            )
                        )

                        AgentToolCategory.values().forEach { cat ->
                            val count = allTools.count { it.category == cat }
                            FilterChip(
                                selected = selectedCategory == cat && !showJsTabOnly,
                                onClick = {
                                    selectedCategory = cat
                                    showJsTabOnly = false
                                },
                                label = { Text("${cat.label} ($count)", fontSize = 11.5.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF2563EB),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }

                        FilterChip(
                            selected = showJsTabOnly,
                            onClick = { showJsTabOnly = true },
                            label = { Text("Custom Scripts (v3.1)", fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFD97706),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            // 4. Tool Cards List
            if (!showJsTabOnly) {
                items(filteredTools, key = { it.id }) { tool ->
                    ToolItemCard(
                        tool = tool,
                        onExecuteAction = { action -> agentEngine.execute(action) }
                    )
                }
            }

            // 5. JavaScript Tool Import Preview Card
            item {
                JsToolImportCard()
            }
        }
    }
}
