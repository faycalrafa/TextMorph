package com.textmorph.app.ui.commands

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.SlashCommandEntity
import com.textmorph.app.data.repository.SlashCommandRepository
import com.textmorph.app.ui.commands.components.SlashCommandEditorDialog
import com.textmorph.app.ui.commands.components.SlashCommandItemCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomCommandsContent(
    slashCommandRepository: SlashCommandRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allCommands by slashCommandRepository.commandsState.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var editingCommand by remember { mutableStateOf<SlashCommandEntity?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    val filteredCommands = remember(allCommands, searchQuery) {
        if (searchQuery.isBlank()) allCommands
        else {
            val q = searchQuery.trim().lowercase().removePrefix("/")
            allCommands.filter { cmd ->
                cmd.cleanTrigger.contains(q) ||
                        cmd.title.contains(searchQuery, ignoreCase = true) ||
                        cmd.systemInstruction.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Header Info Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Slash Commands Architecture",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Trigger on-device AI actions in any app by typing /command in your text field (e.g. /fix, /summarize, /explain, /french) or tapping the ⚡ Quick Execute button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    editingCommand = SlashCommandEntity(
                                        trigger = "custom",
                                        title = "",
                                        systemInstruction = "",
                                        contextAction = ContextAction.NONE,
                                        isBuiltIn = false
                                    )
                                    isCreatingNew = true
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New Slash Command")
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        slashCommandRepository.resetToDefaults()
                                        Toast.makeText(context, "Reset to default slash commands", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore Defaults")
                            }
                        }
                    }
                }
            }

            // Search Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search slash commands (e.g. /fix, translate)...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Commands List
            items(filteredCommands, key = { it.id }) { cmd ->
                SlashCommandItemCard(
                    command = cmd,
                    onEdit = {
                        editingCommand = cmd
                        isCreatingNew = false
                    },
                    onDelete = {
                        scope.launch {
                            slashCommandRepository.deleteCommand(cmd.id)
                            Toast.makeText(context, "Deleted /${cmd.cleanTrigger}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        // Floating Action Button
        FloatingActionButton(
            onClick = {
                editingCommand = SlashCommandEntity(
                    trigger = "custom",
                    title = "",
                    systemInstruction = "",
                    contextAction = ContextAction.NONE,
                    isBuiltIn = false
                )
                isCreatingNew = true
            },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Command")
        }
    }

    // Editor Dialog
    if (editingCommand != null) {
        SlashCommandEditorDialog(
            initialCommand = editingCommand!!,
            isNew = isCreatingNew,
            onDismiss = {
                editingCommand = null
                isCreatingNew = false
            },
            onSave = { updated ->
                scope.launch {
                    if (isCreatingNew) {
                        slashCommandRepository.addCommand(updated)
                    } else {
                        slashCommandRepository.updateCommand(updated)
                    }
                    Toast.makeText(context, "Saved /${updated.cleanTrigger}", Toast.LENGTH_SHORT).show()
                    editingCommand = null
                    isCreatingNew = false
                }
            }
        )
    }
}
