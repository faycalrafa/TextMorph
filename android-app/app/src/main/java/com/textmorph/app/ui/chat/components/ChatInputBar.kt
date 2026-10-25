package com.textmorph.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
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

/**
 * Modern floating input bar styled in the Charcoal Slate design language:
 * - Midnight Slate surface (#111827) with subtle slate border (#374151).
 * - Discrete 2dp context capacity progress line (Electric Blue / Amber / Crimson).
 * - Companion toolbar-style square attachment & mic buttons.
 * - Electric Blue action send button (#2563EB) and soft crimson stop button (#EF4444).
 */
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
    capacityFraction: Float = 0f,
    onCapacityClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xF2111827), // Charcoal midnight surface
        border = BorderStroke(1.dp, Color(0xFF374151)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Discrete 2dp capacity progress bar
            val progressColor = when {
                capacityFraction >= 0.85f -> Color(0xFFEF4444) // Crimson
                capacityFraction >= 0.60f -> Color(0xFFFBBF24) // Amber
                else -> Color(0xFF3B82F6) // Electric Blue
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFF1F2937))
                    .then(if (onCapacityClick != null) Modifier.clickable { onCapacityClick() } else Modifier)
            ) {
                if (capacityFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(capacityFraction.coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(progressColor)
                    )
                }
            }

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
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1F2937),
                            border = BorderStroke(1.dp, Color(0xFF374151)),
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
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "File",
                                        tint = Color(0xFF60A5FA),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (item.name.length > 14) item.name.take(12) + "…" else item.name,
                                    fontSize = 11.sp,
                                    color = Color(0xFFE5E7EB),
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
                // Attach Media Button (Square Toolbar Style)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x331F2937),
                    border = BorderStroke(1.dp, Color(0x44374151)),
                    onClick = onPickMedia,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach media",
                            tint = if (attachments.isNotEmpty()) Color(0xFF3B82F6) else Color(0xFF9CA3AF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Text Input Box
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChanged,
                    placeholder = {
                        Text(
                            text = if (isListening) "Listening…" else "Ask or instruct TextMorph…",
                            fontSize = 13.5.sp,
                            color = if (isListening) Color(0xFFFBBF24) else Color(0xFF9CA3AF)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF9FAFB),
                        unfocusedTextColor = Color(0xFFE5E7EB),
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedContainerColor = Color(0xFF1F2937),
                        unfocusedContainerColor = Color(0xFF1F2937)
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

                Spacer(modifier = Modifier.width(6.dp))

                // Voice Mic Button
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isListening) Color(0x44EF4444) else Color(0x331F2937),
                    border = BorderStroke(1.dp, if (isListening) Color(0xFFEF4444) else Color(0x44374151)),
                    onClick = onToggleVoice,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice dictation",
                            tint = if (isListening) Color(0xFFEF4444) else Color(0xFF9CA3AF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Send or Stop Button
                if (isGenerating) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEF4444),
                        onClick = onStop,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    val canSend = inputText.isNotBlank() || attachments.isNotEmpty()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (canSend) Color(0xFF2563EB) else Color(0x22374151),
                        border = BorderStroke(1.dp, if (canSend) Color(0xFF3B82F6) else Color(0x33374151)),
                        enabled = canSend,
                        onClick = onSend,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) Color.White else Color(0xFF4B5563),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
