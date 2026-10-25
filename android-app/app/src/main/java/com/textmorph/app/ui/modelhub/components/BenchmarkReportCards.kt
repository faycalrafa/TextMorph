package com.textmorph.app.ui.modelhub.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.benchmark.BenchmarkFormat
import com.textmorph.app.agent.benchmark.BenchmarkSummaryReport
import com.textmorph.app.agent.benchmark.BenchmarkTestReport

/**
 * Report cards, metric comparators, and markdown export for AgentBenchmarkDialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BenchmarkResultsView(
    report: BenchmarkSummaryReport,
    context: Context,
    onRunAgain: () -> Unit
) {
    // Winner Banner
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (report.recommendedFormat == BenchmarkFormat.CODE_AS_ACTION) Color(0xFF14532D) else Color(0xFF1E3A8A)
        )
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "🏆 Recommended: ${report.recommendedFormat.displayName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = report.verdictReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 16.sp
                )
            }
        }
    }

    // Side-by-side metric comparison cards
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricComparisonCard(
            title = "Success Rate",
            codeValue = "${report.codeSuccessCount}/${report.totalTests}",
            jsonValue = "${report.jsonSuccessCount}/${report.totalTests}",
            subtext = if (report.codeSuccessCount >= report.jsonSuccessCount) "Code matches/exceeds JSON" else "JSON had higher accuracy",
            modifier = Modifier.weight(1f)
        )
        MetricComparisonCard(
            title = "Avg Latency",
            codeValue = "${report.codeAvgLatencyMs} ms",
            jsonValue = "${report.jsonAvgLatencyMs} ms",
            subtext = if (report.latencySpeedupPercent > 0) "${report.latencySpeedupPercent}% faster" else "Similar latency",
            modifier = Modifier.weight(1f)
        )
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricComparisonCard(
            title = "Avg Output Tokens",
            codeValue = String.format("%.1f", report.codeAvgTokens),
            jsonValue = String.format("%.1f", report.jsonAvgTokens),
            subtext = if (report.tokenSavingsPercent > 0) "${report.tokenSavingsPercent}% token reduction" else "Similar footprint",
            modifier = Modifier.weight(1f)
        )
        MetricComparisonCard(
            title = "Avg Speed (tk/s)",
            codeValue = String.format("%.1f", report.codeAvgTps),
            jsonValue = String.format("%.1f", report.jsonAvgTps),
            subtext = "Generation rate",
            modifier = Modifier.weight(1f)
        )
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricComparisonCard(
            title = "RAM Delta",
            codeValue = "${report.codeAvgRamDeltaMb} MB",
            jsonValue = "${report.jsonAvgRamDeltaMb} MB",
            subtext = "Memory impact",
            modifier = Modifier.weight(1f)
        )
        MetricComparisonCard(
            title = "Temp Rise",
            codeValue = report.codeAvgTempDeltaC?.let { String.format("+%.2f°C", it) } ?: "N/A",
            jsonValue = report.jsonAvgTempDeltaC?.let { String.format("+%.2f°C", it) } ?: "N/A",
            subtext = "Thermal footprint",
            modifier = Modifier.weight(1f)
        )
    }

    // Detailed Per-Test Accordions
    Text(
        text = "Test Details (${report.testReports.size} Tasks)",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )

    report.testReports.forEach { testReport ->
        BenchmarkTestAccordion(testReport)
    }

    // Actions
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = formatMarkdownReport(report)
                clipMgr.setPrimaryClip(ClipData.newPlainText("Benchmark Report", text))
                Toast.makeText(context, "Markdown report copied to clipboard!", Toast.LENGTH_SHORT).show()
            },
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Copy Markdown Report", fontSize = 12.sp)
        }

        OutlinedButton(
            onClick = onRunAgain,
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Run Again", fontSize = 12.sp)
        }
    }
}

@Composable
fun MetricComparisonCard(
    title: String,
    codeValue: String,
    jsonValue: String,
    subtext: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Code", fontSize = 10.sp, color = Color(0xFF38BDF8))
                    Text(codeValue, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("JSON", fontSize = 10.sp, color = Color(0xFFA78BFA))
                    Text(jsonValue, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtext, fontSize = 10.sp, color = Color(0xFF4ADE80))
        }
    }
}

@Composable
fun BenchmarkTestAccordion(report: BenchmarkTestReport) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(report.testCase.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(report.testCase.userPrompt, fontSize = 11.sp, color = Color(0xFF94A3B8), maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (report.codeResult.isSemanticMatch) Icons.Default.CheckCircle else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (report.codeResult.isSemanticMatch) Color(0xFF4ADE80) else Color(0xFFEF4444),
                        modifier = Modifier.size(14.dp)
                    )
                    Text("Code", fontSize = 10.sp, color = Color(0xFF38BDF8))
                    Text("|", fontSize = 10.sp, color = Color(0xFF64748B))
                    Icon(
                        if (report.jsonResult.isSemanticMatch) Icons.Default.CheckCircle else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (report.jsonResult.isSemanticMatch) Color(0xFF4ADE80) else Color(0xFFEF4444),
                        modifier = Modifier.size(14.dp)
                    )
                    Text("JSON", fontSize = 10.sp, color = Color(0xFFA78BFA))
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF0F172A), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Code-as-Action Output (${report.codeResult.durationMs}ms • ${report.codeResult.tokenCount} tok):", fontSize = 10.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                            Text(report.codeResult.rawOutput.ifEmpty { "[No Output]" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFE2E8F0))
                        }
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF0F172A), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("JSON Output (${report.jsonResult.durationMs}ms • ${report.jsonResult.tokenCount} tok):", fontSize = 10.sp, color = Color(0xFFA78BFA), fontWeight = FontWeight.Bold)
                            Text(report.jsonResult.rawOutput.ifEmpty { "[No Output]" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFE2E8F0))
                        }
                    }
                }
            }
        }
    }
}

fun formatMarkdownReport(report: BenchmarkSummaryReport): String {
    return buildString {
        appendLine("# TextMorph Agentic Benchmark Report")
        appendLine("**Model:** ${report.modelName} (${report.backend})")
        appendLine("**Verdict:** ${report.recommendedFormat.displayName}")
        appendLine("**Rationale:** ${report.verdictReason}\n")
        appendLine("## Aggregate Metrics")
        appendLine("| Metric | Code-as-Action | JSON Payload |")
        appendLine("| :--- | :--- | :--- |")
        appendLine("| **Success Rate** | ${report.codeSuccessCount}/${report.totalTests} | ${report.jsonSuccessCount}/${report.totalTests} |")
        appendLine("| **Avg Latency** | ${report.codeAvgLatencyMs} ms | ${report.jsonAvgLatencyMs} ms |")
        appendLine("| **Avg Output Tokens** | ${String.format("%.1f", report.codeAvgTokens)} | ${String.format("%.1f", report.jsonAvgTokens)} |")
        appendLine("| **Avg Speed** | ${String.format("%.1f", report.codeAvgTps)} tok/s | ${String.format("%.1f", report.jsonAvgTps)} tok/s |")
        appendLine("| **Avg RAM Delta** | ${report.codeAvgRamDeltaMb} MB | ${report.jsonAvgRamDeltaMb} MB |")
        appendLine("| **Avg Temp Rise** | ${report.codeAvgTempDeltaC?.let { String.format("+%.2f°C", it) } ?: "N/A"} | ${report.jsonAvgTempDeltaC?.let { String.format("+%.2f°C", it) } ?: "N/A"} |\n")
        appendLine("## Per-Task Breakdown")
        report.testReports.forEachIndexed { i, t ->
            appendLine("### ${i + 1}. ${t.testCase.title}")
            appendLine("- **Prompt:** `${t.testCase.userPrompt}`")
            appendLine("- **Code (${t.codeResult.durationMs}ms, ${t.codeResult.tokenCount} tok):** `${t.codeResult.rawOutput}` (${if (t.codeResult.isSemanticMatch) "PASS" else "FAIL"})")
            appendLine("- **JSON (${t.jsonResult.durationMs}ms, ${t.jsonResult.tokenCount} tok):** `${t.jsonResult.rawOutput}` (${if (t.jsonResult.isSemanticMatch) "PASS" else "FAIL"})\n")
        }
    }
}
