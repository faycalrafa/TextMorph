package com.textmorph.app.ui.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptEditorDialog(
    prompt: PromptEntity? = null,
    onDismiss: () -> Unit,
    onSave: (PromptEntity) -> Unit
) {
    var title by remember { mutableStateOf(prompt?.title ?: "") }
    var systemInstruction by remember { mutableStateOf(prompt?.systemInstruction ?: "") }
    var iconEmoji by remember { mutableStateOf(prompt?.iconEmoji ?: "✨") }
    var category by remember { mutableStateOf(prompt?.category ?: PromptCategory.TEXT_TRANSFORM) }
    var requiresVision by remember { mutableStateOf(prompt?.requiresVision ?: false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (prompt == null) "Create Custom Prompt" else "Edit Prompt",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = iconEmoji,
                        onValueChange = { if (it.length <= 4) iconEmoji = it },
                        label = { Text("Icon") },
                        modifier = Modifier.width(80.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Prompt Title") },
                        placeholder = { Text("e.g. Fix Grammar, Explain Code") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PromptCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = {
                                category = cat
                                if (cat == PromptCategory.VISION_ACTION) requiresVision = true
                            },
                            label = { Text(cat.displayName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = systemInstruction,
                    onValueChange = { systemInstruction = it },
                    label = { Text("System Instruction / AI Guidance") },
                    placeholder = { Text("Describe how the AI should rewrite, analyze, or respond to the text.") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Requires Vision / Screen Context",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Attaches screenshot or camera input when invoked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = requiresVision,
                        onCheckedChange = { requiresVision = it }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons in FlowRow (Contract Rule: wrap in FlowRow to avoid truncation)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                val newOrUpdated = prompt?.copy(
                                    title = title.trim(),
                                    systemInstruction = systemInstruction.trim(),
                                    iconEmoji = iconEmoji.ifBlank { "✨" },
                                    category = category,
                                    requiresVision = requiresVision
                                ) ?: PromptEntity(
                                    id = "custom_" + UUID.randomUUID().toString(),
                                    title = title.trim(),
                                    systemInstruction = systemInstruction.trim(),
                                    iconEmoji = iconEmoji.ifBlank { "✨" },
                                    category = category,
                                    requiresVision = requiresVision,
                                    sortOrder = 99
                                )
                                onSave(newOrUpdated)
                            }
                        },
                        enabled = title.isNotBlank(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Prompt")
                    }
                }
            }
        }
    }
}
