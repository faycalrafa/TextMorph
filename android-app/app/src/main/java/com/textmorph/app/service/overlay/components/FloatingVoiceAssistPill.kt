package com.textmorph.app.service.overlay.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * Minimalist Charcoal Slate floating overlay pill displayed over any screen
 * during an active Screen Assist voice session.
 */
@Composable
fun FloatingVoiceAssistPill(
    state: VoiceChatState,
    silenceRemainingSeconds: Int,
    ttsSpeed: Float,
    transcribedText: String,
    spokenText: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "VoicePillPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "MicOrVolumePulse"
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xF2111827), // Charcoal Slate
        shadowElevation = 8.dp,
        modifier = modifier
            .widthIn(min = 180.dp, max = 320.dp)
            .border(1.5.dp, Color(0x663B82F6), RoundedCornerShape(24.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Left Status Icon
            Box(
                modifier = Modifier.size(28.dp),
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
                    VoiceChatState.GREETING, VoiceChatState.SPEAKING -> {
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

            // Center Content Label & Preview
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                val title = when (state) {
                    VoiceChatState.GREETING -> "Greeting…"
                    VoiceChatState.LISTENING -> "Listening (${silenceRemainingSeconds}s)…"
                    VoiceChatState.THINKING -> "Solving…"
                    VoiceChatState.SPEAKING -> "Speaking (${ttsSpeed}x)…"
                    VoiceChatState.IDLE -> "Ready"
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

                val subtitle = when {
                    state == VoiceChatState.LISTENING && transcribedText.isNotBlank() -> transcribedText
                    state == VoiceChatState.SPEAKING && spokenText.isNotBlank() -> spokenText
                    state == VoiceChatState.THINKING && transcribedText.isNotBlank() -> transcribedText
                    else -> null
                }

                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right Stop Button
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
                    contentDescription = "Stop voice session",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
