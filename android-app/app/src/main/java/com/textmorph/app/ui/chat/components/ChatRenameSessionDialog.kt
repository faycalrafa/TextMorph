package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Clean dialog for renaming an active chat session.
 */
@Composable
fun ChatRenameSessionDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Rename Session", color = Color(0xFFF9FAFB), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF111827),
                    unfocusedContainerColor = Color(0xFF111827),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color(0xFF3B82F6),
                    unfocusedIndicatorColor = Color(0xFF374151)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val text = title.trim()
                    if (text.isNotBlank()) onConfirm(text)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF9CA3AF))
            }
        },
        containerColor = Color(0xFF1F2937)
    )
}
