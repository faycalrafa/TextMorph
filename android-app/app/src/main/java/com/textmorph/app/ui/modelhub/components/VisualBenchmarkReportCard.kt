package com.textmorph.app.ui.modelhub.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.benchmark.VisualBenchmarkProgressState
import com.textmorph.app.agent.benchmark.VisualBenchmarkReport
import com.textmorph.app.agent.benchmark.VisualBenchmarkRoundReport

/**
 * Visual benchmark results card displaying accuracy, spatial precision, latency,
 * per-round trial breakdown, and markdown export.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisualBenchmarkResultsView(
    report: VisualBenchmarkReport,
    context: Context,
    onRunAgain: () -> Unit
) {
    val bannerColor = when {
        report.accuracyPercent >= 80f -> Color(0xFF14532D) // Green
        report.accuracyPercent >= 50f -> Color(0xFF78350F) // Amber
        else -> Color(0xFF7F1D1D) // Red
    }

    // 1. Verdict Banner
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bannerColor)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Visual Score: ${report.successfulHits}/${report.totalSteps} Hits (${report.accuracyPercent.toInt()}%)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = report.verdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 16.sp
                )
            }
        }
    }

    // 2. Metric Tiles Grid
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VisualMetricTile("Accuracy", "${report.accuracyPercent.toInt()}%", "${report.successfulHits}/${report.totalSteps} hits")
        VisualMetricTile("Perfect Rounds", "${report.perfectTrials}/${report.totalTrials}", "All 6 hit in order")
        VisualMetricTile("Avg Latency", "${report.avgLatencyMs} ms", "Per button press")
        VisualMetricTile("Avg TTFT", "${report.avgTtftMs} ms", "First visual token")
        VisualMetricTile("Throughput", String.format(java.util.Locale.US, "%.1f tk/s", report.avgTps), "Inference speed")
        VisualMetricTile(
            "Spatial Precision",
            if (report.avgDistanceErrorPx != null) String.format(java.util.Locale.US, "±%.1f px", report.avgDistanceErrorPx) else "N/A",
            "Center offset"
        )
        VisualMetricTile("RAM Impact", "${if (report.ramDeltaMb >= 0) "+" else ""}${report.ramDeltaMb} MB", "Delta during test")
        VisualMetricTile(
            "Device Temp",
            if (report.tempDeltaC != null) "${if (report.tempDeltaC >= 0) "+" else ""}${report.tempDeltaC}°C" else "N/A",
            "Thermal delta"
        )
    }

    // 3. Per-Round Breakdown
    Text(
        text = "Trial Breakdown (3 Rounds × 6 Buttons)",
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = Color(0xFF94A3B8)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        report.roundReports.forEach { roundReport ->
            VisualRoundBreakdownCard(roundReport)
        }
    }

    // 4. Action Row
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onRunAgain,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Run Visual Benchmark Again", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = {
                val markdown = formatVisualReportMarkdown(report)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("TextMorph Visual Benchmark", markdown)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Visual report copied to clipboard!", Toast.LENGTH_SHORT).show()
            },
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Copy Markdown Report", fontSize = 12.sp)
        }
    }
}

@Composable
private fun VisualMetricTile(
    title: String,
    value: String,
    subtitle: String
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier.width(160.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
            Text(subtitle, fontSize = 10.sp, color = Color(0xFF64748B))
        }
    }
}

@Composable
private fun VisualRoundBreakdownCard(roundReport: VisualBenchmarkRoundReport) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier = Modifier.clickable { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = if (roundReport.isPerfect) Color(0xFF22C55E) else Color(0xFFF59E0B)
                    Surface(shape = RoundedCornerShape(4.dp), color = statusColor.copy(alpha = 0.2f)) {
                        Text(
                            text = "Round ${roundReport.roundIndex}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = statusColor
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${roundReport.hits}/${roundReport.totalSteps} Hits • ${roundReport.durationMs}ms",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    roundReport.steps.forEach { step ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (step.isHit) Icons.Default.CheckCircle else Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (step.isHit) Color(0xFF22C55E) else Color(0xFFEF4444),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Button ${step.targetNumber}: ${step.rawOutput.take(28)}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                            Text(
                                text = "${step.durationMs}ms${if (step.distanceErrorPx != null) " (±${step.distanceErrorPx.toInt()}px)" else ""}",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatVisualReportMarkdown(report: VisualBenchmarkReport): String {
    val sb = StringBuilder()
    sb.appendLine("# TextMorph Visual Agent Capability Benchmark Report")
    sb.appendLine("- **Model**: ${report.modelName}")
    sb.appendLine("- **Backend**: ${report.backend}")
    sb.appendLine("- **Visual Accuracy**: ${report.successfulHits}/${report.totalSteps} (${report.accuracyPercent.toInt()}%)")
    sb.appendLine("- **Perfect Rounds**: ${report.perfectTrials}/${report.totalTrials}")
    sb.appendLine("- **Average Step Latency**: ${report.avgLatencyMs} ms")
    sb.appendLine("- **Average TTFT**: ${report.avgTtftMs} ms")
    sb.appendLine("- **Throughput**: ${String.format(java.util.Locale.US, "%.1f", report.avgTps)} tk/s")
    if (report.avgDistanceErrorPx != null) {
        sb.appendLine("- **Spatial Precision Error**: ±${String.format(java.util.Locale.US, "%.1f", report.avgDistanceErrorPx)} px")
    }
    sb.appendLine("- **RAM Delta**: ${if (report.ramDeltaMb >= 0) "+" else ""}${report.ramDeltaMb} MB")
    sb.appendLine("- **Verdict**: ${report.verdict}")
    sb.appendLine()
    sb.appendLine("## Trial Breakdown")
    report.roundReports.forEach { round ->
        sb.appendLine("### Round ${round.roundIndex}: ${round.hits}/${round.totalSteps} Hits (${round.durationMs}ms)")
        round.steps.forEach { step ->
            val status = if (step.isHit) "PASS" else "FAIL"
            sb.appendLine("- [$status] Target Button ${step.targetNumber}: `${step.rawOutput}` | Latency: ${step.durationMs}ms")
        }
    }
    return sb.toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisualBenchmarkIntroCard(
    isVisionCapable: Boolean,
    onStart: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Visual Agentic Benchmark (Vision LLMs)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }

            Text(
                text = "Evaluates visual perception, spatial coordinate grounding, and sequence execution.\n\n" +
                        "• Displays a synthetic screen with 6 numbered buttons scattered randomly.\n" +
                        "• The AI model must locate and press each button in ascending order (1 -> 2 -> 3 -> 4 -> 5 -> 6).\n" +
                        "• Repeats across 3 consecutive randomized trials (18 total interactions) to measure execution speed, spatial pixel precision, accuracy, and RAM footprint.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1),
                lineHeight = 18.sp
            )

            if (!isVisionCapable) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF78350F).copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Active model does not report multimodal vision support. Visual benchmarking will test if the engine accepts image prompts.",
                            fontSize = 11.sp,
                            color = Color(0xFFFDE68A)
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Start Visual Benchmark", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

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
