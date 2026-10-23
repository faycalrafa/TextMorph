package com.textmorph.app.ui.commands.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.SlashCommandEntity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlashCommandEditorDialog(
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
