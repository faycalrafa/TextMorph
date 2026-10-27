package com.textmorph.app.ui.modelhub.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.benchmark.VisualBenchmarkProgressState

/**
 * Real-time progress and visual screen canvas inspection card during active visual benchmarking.
 * Renders the canvas image, 2-second LLM click location dot, and live streaming output.
 */
@Composable
fun VisualBenchmarkRunningCard(
    state: VisualBenchmarkProgressState,
    onCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Round ${state.currentRound}/${state.totalRounds} • Target: Button ${state.currentTargetNumber}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Step ${state.overallStepIndex}/${state.overallTotalSteps} total",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            val progressFraction = if (state.overallTotalSteps > 0) {
                state.overallStepIndex.toFloat() / state.overallTotalSteps.toFloat()
            } else 0f

            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0.05f, 1.0f) },
                modifier = Modifier.fillMaxWidth()
            )

            // Canvas Thumbnail Preview
            if (state.currentCanvasPreviewBytes != null) {
                val previewBitmap = remember(state.currentCanvasPreviewBytes) {
                    runCatching {
                        BitmapFactory.decodeByteArray(
                            state.currentCanvasPreviewBytes,
                            0,
                            state.currentCanvasPreviewBytes.size
                        )
                    }.getOrNull()
                }

                if (previewBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Test Screen Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.lastClickIsHit != null) {
                            val hitColor = if (state.lastClickIsHit == true) Color(0xFF16A34A) else Color(0xFFDC2626)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = hitColor.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = if (state.lastClickIsHit == true) "HIT ✓ (Target Button ${state.currentTargetNumber})" else "MISSED ✗ (Offset ±${state.lastDistanceErrorPx?.toInt() ?: 0}px)",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Live Output Console
            if (state.liveStreamingOutput.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.liveStreamingOutput,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(10.dp),
                        maxLines = 5
                    )
                }
            }
        }
    }
}
