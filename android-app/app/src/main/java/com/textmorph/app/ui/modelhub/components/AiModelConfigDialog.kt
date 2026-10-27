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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.ActionCommandSyntax
import com.textmorph.app.data.model.AppSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiModelConfigDialog(
    settings: AppSettings,
    onSave: (
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        enableThinking: Boolean,
        speculativeDecoding: Boolean,
        actionCommandSyntax: ActionCommandSyntax,
        contextWindowTokens: Int
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var contextWindowInput by remember { mutableStateOf(settings.contextWindowTokens.toString()) }
    var maxTokensInput by remember { mutableStateOf(settings.maxTokens.toString()) }
    var temperature by remember { mutableFloatStateOf(settings.temperature) }
    var topKInput by remember { mutableStateOf(settings.topK.toString()) }
    var topP by remember { mutableFloatStateOf(settings.topP) }
    var enableThinking by remember { mutableStateOf(settings.enableThinking) }
    var speculativeDecoding by remember { mutableStateOf(settings.speculativeDecoding) }
    var actionCommandSyntax by remember { mutableStateOf(settings.actionCommandSyntax) }

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
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "AI Model Configurations",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Context Window Capacity Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Model Context Window vs Generation Limit",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• On-Device SLMs (Gemma 4 E2B): Supports up to 128K context (131,072 tokens total input + output combined).\n• Cloud Engine: Up to 128,000+ tokens.\n• Context Window: Hardware buffer allocated for input prompts + conversation history.\n• Max Output Tokens: Limits how many tokens the AI can generate per response turn.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Context Window Capacity (Hardware Context Limit)
                val currentContextCap = contextWindowInput.toIntOrNull() ?: 131072
                Text(
                    text = "Context Window Capacity (Tokens)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = contextWindowInput,
                    onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 7) contextWindowInput = it },
                    label = { Text("Total Context Window (e.g. 131072 for 128K)") },
                    placeholder = { Text("131072") },
                    supportingText = {
                        Text("Native capacity for Gemma 4 E2B is 131,072 (128K)")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // Quick preset chips for Context Window
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("8192" to "8K", "32768" to "32K", "65536" to "64K", "120000" to "120K", "131072" to "128K (Gemma 4)").forEach { (presetVal, label) ->
                        FilterChip(
                            selected = contextWindowInput == presetVal,
                            onClick = { contextWindowInput = presetVal },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Max Output Tokens
                val tokensNum = maxTokensInput.toIntOrNull() ?: 1024
                val isTokensExceedingContext = tokensNum > currentContextCap

                OutlinedTextField(
                    value = maxTokensInput,
                    onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 6) maxTokensInput = it },
                    label = { Text("Max Output Tokens (Generation Limit)") },
                    placeholder = { Text("1024") },
                    supportingText = {
                        if (isTokensExceedingContext) {
                            Text(
                                "Warning: Exceeds context window ($currentContextCap tokens). Generation will be clamped.",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text("Maximum response generation length per turn (recommended: 512–4096)")
                        }
                    },
                    isError = isTokensExceedingContext,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Temperature
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Temperature: ${String.format("%.2f", temperature)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.0f..2.0f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Top-K & Top-P in a responsive layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = topKInput,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 4) topKInput = it },
                        label = { Text("Top-K") },
                        placeholder = { Text("40") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Top-P: ${String.format("%.2f", topP)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = topP,
                            onValueChange = { topP = it },
                            valueRange = 0.1f..1.0f,
                            steps = 17,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Enable Thinking Toggle (OFF by default)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Thinking (Reasoning)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "When OFF (recommended), hides <think> blocks for clean in-place text insertions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableThinking,
                        onCheckedChange = { enableThinking = it }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Speculative Decoding Toggle (OFF by default)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Speculative Decoding",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Uses lightweight draft tokens to accelerate inference speed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = speculativeDecoding,
                        onCheckedChange = { speculativeDecoding = it }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Command Syntax Selector
                Text(
                    text = "Agent Command Syntax",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Format for autonomous device actions. Code-as-Action saves ~70% token overhead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = actionCommandSyntax == ActionCommandSyntax.CODE_AS_ACTION,
                        onClick = { actionCommandSyntax = ActionCommandSyntax.CODE_AS_ACTION },
                        label = { Text("Code-as-Action (Default)") }
                    )
                    FilterChip(
                        selected = actionCommandSyntax == ActionCommandSyntax.JSON_BASED,
                        onClick = { actionCommandSyntax = ActionCommandSyntax.JSON_BASED },
                        label = { Text("JSON-Based") }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons in FlowRow
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            contextWindowInput = "131072"
                            maxTokensInput = "1024"
                            temperature = 0.7f
                            topKInput = "40"
                            topP = 0.9f
                            enableThinking = false
                            speculativeDecoding = false
                            actionCommandSyntax = ActionCommandSyntax.CODE_AS_ACTION
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset")
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val contextTokens = (contextWindowInput.toIntOrNull() ?: 131072).coerceIn(2048, 131072)
                            val tokens = (maxTokensInput.toIntOrNull() ?: 1024).coerceIn(64, contextTokens)
                            val k = topKInput.toIntOrNull() ?: 40
                            onSave(
                                tokens,
                                temperature,
                                k,
                                topP,
                                enableThinking,
                                speculativeDecoding,
                                actionCommandSyntax,
                                contextTokens
                            )
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
