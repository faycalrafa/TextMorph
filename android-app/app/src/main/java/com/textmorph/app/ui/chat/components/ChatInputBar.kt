package com.textmorph.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Detached rounded floating capsule input bar styled after the Gemini mobile app:
 * - Floating capsule container with RoundedCornerShape(28.dp).
 * - Charcoal Slate surface (#1F2937) with subtle slate border (#374151).
 * - Circular + attachment button on the left.
 * - Seamless multiline text field.
 * - Circular voice mic and send / stop button on the right.
 */
@Composable
fun ChatInputBar(
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    activeCommand: String? = null,
    onActiveCommandChanged: (String?) -> Unit = {},
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
    onInputFocus: (() -> Unit)? = null,
    isVisionSupported: Boolean = true,
    modifier: Modifier = Modifier
) {
    val showSlashAutocomplete = activeCommand == null && inputText.startsWith("/") && !inputText.contains(" ")
    val hasImages = attachments.any { it.isImage }

    val handleTextChange: (String) -> Unit = { newText ->
        if (activeCommand == null && newText.startsWith("/")) {
            val matchingCmd = AVAILABLE_SLASH_COMMANDS.firstOrNull { newText.startsWith("${it.command} ") }
            if (matchingCmd != null) {
                onActiveCommandChanged(matchingCmd.command)
                onInputTextChanged(newText.removePrefix("${matchingCmd.command} "))
            } else {
                onInputTextChanged(newText)
            }
        } else {
            onInputTextChanged(newText)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // Slash Command Autocomplete Popup
        AnimatedVisibility(
            visible = showSlashAutocomplete,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.padding(bottom = 8.dp)) {
                SlashCommandAutocompleteCard(
                    filterText = inputText,
                    onSelectCommand = { cmd ->
                        onActiveCommandChanged(cmd.command)
                        onInputTextChanged("")
                    }
                )
            }
        }

        // Vision Capability Warning (if images attached but model is text-only)
        if (hasImages && !isVisionSupported) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF78350F).copy(alpha = 0.8f),
                border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Text(
                    text = "⚠️ Active model does not support vision. Text-only inference will run.",
                    fontSize = 11.5.sp,
                    color = Color(0xFFFDE68A),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // Attachment Preview Strip (floats above capsule when media attached)
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1F2937),
                        border = BorderStroke(1.dp, Color(0xFF374151)),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
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
                                fontSize = 11.5.sp,
                                color = Color(0xFFE5E7EB),
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
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

        // Detached Rounded Capsule Surface
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xF21F2937), // Elevated Charcoal Slate
            border = BorderStroke(1.dp, Color(0xFF374151)),
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Discrete capacity progress bar along top curve of card
                if (capacityFraction > 0f) {
                    val progressColor = when {
                        capacityFraction >= 0.85f -> Color(0xFFEF4444)
                        capacityFraction >= 0.60f -> Color(0xFFFBBF24)
                        else -> Color(0xFF3B82F6)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(Color(0xFF111827))
                            .then(if (onCapacityClick != null) Modifier.clickable { onCapacityClick() } else Modifier)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(capacityFraction.coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(progressColor)
                        )
                    }
                }

                // Inner Capsule Controls Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Gemini-Style '+' Attachment Button
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x22FFFFFF))
                            .clickable(onClick = onPickMedia),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add attachments",
                            tint = if (attachments.isNotEmpty()) Color(0xFF60A5FA) else Color(0xFFE5E7EB),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Active Slash Command Chip (e.g. [/plan ▾ | ✕])
                    if (activeCommand != null) {
                        var showCommandDropdown by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF1E3A8A).copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, Color(0xFF3B82F6)),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                            ) {
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { showCommandDropdown = true }
                                    ) {
                                        Text(
                                            text = activeCommand,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF93C5FD)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Switch command",
                                            tint = Color(0xFF93C5FD),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showCommandDropdown,
                                        onDismissRequest = { showCommandDropdown = false },
                                        modifier = Modifier.background(Color(0xFF1F2937))
                                    ) {
                                        AVAILABLE_SLASH_COMMANDS.forEach { cmd ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(cmd.command, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(cmd.description, color = Color(0xFF9CA3AF), fontSize = 11.sp)
                                                    }
                                                },
                                                onClick = {
                                                    onActiveCommandChanged(cmd.command)
                                                    showCommandDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x33FFFFFF))
                                        .clickable { onActiveCommandChanged(null) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove command",
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Seamless Text Input
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = if (isListening) "Listening…" else if (activeCommand != null) "Describe objective…" else "Ask TextMorph…",
                                fontSize = 15.sp,
                                color = if (isListening) Color(0xFFFBBF24) else Color(0xFF9CA3AF)
                            )
                        }

                        BasicTextField(
                            value = inputText,
                            onValueChange = handleTextChange,
                            textStyle = TextStyle(
                                color = Color(0xFFF9FAFB),
                                fontSize = 15.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(Color(0xFF3B82F6)),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if ((inputText.isNotBlank() || attachments.isNotEmpty() || (activeCommand != null && inputText.isNotBlank())) && !isGenerating) {
                                        onSend()
                                    }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (it.isFocused) onInputFocus?.invoke() }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Backspace &&
                                        inputText.isEmpty() &&
                                        activeCommand != null
                                    ) {
                                        onActiveCommandChanged(null)
                                        true
                                    } else {
                                        false
                                    }
                                }
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Voice Mic Button
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isListening) Color(0x33EF4444) else Color.Transparent)
                            .clickable(onClick = onToggleVoice),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = if (isListening) Color(0xFFEF4444) else Color(0xFF9CA3AF),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Send or Stop Action Pill
                    if (isGenerating) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop generation",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        val canSend = inputText.isNotBlank() || attachments.isNotEmpty() || (activeCommand != null && inputText.isNotBlank())
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (canSend) Color(0xFF2563EB) else Color(0x22374151))
                                .clickable(enabled = canSend, onClick = onSend),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) Color.White else Color(0xFF6B7280),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
