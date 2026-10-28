package com.textmorph.app.ui.automations.components

import android.app.Activity
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.agent.planner.AiAutomationScriptGenerator
import com.textmorph.app.data.model.AutomationEntity
import com.textmorph.app.ui.chat.components.VoiceInputHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAutomationGeneratorDialog(
    onDismiss: () -> Unit,
    onSave: (AutomationEntity) -> Unit,
    onOpenInEditor: ((AutomationEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceHelper = remember { VoiceInputHelper(context) }
    val isListening by voiceHelper.isListening.collectAsState()

    var promptInput by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generatedDraft by remember { mutableStateOf<AutomationEntity?>(null) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI Script & Trigger Generator",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Explain in plain English or dictate with your voice what the automation should do. AI will write the script and configure the trigger.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Input Bar with Mic Dictation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        label = { Text("What should this automation do?") },
                        placeholder = { Text("e.g. When I say call my brother, call 0612345678 and toast called out nouh !") },
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF4B5563),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (isListening) {
                                voiceHelper.stopListening()
                            } else {
                                voiceHelper.startListening { spoken ->
                                    promptInput = if (promptInput.isBlank()) spoken else "$promptInput $spoken"
                                }
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .background(if (isListening) Color(0xFFDC2626) else Color(0xFF1F2937), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Voice Dictate",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (promptInput.isNotBlank() && !isGenerating) {
                            isGenerating = true
                            scope.launch {
                                val result = AiAutomationScriptGenerator.generateAutomation(context, promptInput)
                                isGenerating = false
                                if (onOpenInEditor != null) {
                                    onOpenInEditor(result)
                                    onDismiss()
                                } else {
                                    generatedDraft = result
                                }
                            }
                        }
                    },
                    enabled = promptInput.isNotBlank() && !isGenerating,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI Writing Automation Script…", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generate Automation", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Draft Preview Card
                generatedDraft?.let { draft ->
                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1F2937),
                        border = BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Generated Draft Preview",
                                    color = Color(0xFF93C5FD),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = draft.actionType.displayName,
                                    color = Color(0xFF60A5FA),
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Trigger: \"${draft.trigger}\"",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )

                            if (!draft.targetNumber.isNullOrBlank()) {
                                Text(
                                    text = "Target: ${draft.targetNumber}",
                                    color = Color(0xFFE5E7EB),
                                    fontSize = 12.sp
                                )
                            }

                            if (!draft.targetApp.isNullOrBlank()) {
                                Text(
                                    text = "App: ${draft.targetApp}",
                                    color = Color(0xFFE5E7EB),
                                    fontSize = 12.sp
                                )
                            }

                            if (!draft.toastMessage.isNullOrBlank()) {
                                Text(
                                    text = "Toast Alert: \"${draft.toastMessage}\"",
                                    color = Color(0xFF6EE7B7),
                                    fontSize = 12.sp
                                )
                            }

                            if (!draft.scriptCode.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF111827),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = draft.scriptCode,
                                        color = Color(0xFFE5E7EB),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                onOpenInEditor?.invoke(draft) ?: onSave(draft)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Text("Review in Form ➡️", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        Button(
                            onClick = {
                                onSave(draft)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Text("Quick Save ✓", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF4B5563)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9CA3AF)),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Text("Discard")
                        }
                    }
                }
            }
        }
    }
}
