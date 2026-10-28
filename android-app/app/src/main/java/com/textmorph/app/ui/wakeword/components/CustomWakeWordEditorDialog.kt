package com.textmorph.app.ui.wakeword.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.WakeWordEntity
import com.textmorph.app.data.repository.WakeWordRepository

/**
 * Dialog for adding a custom wake-word trigger.
 * Performs real-time phonetic token validation against tokens.txt
 * to prevent native JNI failures and app crashes.
 */
@Composable
fun CustomWakeWordEditorDialog(
    onDismiss: () -> Unit,
    onSave: (WakeWordEntity) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { WakeWordRepository.getInstance(context) }

    var displayName by remember { mutableStateOf("") }
    var aliasTag by remember { mutableStateOf("") }
    var phonemes by remember { mutableStateOf("") }

    val missingTokens = remember(phonemes) {
        if (phonemes.isBlank()) emptyList() else repository.validatePhonemes(phonemes)
    }

    val isValid = displayName.isNotBlank() &&
            aliasTag.isNotBlank() &&
            phonemes.isNotBlank() &&
            missingTokens.isEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF374151)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "New Wake-Word Trigger",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF9CA3AF))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Display Name
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        if (aliasTag.isBlank()) {
                            aliasTag = it.lowercase().replace("\\s+".toRegex(), "_")
                        }
                    },
                    label = { Text("Display Name (e.g. Echo, Genie)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Alias Tag
                OutlinedTextField(
                    value = aliasTag,
                    onValueChange = { aliasTag = it.lowercase().replace("\\s+".toRegex(), "_") },
                    label = { Text("Trigger Tag (e.g. echo, genie)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // CMU Phonemes
                OutlinedTextField(
                    value = phonemes,
                    onValueChange = { phonemes = it.uppercase() },
                    label = { Text("CMU Phonemes (e.g. EH1 K OW0)") },
                    placeholder = { Text("JH IY1 N IY0") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (missingTokens.isEmpty()) Color(0xFF3B82F6) else Color(0xFFEF4444),
                        unfocusedBorderColor = if (missingTokens.isEmpty()) Color(0xFF374151) else Color(0xFFDC2626),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (missingTokens.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Unknown tokens: ${missingTokens.joinToString()}",
                            fontSize = 11.5.sp,
                            color = Color(0xFFFCA5A5)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Phoneme Help Box
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Phoneme Examples:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF93C5FD))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Echo: EH1 K OW0\n• Genie: JH IY1 N IY0\n• Assistant: AH0 S IH1 S T AH0 N T",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF374151)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel", fontSize = 13.sp, color = Color(0xFFE5E7EB))
                    }

                    Button(
                        onClick = {
                            if (isValid) {
                                val cleanTag = if (aliasTag.startsWith("@")) aliasTag else "@$aliasTag"
                                val entity = WakeWordEntity(
                                    id = "custom_${System.currentTimeMillis()}",
                                    displayName = displayName.trim(),
                                    phonemes = phonemes.trim(),
                                    aliasTag = cleanTag,
                                    isBuiltIn = false,
                                    isCalibrated = false,
                                    isEnabled = false,
                                    testPassCount = 0
                                )
                                onSave(entity)
                            }
                        },
                        enabled = isValid,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Trigger", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
