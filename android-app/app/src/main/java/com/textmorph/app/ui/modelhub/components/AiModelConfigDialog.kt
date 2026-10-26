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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.ActionCommandSyntax
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.ScreenPerceptionMode
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
        screenPerceptionMode: ScreenPerceptionMode
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var maxTokensInput by remember { mutableStateOf(settings.maxTokens.toString()) }
    var temperature by remember { mutableFloatStateOf(settings.temperature) }
    var topKInput by remember { mutableStateOf(settings.topK.toString()) }
    var topP by remember { mutableFloatStateOf(settings.topP) }
    var enableThinking by remember { mutableStateOf(settings.enableThinking) }
    var speculativeDecoding by remember { mutableStateOf(settings.speculativeDecoding) }
    var actionCommandSyntax by remember { mutableStateOf(settings.actionCommandSyntax) }
    var screenPerceptionMode by remember { mutableStateOf(settings.screenPerceptionMode) }

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

                // Max Tokens
                OutlinedTextField(
                    value = maxTokensInput,
                    onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 6) maxTokensInput = it },
                    label = { Text("Max Tokens") },
                    placeholder = { Text("1024") },
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

                Spacer(modifier = Modifier.height(14.dp))

                // Screen Perception Mode Selector
                Text(
                    text = "Screen Perception Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "How the autonomous agent perceives the device screen before deciding actions.",
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
                        selected = screenPerceptionMode == ScreenPerceptionMode.PRIVATE_AGENT,
                        onClick = { screenPerceptionMode = ScreenPerceptionMode.PRIVATE_AGENT },
                        label = { Text("Private Agent Way (Default)") }
                    )
                    FilterChip(
                        selected = screenPerceptionMode == ScreenPerceptionMode.ACCESSIBILITY_TREE,
                        onClick = { screenPerceptionMode = ScreenPerceptionMode.ACCESSIBILITY_TREE },
                        label = { Text("Accessibility Tree") }
                    )
                    FilterChip(
                        selected = screenPerceptionMode == ScreenPerceptionMode.SCREEN_CAPTURE,
                        onClick = { screenPerceptionMode = ScreenPerceptionMode.SCREEN_CAPTURE },
                        label = { Text("Screen Capture (Multimodal)") }
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
                            maxTokensInput = "1024"
                            temperature = 0.7f
                            topKInput = "40"
                            topP = 0.9f
                            enableThinking = false
                            speculativeDecoding = false
                            actionCommandSyntax = ActionCommandSyntax.CODE_AS_ACTION
                            screenPerceptionMode = ScreenPerceptionMode.PRIVATE_AGENT
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
                            val tokens = maxTokensInput.toIntOrNull() ?: 1024
                            val k = topKInput.toIntOrNull() ?: 40
                            onSave(
                                tokens,
                                temperature,
                                k,
                                topP,
                                enableThinking,
                                speculativeDecoding,
                                actionCommandSyntax,
                                screenPerceptionMode
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
