package com.textmorph.app.ui.commands

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.SlashCommandEntity
import com.textmorph.app.data.repository.SlashCommandRepository
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
                                text = "Inline Slash Commands",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Append or prepend commands anywhere in text (e.g. \"what time do we meet? /fr\"). When you tap the Quick Execute button on the floating bar, TextMorph automatically removes the /cmd and runs its custom instruction on your text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${allCommands.size} commands registered",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        slashCommandRepository.resetToDefaults()
                                        Toast.makeText(context, "Reset to default slash commands.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset Defaults", fontSize = 11.sp)
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
                    placeholder = { Text("Search slash commands (e.g. /fr, /fix)...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // Command Items
            if (filteredCommands.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "No slash commands found." else "No commands match \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredCommands, key = { it.id }) { cmd ->
                    SlashCommandItemCard(
                        command = cmd,
                        onEdit = { editingCommand = cmd },
                        onDelete = {
                            scope.launch {
                                slashCommandRepository.deleteCommand(cmd.id)
                                Toast.makeText(context, "Deleted /${cmd.trigger}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        // Add Command FAB
        FloatingActionButton(
            onClick = { isCreatingNew = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create Command")
        }
    }

    // Command Editor Dialog
    if (isCreatingNew || editingCommand != null) {
        val target = editingCommand ?: SlashCommandEntity(
            trigger = "",
            title = "",
            systemInstruction = ""
        )
        SlashCommandEditorDialog(
            initialCommand = target,
            isNew = isCreatingNew,
            onDismiss = {
                isCreatingNew = false
                editingCommand = null
            },
            onSave = { updated ->
                scope.launch {
                    if (isCreatingNew) {
                        slashCommandRepository.addCommand(updated)
                        Toast.makeText(context, "Created /${updated.cleanTrigger}", Toast.LENGTH_SHORT).show()
                    } else {
                        slashCommandRepository.updateCommand(updated)
                        Toast.makeText(context, "Updated /${updated.cleanTrigger}", Toast.LENGTH_SHORT).show()
                    }
                    isCreatingNew = false
                    editingCommand = null
                }
            }
        )
    }
}

@Composable
private fun SlashCommandItemCard(
    command: SlashCommandEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val actionBadge = when (command.contextAction) {
        ContextAction.NONE -> null
        ContextAction.SCREENSHOT -> "📸 Screenshot"
        ContextAction.CAMERA -> "📷 Camera"
        ContextAction.OCR -> "📄 Screen OCR"
        ContextAction.ACCESSIBILITY_TEXT -> "🔤 Screen Text"
        ContextAction.CLIPBOARD -> "📋 Clipboard"
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 10.dp)
                    ) {
                        Text(
                            text = command.displayTrigger,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = command.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (actionBadge != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = actionBadge,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        if (command.description.isNotBlank()) {
                            Text(
                                text = command.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = command.systemInstruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SlashCommandEditorDialog(
    initialCommand: SlashCommandEntity,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (SlashCommandEntity) -> Unit
) {
    var trigger by remember { mutableStateOf(initialCommand.cleanTrigger) }
    var title by remember { mutableStateOf(initialCommand.title) }
    var description by remember { mutableStateOf(initialCommand.description) }
    var systemInstruction by remember { mutableStateOf(initialCommand.systemInstruction) }
    var contextAction by remember { mutableStateOf(initialCommand.contextAction) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isNew) "Create Slash Command" else "Edit Slash Command",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it.removePrefix("/").replace(Regex("""\s+"""), "") },
                    label = { Text("Command Trigger") },
                    placeholder = { Text("fr") },
                    prefix = { Text("/", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Command Title") },
                    placeholder = { Text("Translate to French") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Short Description (Optional)") },
                    placeholder = { Text("Translates text to French") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = systemInstruction,
                    onValueChange = { systemInstruction = it },
                    label = { Text("System Instruction / Prompt") },
                    placeholder = { Text("Translate the user's text into natural, fluent French...") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Context Action / Input Source",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Attach screenshot vision, camera photo, OCR, screen text, or clipboard context when this command is triggered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ContextAction.entries.forEach { action ->
                        val actionIcon = when (action) {
                            ContextAction.NONE -> "📝"
                            ContextAction.SCREENSHOT -> "📸"
                            ContextAction.CAMERA -> "📷"
                            ContextAction.OCR -> "📄"
                            ContextAction.ACCESSIBILITY_TEXT -> "🔤"
                            ContextAction.CLIPBOARD -> "📋"
                        }
                        FilterChip(
                            selected = contextAction == action,
                            onClick = { contextAction = action },
                            label = { Text("$actionIcon ${action.displayName}") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val clean = trigger.trim().removePrefix("/").lowercase()
                            val isVision = contextAction == ContextAction.SCREENSHOT ||
                                    contextAction == ContextAction.CAMERA ||
                                    contextAction == ContextAction.OCR
                            if (clean.isNotBlank() && title.isNotBlank() && systemInstruction.isNotBlank()) {
                                onSave(
                                    initialCommand.copy(
                                        trigger = clean,
                                        title = title.trim(),
                                        description = description.trim(),
                                        systemInstruction = systemInstruction.trim(),
                                        contextAction = contextAction,
                                        requiresVision = isVision
                                    )
                                )
                            }
                        },
                        enabled = trigger.isNotBlank() && title.isNotBlank() && systemInstruction.isNotBlank()
                    ) {
                        Text("Save Command")
                    }
                }
            }
        }
    }
}
