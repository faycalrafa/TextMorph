package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ChatSessionEntity

/**
 * Bottom sheet for switching, renaming, creating, and deleting chat sessions in-chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSessionsBottomSheet(
    sessions: List<ChatSessionEntity>,
    activeSessionId: String?,
    onSelectSession: (String) -> Unit,
    onCreateNewSession: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sessionToRename by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111827),
        contentColor = Color(0xFFF9FAFB)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chat Conversations",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF9FAFB)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            onCreateNewSession()
                            onDismiss()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New chat",
                            tint = Color(0xFF60A5FA)
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF9CA3AF)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Sessions List
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { session ->
                    val isActive = session.id == activeSessionId

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isActive) Color(0xFF1E3A8A).copy(alpha = 0.4f) else Color(0xFF1F2937),
                        border = BorderStroke(
                            1.dp,
                            if (isActive) Color(0xFF3B82F6) else Color(0xFF374151)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectSession(session.id)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                tint = if (isActive) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
                                modifier = Modifier.size(18.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = session.title,
                                fontSize = 13.5.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                color = if (isActive) Color.White else Color(0xFFE5E7EB),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // Edit Rename Action
                            IconButton(
                                onClick = {
                                    sessionToRename = session
                                    renameText = session.title
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename",
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Delete Action
                            IconButton(
                                onClick = { onDeleteSession(session.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }

    // Rename Dialog
    sessionToRename?.let { sess ->
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = {
                Text(
                    text = "Rename Session",
                    color = Color(0xFFF9FAFB),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
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
                        if (renameText.isNotBlank()) {
                            onRenameSession(sess.id, renameText.trim())
                        }
                        sessionToRename = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { sessionToRename = null }) {
                    Text("Cancel", color = Color(0xFF9CA3AF))
                }
            },
            containerColor = Color(0xFF1F2937)
        )
    }
}
