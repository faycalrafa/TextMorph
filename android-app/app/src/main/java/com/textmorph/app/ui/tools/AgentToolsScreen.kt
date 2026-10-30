package com.textmorph.app.ui.tools

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.textmorph.app.agent.tools.js.JsToolEntity
import com.textmorph.app.agent.tools.js.JsToolRepository
import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolRegistry
import com.textmorph.app.service.overlay.TextMorphAccessibilityService
import com.textmorph.app.ui.tools.components.JsToolDocumentationDialog
import com.textmorph.app.ui.tools.components.JsToolEditorDialog
import com.textmorph.app.ui.tools.components.JsToolItemCard
import com.textmorph.app.ui.tools.components.JsToolTesterDialog
import com.textmorph.app.ui.tools.components.ToolItemCard
import kotlinx.coroutines.launch

/**
 * Agent Tools Hub screen accessible from the navigation sidebar.
 * Displays all built-in in-app tools, web browsing tools, interactive chat tools,
 * and custom JavaScript tool plugins with live editor, tester, and documentation.
 */
@Composable
fun AgentToolsScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val agentEngine = remember { AgentEngine(context) }
    val jsRepo = remember { JsToolRepository.getInstance(context) }
    val jsTools by jsRepo.tools.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<AgentToolCategory?>(null) }
    var showJsTabOnly by remember { mutableStateOf(false) }

    // Dialog States
    var showDocDialog by remember { mutableStateOf(false) }
    var showEditorDialog by remember { mutableStateOf(false) }
    var editingTool by remember { mutableStateOf<JsToolEntity?>(null) }
    var testingTool by remember { mutableStateOf<JsToolEntity?>(null) }

    // Quick Command Tester state
    var commandInput by remember { mutableStateOf("open_app(\"Settings\")") }
    var commandRunning by remember { mutableStateOf(false) }
    var commandOutput by remember { mutableStateOf<String?>(null) }

    val allInAppTools = remember { AgentToolRegistry.getInAppTools() }

    val filteredInAppTools = remember(searchQuery, selectedCategory, showJsTabOnly) {
        if (showJsTabOnly) emptyList()
        else {
            allInAppTools.filter { tool ->
                val matchesCategory = selectedCategory == null || tool.category == selectedCategory
                val matchesSearch = searchQuery.isBlank() ||
                        tool.name.contains(searchQuery, ignoreCase = true) ||
                        tool.description.contains(searchQuery, ignoreCase = true) ||
                        tool.codeSignature.contains(searchQuery, ignoreCase = true)
                matchesCategory && matchesSearch
            }
        }
    }

    val filteredJsTools = remember(searchQuery, jsTools) {
        if (searchQuery.isBlank()) jsTools
        else {
            jsTools.filter { tool ->
                tool.name.contains(searchQuery, ignoreCase = true) ||
                        tool.description.contains(searchQuery, ignoreCase = true) ||
                        tool.codeSignature.contains(searchQuery, ignoreCase = true)
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
                        text = "${allInAppTools.size} in-app • ${jsTools.size} JS plugins",
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
            // 2. Code-as-Action Direct Runner
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1F2937),
                    border = BorderStroke(1.dp, Color(0xFF374151)),
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
                            text = "Test commands: search_web(\"query\"), calculate_tip(bill=80), or open_app(\"YouTube\")",
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
                                    fontSize = 12.sp,
                                    color = Color(0xFF38BDF8)
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF4B5563),
                                    focusedTextColor = Color(0xFF38BDF8),
                                    unfocusedTextColor = Color(0xFF38BDF8)
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        commandRunning = true
                                        commandOutput = null
                                        val parsed = AgentActionParser.parseAction(commandInput)
                                        if (parsed == null) {
                                            commandOutput = "Failed: Could not parse command '$commandInput'."
                                            commandRunning = false
                                            return@launch
                                        }

                                        val res: AgentActionResult = agentEngine.execute(parsed)
                                        commandOutput = if (res.success) "Success: ${res.details}" else "Failed: ${res.details}"
                                        commandRunning = false
                                    }
                                },
                                enabled = !commandRunning && commandInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (commandRunning) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Run",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        if (commandOutput != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF0B1020),
                                border = BorderStroke(1.dp, Color(0xFF374151)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = commandOutput.orEmpty(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (commandOutput?.startsWith("Success") == true) Color(0xFF34D399) else Color(0xFFEF4444),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Search and Category Filter Chips
            item {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search tools, parameters, or actions…", fontSize = 12.sp, color = Color(0xFF6B7280)) },
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
                            label = { Text("All (${allInAppTools.size})", fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2563EB),
                                selectedLabelColor = Color.White
                            )
                        )

                        AgentToolCategory.values().forEach { cat ->
                            val count = allInAppTools.count { it.category == cat }
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
                            label = { Text("Custom Scripts (${jsTools.size})", fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFD97706),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            // 4. Custom Scripts Management View
            if (showJsTabOnly) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                editingTool = null
                                showEditorDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New JS Tool", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showDocDialog = true },
                            border = BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Docs & Prompt", color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                items(filteredJsTools, key = { it.id }) { jsTool ->
                    JsToolItemCard(
                        tool = jsTool,
                        onToggleEnabled = { isEnabled ->
                            scope.launch { jsRepo.toggleTool(jsTool.id, isEnabled) }
                        },
                        onTestTool = { testingTool = jsTool },
                        onDeleteTool = {
                            scope.launch { jsRepo.deleteTool(jsTool.id) }
                        }
                    )
                }
            } else {
                // 5. In-App Built-In Tool Cards List
                items(filteredInAppTools, key = { it.id }) { tool ->
                    ToolItemCard(
                        tool = tool,
                        onExecuteAction = { action -> agentEngine.execute(action) }
                    )
                }

                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF111827),
                        border = BorderStroke(1.dp, Color(0xFF374151)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Custom JS Tool Plugins", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("${jsTools.size} installed plugins active", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                                }
                            }
                            Button(
                                onClick = { showJsTabOnly = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
                                border = BorderStroke(1.dp, Color(0xFF4B5563)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Manage", fontSize = 11.5.sp, color = Color(0xFF60A5FA))
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showDocDialog) {
        JsToolDocumentationDialog(onDismiss = { showDocDialog = false })
    }

    if (showEditorDialog) {
        JsToolEditorDialog(
            initialTool = editingTool,
            onDismiss = { showEditorDialog = false },
            onSaveTool = { newTool ->
                scope.launch {
                    jsRepo.saveTool(newTool)
                    showEditorDialog = false
                }
            }
        )
    }

    if (testingTool != null) {
        JsToolTesterDialog(
            tool = testingTool!!,
            onDismiss = { testingTool = null }
        )
    }
}
