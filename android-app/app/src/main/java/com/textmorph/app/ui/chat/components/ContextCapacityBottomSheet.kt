package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ContextColorState
import com.textmorph.app.data.model.ContextUsageMetrics

/**
 * Bottom Sheet displaying granular context window breakdown and actions
 * for rolling history compaction and artifact pruning.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContextCapacityBottomSheet(
    metrics: ContextUsageMetrics,
    onCompressHistory: () -> Unit,
    onPruneContext: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val activeStateColor = when (metrics.colorState) {
        ContextColorState.SAGE -> Color(0xFF7D9D8B)
        ContextColorState.AMBER -> Color(0xFFA27B5C)
        ContextColorState.CRIMSON -> Color(0xFFE57373)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF2C3930),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Context Window Capacity",
                        color = Color(0xFFDCD7C9),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${metrics.colorState.label} (${(metrics.usageFraction * 100).toInt()}% utilized)",
                        color = activeStateColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF3F4F44),
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(
                        text = "${metrics.totalUsedTokens} / ${metrics.maxContextTokens} tok",
                        color = Color(0xFFDCD7C9),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Multi-segment proportional visual bar
            SegmentedProgressBar(metrics = metrics)

            Spacer(modifier = Modifier.height(20.dp))

            // Breakdown Items
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF232D26))
                    .border(1.dp, Color(0xFF3F4F44), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BreakdownRow(
                    dotColor = Color(0xFF8AA89B),
                    title = "System Prompt & Capabilities",
                    tokens = metrics.systemPromptTokens
                )
                BreakdownRow(
                    dotColor = Color(0xFFA27B5C),
                    title = "Compressed History",
                    tokens = metrics.compressedHistoryTokens
                )
                BreakdownRow(
                    dotColor = Color(0xFFC7A589),
                    title = "Recent Messages (Window)",
                    tokens = metrics.recentMessagesTokens
                )
                if (metrics.currentInputTokens > 0) {
                    BreakdownRow(
                        dotColor = Color(0xFFE5A65D),
                        title = "Pending Input & Media",
                        tokens = metrics.currentInputTokens
                    )
                }
                BreakdownRow(
                    dotColor = Color(0xFF3F4F44),
                    title = "Free Headroom for Output",
                    tokens = metrics.freeSpaceTokens
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onCompressHistory()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA27B5C)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Compress History", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        onPruneContext()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDCD7C9)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3F4F44)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, tint = Color(0xFFDCD7C9), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Prune Artifacts", fontSize = 12.5.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SegmentedProgressBar(metrics: ContextUsageMetrics) {
    val total = metrics.maxContextTokens.toFloat().coerceAtLeast(1f)
    val sysWeight = (metrics.systemPromptTokens / total).coerceIn(0f, 1f)
    val compWeight = (metrics.compressedHistoryTokens / total).coerceIn(0f, 1f)
    val recWeight = (metrics.recentMessagesTokens / total).coerceIn(0f, 1f)
    val inWeight = (metrics.currentInputTokens / total).coerceIn(0f, 1f)
    val freeWeight = (1f - (sysWeight + compWeight + recWeight + inWeight)).coerceAtLeast(0f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF1E2822))
    ) {
        if (sysWeight > 0f) {
            Box(modifier = Modifier.weight(sysWeight).height(10.dp).background(Color(0xFF8AA89B)))
        }
        if (compWeight > 0f) {
            Box(modifier = Modifier.weight(compWeight).height(10.dp).background(Color(0xFFA27B5C)))
        }
        if (recWeight > 0f) {
            Box(modifier = Modifier.weight(recWeight).height(10.dp).background(Color(0xFFC7A589)))
        }
        if (inWeight > 0f) {
            Box(modifier = Modifier.weight(inWeight).height(10.dp).background(Color(0xFFE5A65D)))
        }
        if (freeWeight > 0f) {
            Box(modifier = Modifier.weight(freeWeight).height(10.dp).background(Color(0xFF3F4F44).copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun BreakdownRow(dotColor: Color, title: String, tokens: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = Color(0xFFDCD7C9).copy(alpha = 0.85f),
                fontSize = 12.sp
            )
        }
        Text(
            text = "~$tokens tok",
            color = Color(0xFFDCD7C9),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
