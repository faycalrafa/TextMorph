package com.textmorph.app.ui.modelhub.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.textmorph.app.agent.prompts.AgentPrompts
import com.textmorph.app.data.model.AppSettings

/**
 * Dedicated dialog for editing Chat Mode and Agent Mode system prompts.
 * Accessible exclusively from the System Prompts inspector card in the Model Hub.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditSystemPromptsDialog(
    settings: AppSettings,
    onSave: (chatPrompt: String, agentPrompt: String) -> Unit,
    onDismiss: () -> Unit
) {
    var defaultSystemPrompt by remember { mutableStateOf(settings.defaultSystemPrompt) }
    var agentSystemPrompt by remember { mutableStateOf(settings.agentSystemPrompt) }

    val defaultChatPrompt = "You are a helpful, concise AI writing assistant. Transform or complete text according to user instructions. Return ONLY the final output without preamble or explanations."
    val defaultAgentPrompt = AgentPrompts.AGENT_SYSTEM_PROMPT_CODE_ACTION

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Edit System Prompts",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Configure underlying directives for conversational chat and autonomous device agent tasks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Chat Mode System Prompt
                Text(
                    text = "💬 Chat Mode System Prompt",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = defaultSystemPrompt,
                    onValueChange = { defaultSystemPrompt = it },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Agent Mode System Prompt
                Text(
                    text = "✦ Agent Mode System Prompt (Tools & Automation)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = agentSystemPrompt,
                    onValueChange = { agentSystemPrompt = it },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons in FlowRow
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            defaultSystemPrompt = defaultChatPrompt
                            agentSystemPrompt = defaultAgentPrompt
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset Defaults")
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            onSave(defaultSystemPrompt.trim(), agentSystemPrompt.trim())
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Prompts")
                    }
                }
            }
        }
    }
}
