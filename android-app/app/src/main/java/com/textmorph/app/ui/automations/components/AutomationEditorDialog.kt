package com.textmorph.app.ui.automations.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.AutomationActionType
import com.textmorph.app.data.model.AutomationEntity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationEditorDialog(
    initialAutomation: AutomationEntity?,
    onDismiss: () -> Unit,
    onSave: (AutomationEntity) -> Unit
) {
    var trigger by remember { mutableStateOf(initialAutomation?.trigger ?: "") }
    var title by remember { mutableStateOf(initialAutomation?.title ?: "") }
    var actionType by remember { mutableStateOf(initialAutomation?.actionType ?: AutomationActionType.PHONE_CALL) }
    var targetNumber by remember { mutableStateOf(initialAutomation?.targetNumber ?: "") }
    var targetApp by remember { mutableStateOf(initialAutomation?.targetApp ?: "") }
    var scriptCode by remember { mutableStateOf(initialAutomation?.scriptCode ?: "") }
    var promptInstruction by remember { mutableStateOf(initialAutomation?.promptInstruction ?: "") }
    var toastMessage by remember { mutableStateOf(initialAutomation?.toastMessage ?: "") }
    var copyToClipboard by remember { mutableStateOf(initialAutomation?.copyToClipboard ?: false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF374151)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (initialAutomation == null) "Create Direct Automation" else "Edit Automation",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Configure voice triggers and instantaneous actions",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Trigger Phrase
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text("Voice Trigger Phrase (e.g. \"call my brother\")") },
                    placeholder = { Text("call my brother") },
                    singleLine = true,
                    colors = editorFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Display Title") },
                    placeholder = { Text("Call Brother Nouh") },
                    singleLine = true,
                    colors = editorFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Action Type",
                    color = Color(0xFFE5E7EB),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Action Type Selector (FlowRow)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AutomationActionType.values().forEach { type ->
                        FilterChip(
                            selected = actionType == type,
                            onClick = { actionType = type },
                            label = { Text(type.displayName, fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2563EB),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF1F2937),
                                labelColor = Color(0xFF9CA3AF)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Contextual Fields based on Action Type
                when (actionType) {
                    AutomationActionType.PHONE_CALL -> {
                        OutlinedTextField(
                            value = targetNumber,
                            onValueChange = { targetNumber = it },
                            label = { Text("Target Phone Number or Contact Name") },
                            placeholder = { Text("+1234567890 or Brother Nouh") },
                            singleLine = true,
                            colors = editorFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    AutomationActionType.OPEN_APP -> {
                        OutlinedTextField(
                            value = targetApp,
                            onValueChange = { targetApp = it },
                            label = { Text("App Name to Launch") },
                            placeholder = { Text("YouTube, WhatsApp, Spotify") },
                            singleLine = true,
                            colors = editorFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    AutomationActionType.SCREEN_ASSIST -> {
                        OutlinedTextField(
                            value = promptInstruction,
                            onValueChange = { promptInstruction = it },
                            label = { Text("Screen Assist Instruction") },
                            placeholder = { Text("Analyze screen and provide immediate solution") },
                            minLines = 2,
                            colors = editorFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    AutomationActionType.DEVICE_CONTROL, AutomationActionType.CODE_SCRIPT -> {
                        OutlinedTextField(
                            value = scriptCode,
                            onValueChange = { scriptCode = it },
                            label = { Text("Code-as-Action Script") },
                            placeholder = { Text("set_volume(50)\nopen_app(\"Spotify\")") },
                            minLines = 3,
                            colors = editorFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    AutomationActionType.SEND_SMS -> {
                        OutlinedTextField(
                            value = targetNumber,
                            onValueChange = { targetNumber = it },
                            label = { Text("Contact or Phone Number") },
                            placeholder = { Text("Mom or +123456789") },
                            singleLine = true,
                            colors = editorFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = promptInstruction,
                            onValueChange = { promptInstruction = it },
                            label = { Text("SMS Message Body") },
                            placeholder = { Text("I am on my way!") },
                            singleLine = true,
                            colors = editorFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    AutomationActionType.CUSTOM_TOAST -> {}
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Custom Toast Message
                OutlinedTextField(
                    value = toastMessage,
                    onValueChange = { toastMessage = it },
                    label = { Text("Toast Message Alert") },
                    placeholder = { Text("called out nouh !") },
                    singleLine = true,
                    colors = editorFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Copy to Clipboard Checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = copyToClipboard,
                        onCheckedChange = { copyToClipboard = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF2563EB),
                            checkmarkColor = Color.White,
                            uncheckedColor = Color(0xFF6B7280)
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Dump result to system clipboard",
                        color = Color(0xFFE5E7EB),
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            if (trigger.isNotBlank()) {
                                onSave(
                                    AutomationEntity(
                                        id = initialAutomation?.id ?: java.util.UUID.randomUUID().toString(),
                                        trigger = trigger.trim(),
                                        title = title.ifBlank { trigger.trim() },
                                        actionType = actionType,
                                        targetNumber = targetNumber.ifBlank { null },
                                        targetApp = targetApp.ifBlank { null },
                                        scriptCode = scriptCode.ifBlank { null },
                                        promptInstruction = promptInstruction.ifBlank { null },
                                        toastMessage = toastMessage.ifBlank { null },
                                        copyToClipboard = copyToClipboard,
                                        isBuiltIn = initialAutomation?.isBuiltIn ?: false,
                                        isEnabled = initialAutomation?.isEnabled ?: true,
                                        sortOrder = initialAutomation?.sortOrder ?: 10
                                    )
                                )
                                onDismiss()
                            }
                        },
                        enabled = trigger.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("Save Automation", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF4B5563)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9CA3AF)),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF3B82F6),
    unfocusedBorderColor = Color(0xFF4B5563),
    focusedLabelColor = Color(0xFF60A5FA),
    unfocusedLabelColor = Color(0xFF9CA3AF),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)
