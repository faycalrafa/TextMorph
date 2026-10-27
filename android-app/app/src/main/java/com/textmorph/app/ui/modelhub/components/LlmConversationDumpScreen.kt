package com.textmorph.app.ui.modelhub.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.LlmInteractionRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated full-screen diagnostic route displaying raw, unfiltered byte-for-byte
 * LLM generation dumps, system instructions, and tool commands across all features.
 * Displays interactions ordered recent-to-oldest with source filters and retention controls.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LlmConversationDumpScreen(
    chatRepository: ChatSessionRepository,
    onNavigateBack: () -> Unit,
    interactionRepository: LlmInteractionRepository = LlmInteractionRepository.getInstance(LocalContext.current),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allInteractions by interactionRepository.interactions.collectAsState()
    val retentionDays by interactionRepository.retentionDays.collectAsState()
    val messages by chatRepository.currentMessages.collectAsState()
    val activeSession = chatRepository.getActiveSession()

    var showSystemInstructions by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf<InteractionTriggerSource?>(null) } // null = All

    BackHandler {
        onNavigateBack()
    }

    val filteredInteractions = remember(allInteractions, selectedFilter) {
        if (selectedFilter == null) allInteractions
        else allInteractions.filter { it.source == selectedFilter }
    }

    val turns = remember(filteredInteractions, messages) {
        if (filteredInteractions.isNotEmpty()) {
            ConversationDumpTurn.fromInteractionRecords(filteredInteractions)
        } else {
            ConversationDumpTurn.extractDumpTurns(
                messages = messages,
                defaultSystemPrompt = AgentPrompts.AGENT_SYSTEM_PROMPT_CODE_ACTION
            )
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipMgr.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF0B1020),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Raw LLM Conversation Dump",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${turns.size} records • Flushed ${if (retentionDays >= LlmInteractionRepository.FOREVER_DAYS) "never" else "daily ($retentionDays d)"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    FilterChip(
                        selected = showSystemInstructions,
                        onClick = { showSystemInstructions = !showSystemInstructions },
                        label = {
                            Text(
                                text = if (showSystemInstructions) "Sys: On" else "Sys: Off",
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (showSystemInstructions) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF111827)
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Diagnosis / Explanation Card with Retention Controls
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Unfiltered Model Generation Stream",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Text(
                            text = "Byte-for-byte LLM inputs and generations across custom prompts, toolbar commands, voice agent missions, chat, and auto-correct. Ordered from most recent to oldest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 16.sp
                        )

                        // Retention Extension Controls
                        Text(
                            text = "Record Retention (Auto-flush oldest):",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8)
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                1 to "1 Day",
                                3 to "3 Days",
                                7 to "7 Days",
                                30 to "30 Days",
                                LlmInteractionRepository.FOREVER_DAYS to "Forever"
                            ).forEach { (days, label) ->
                                FilterChip(
                                    selected = retentionDays == days,
                                    onClick = {
                                        scope.launch {
                                            interactionRepository.setRetentionDays(days)
                                            Toast.makeText(context, "Retention set to $label", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    leadingIcon = if (retentionDays == days) {
                                        { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                    } else null
                                )
                            }
                        }

                        // Action Buttons: Copy All & Clear All
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (turns.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        val fullMarkdown = buildString {
                                            appendLine("# Raw LLM Conversation Dump (${activeSession?.title ?: "All Features"})\n")
                                            turns.forEach { turn ->
                                                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(turn.timestamp))
                                                appendLine("## Turn #${turn.turnNumber} ($timeStr)")
                                                if (showSystemInstructions && !turn.systemPrompt.isNullOrBlank()) {
                                                    appendLine("### System Instruction\n```\n${turn.systemPrompt}\n```\n")
                                                }
                                                appendLine("### User Request\n${turn.userPrompt}\n")
                                                appendLine("### Naked LLM Output\n```\n${turn.rawNakedOutput}\n```\n")
                                                appendLine("---\n")
                                            }
                                        }
                                        copyToClipboard("Conversation Dump", fullMarkdown)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy All Turns", fontSize = 12.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        interactionRepository.clearAll()
                                        activeSession?.id?.let { chatRepository.clearSessionMessages(it) }
                                        Toast.makeText(context, "All records cleared.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear Records", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Source Filter Chips Row
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Filter by Source:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = { Text("All (${allInteractions.size})", fontSize = 11.sp) }
                        )

                        InteractionTriggerSource.entries.forEach { src ->
                            val count = allInteractions.count { it.source == src }
                            FilterChip(
                                selected = selectedFilter == src,
                                onClick = { selectedFilter = if (selectedFilter == src) null else src },
                                label = { Text("${src.displayName} ($count)", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Empty State
            if (turns.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "No Interaction Records Found",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Trigger a prompt from the toolbar, run a voice agent command, or send a message in chat. All executions will be captured here in real-time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Turns List: Latest to Oldest
            itemsIndexed(turns, key = { _, turn -> "${turn.turnNumber}_${turn.timestamp}" }) { index, turn ->
                ConversationTurnAccordion(
                    turn = turn,
                    isLatest = index == 0,
                    showSystemInstruction = showSystemInstructions,
                    onCopy = { label, text -> copyToClipboard(label, text) }
                )
            }
        }
    }
}
