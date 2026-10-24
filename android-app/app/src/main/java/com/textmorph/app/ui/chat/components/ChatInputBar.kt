package com.textmorph.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatInputBar(
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    isGenerating: Boolean,
    isListening: Boolean = false,
    attachments: List<AttachedMediaItem> = emptyList(),
    onRemoveAttachment: (AttachedMediaItem) -> Unit = {},
    onPickMedia: () -> Unit = {},
    onToggleVoice: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF1E2822), // Forest clay dark surface
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3F4F44)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Attachment Preview Strip
            AnimatedVisibility(
                visible = attachments.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF2C3930),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3F4F44)),
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                if (item.isImage && item.bitmap != null) {
                                    Image(
                                        bitmap = item.bitmap.asImageBitmap(),
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "File",
                                        tint = Color(0xFFA27B5C),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (item.name.length > 14) item.name.take(12) + "…" else item.name,
                                    fontSize = 11.sp,
                                    color = Color(0xFFDCD7C9),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x33FFFFFF))
                                        .clickable { onRemoveAttachment(item) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Input Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach File/Photos Button
                IconButton(
                    onClick = onPickMedia,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach media",
                        tint = if (attachments.isNotEmpty()) Color(0xFFA27B5C) else Color(0xFF9EABA2)
                    )
                }

                // Text Input Box
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChanged,
                    placeholder = {
                        Text(
                            text = if (isListening) "Listening…" else "Ask or instruct TextMorph…",
                            fontSize = 13.sp,
                            color = if (isListening) Color(0xFFFBBF24) else Color(0xFF75857B)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFDCD7C9),
                        unfocusedTextColor = Color(0xFFDCD7C9),
                        focusedBorderColor = Color(0xFFA27B5C),
                        unfocusedBorderColor = Color(0xFF3F4F44),
                        focusedContainerColor = Color(0xFF2C3930),
                        unfocusedContainerColor = Color(0xFF2C3930)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if ((inputText.isNotBlank() || attachments.isNotEmpty()) && !isGenerating) {
                                onSend()
                            }
                        }
                    )
                )

                // Voice Mic Button
                IconButton(
                    onClick = onToggleVoice,
                    modifier = Modifier
                        .size(38.dp)
                        .background(if (isListening) Color(0xFFE53E3E) else Color.Transparent, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice dictation",
                        tint = if (isListening) Color.White else Color(0xFF9EABA2)
                    )
                }

                // Send or Stop Button
                if (isGenerating) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color(0xFFE53E3E), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = Color.White
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = inputText.isNotBlank() || attachments.isNotEmpty(),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank() || attachments.isNotEmpty()) Color(0xFFA27B5C) else Color(0x33A27B5C)
                        )
                    }
                }
            }
        }
    }
}
