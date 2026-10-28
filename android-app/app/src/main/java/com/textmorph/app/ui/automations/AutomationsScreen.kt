package com.textmorph.app.ui.automations

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.AgentEngine
import com.textmorph.app.data.model.AutomationEntity
import com.textmorph.app.data.repository.AutomationRepository
import com.textmorph.app.service.overlay.TextMorphAccessibilityService
import com.textmorph.app.service.overlay.VoiceDispatchResult
import com.textmorph.app.service.overlay.VoiceTriggerDispatcher
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.WakeWordRepository
import com.textmorph.app.ui.automations.components.AiAutomationGeneratorDialog
import com.textmorph.app.ui.automations.components.AutomationEditorDialog
import com.textmorph.app.ui.automations.components.AutomationItemCard
import com.textmorph.app.ui.automations.components.WakeWordOverviewCard
import com.textmorph.app.util.PermissionUtils
import kotlinx.coroutines.launch

/**
 * Dedicated Voice & Automations Screen.
 * Displays all direct voice keys, custom automations, direct test runners,
 * and AI-assisted plain-text/voice script synthesis.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationsScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToWakeWords: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoRepo = remember { AutomationRepository.getInstance(context) }
    val automations by autoRepo.automationsState.collectAsState()
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepo.settingsState.collectAsState()
    val wakeRepo = remember { WakeWordRepository.getInstance(context) }
    val wakeWords by wakeRepo.wakeWordsState.collectAsState()

    val agentEngine = remember { AgentEngine(context) }
    val voiceDispatcher = remember {
        VoiceTriggerDispatcher(
            context = context,
            serviceProvider = { TextMorphAccessibilityService.getInstance() },
            agentEngineProvider = { agentEngine }
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "Built-in", "My Automations"

    var editingAutomation by remember { mutableStateOf<AutomationEntity?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }
    var showAiGeneratorDialog by remember { mutableStateOf(false) }

    var runningAutomationId by remember { mutableStateOf<String?>(null) }

    val filteredAutomations = remember(automations, searchQuery, selectedFilter) {
        automations.filter { item ->
            val matchesFilter = when (selectedFilter) {
                "Built-in" -> item.isBuiltIn
                "My Automations" -> !item.isBuiltIn
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                    item.title.contains(searchQuery, ignoreCase = true) ||
                    item.trigger.contains(searchQuery, ignoreCase = true) ||
                    item.description.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1020))
    ) {
        // Top App Bar
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voice & Automations",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${automations.size} triggers configured • Direct hotkeys & AI scripts",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.5.sp
                    )
                }

                IconButton(onClick = onNavigateToPermissions) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Permissions",
                        tint = Color(0xFF60A5FA)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Offline Wake-Word Overview Card
            item {
                WakeWordOverviewCard(
                    isEnabled = settings.wakeWordDetectionEnabled,
                    activeTriggers = wakeWords.filter { it.isEnabled },
                    onToggleMaster = { isChecked ->
                        scope.launch {
                            settingsRepo.updateSettings { it.copy(wakeWordDetectionEnabled = isChecked) }
                        }
                    },
                    onNavigateToStudio = onNavigateToWakeWords
                )
            }

            // Quick Action Buttons (AI Generator & Create)
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { showAiGeneratorDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("✨ AI Script Generator", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Button(
                        onClick = {
                            editingAutomation = null
                            showEditorDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151)),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color(0xFFE5E7EB), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Trigger", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE5E7EB))
                    }
                }
            }

            // Search Bar & Filter Chips
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search voice triggers or actions…", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF9CA3AF)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("All", "Built-in", "My Automations").forEach { tab ->
                        FilterChip(
                            selected = selectedFilter == tab,
                            onClick = { selectedFilter = tab },
                            label = { Text(tab, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF3B82F6),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF1F2937),
                                labelColor = Color(0xFF9CA3AF)
                            )
                        )
                    }
                }
            }

            // Automation Items
            if (filteredAutomations.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1F2937),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No automations found",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Try creating a custom voice hotkey or use the AI Generator.",
                                color = Color(0xFF9CA3AF),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                items(filteredAutomations, key = { it.id }) { item ->
                    AutomationItemCard(
                        automation = item,
                        isRunning = runningAutomationId == item.id,
                        onTestRun = {
                            if (runningAutomationId == null) {
                                runningAutomationId = item.id
                                scope.launch {
                                    val result = voiceDispatcher.executeAutomation(item)
                                    runningAutomationId = null
                                    when (result) {
                                        is VoiceDispatchResult.Handled -> {
                                            Toast.makeText(context, "${if (result.success) "✓" else "✗"} ${result.details}", Toast.LENGTH_SHORT).show()
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        },
                        onEdit = {
                            editingAutomation = item
                            showEditorDialog = true
                        },
                        onDelete = {
                            scope.launch {
                                autoRepo.deleteAutomation(item.id)
                                Toast.makeText(context, "Deleted: ${item.title}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onToggleEnabled = {
                            scope.launch {
                                autoRepo.toggleAutomationEnabled(item.id)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showEditorDialog) {
        AutomationEditorDialog(
            initialAutomation = editingAutomation,
            onDismiss = { showEditorDialog = false },
            onSave = { entity ->
                scope.launch {
                    autoRepo.saveAutomation(entity)
                    Toast.makeText(context, "Saved: ${entity.title}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showAiGeneratorDialog) {
        AiAutomationGeneratorDialog(
            onDismiss = { showAiGeneratorDialog = false },
            onSave = { entity ->
                scope.launch {
                    autoRepo.saveAutomation(entity)
                    Toast.makeText(context, "Saved: ${entity.title}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
