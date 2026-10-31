package com.textmorph.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.voice.VoiceChatState

/**
 * Control bar in AiChatScreen active during hands-free voice mode:
 * Provides state feedback, countdown timer, live transcript preview,
 * interactive TTS speed control, and a prominent manual Stop button.
 */
@Composable
fun VoiceHandsFreeBar(
    state: VoiceChatState,
    silenceRemainingSeconds: Int,
    ttsSpeed: Float,
    transcribedText: String,
    spokenText: String,
    onSetSpeed: (Float) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSpeedDropdown by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "HandsFreePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HandsFreeScale"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF2111827),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // State Icon
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    VoiceChatState.THINKING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color(0xFFFBBF24),
                            strokeWidth = 2.5.dp
                        )
                    }
                    VoiceChatState.SPEAKING, VoiceChatState.GREETING -> {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Speaking",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier
                                .size(22.dp)
                                .scale(pulseScale)
                        )
                    }
                    VoiceChatState.LISTENING -> {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Listening",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier
                                .size(22.dp)
                                .scale(pulseScale)
                        )
                    }
                    VoiceChatState.IDLE -> {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Idle",
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Info Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                val title = when (state) {
                    VoiceChatState.GREETING -> "Greeting…"
                    VoiceChatState.LISTENING -> "Listening (${silenceRemainingSeconds}s)…"
                    VoiceChatState.THINKING -> "Thinking…"
                    VoiceChatState.SPEAKING -> "Speaking aloud…"
                    VoiceChatState.IDLE -> "Hands-Free Voice"
                }

                Text(
                    text = title,
                    color = when (state) {
                        VoiceChatState.THINKING -> Color(0xFFFBBF24)
                        VoiceChatState.SPEAKING -> Color(0xFF38BDF8)
                        VoiceChatState.LISTENING -> Color(0xFF60A5FA)
                        else -> Color(0xFFF9FAFB)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )

                val preview = when {
                    state == VoiceChatState.LISTENING && transcribedText.isNotBlank() -> transcribedText
                    state == VoiceChatState.SPEAKING && spokenText.isNotBlank() -> spokenText
                    state == VoiceChatState.THINKING && transcribedText.isNotBlank() -> transcribedText
                    else -> "Speak hands-free, stops automatically after 15s"
                }

                Text(
                    text = preview,
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Speed Selector Pill
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1F2937),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6)),
                    modifier = Modifier.clickable { showSpeedDropdown = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${ttsSpeed}x",
                            color = Color(0xFF60A5FA),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Change speed",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = showSpeedDropdown,
                    onDismissRequest = { showSpeedDropdown = false },
                    modifier = Modifier.background(Color(0xFF1F2937))
                ) {
                    val speedOptions = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                    speedOptions.forEach { sp ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (sp == 1.5f) "1.5x (Default)" else "${sp}x",
                                    color = if (kotlin.math.abs(ttsSpeed - sp) < 0.05f) Color(0xFF60A5FA) else Color(0xFFF9FAFB),
                                    fontWeight = if (kotlin.math.abs(ttsSpeed - sp) < 0.05f) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                onSetSpeed(sp)
                                showSpeedDropdown = false
                            }
                        )
                    }
                }
            }

            // Stop Button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop hands-free voice",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
