package com.textmorph.app.ui.modelhub

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.benchmark.AgenticReadinessReport
import com.textmorph.app.agent.benchmark.AgenticReadinessSuite
import com.textmorph.app.agent.benchmark.ReadinessProgressState
import com.textmorph.app.agent.benchmark.ReadinessTier
import com.textmorph.app.agent.benchmark.ScenarioResult
import com.textmorph.app.llm.LlmEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Charcoal Slate Palette
private val SlateBg = Color(0xFF0B1020)
private val CardSurface = Color(0xFF111827)
private val CardBorder = Color(0xFF1F2937)
private val SubCardBg = Color(0xFF1F2937)
private val AccentBlue = Color(0xFF3B82F6)
private val TextPrimary = Color(0xFFF9FAFB)
private val TextSecondary = Color(0xFF9CA3AF)
private val SuccessGreen = Color(0xFF10B981)
private val WarningAmber = Color(0xFFF59E0B)
private val ErrorRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgenticReadinessScreen(
    engine: LlmEngine,
    modelName: String,
    backend: String,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var runJob by remember { mutableStateOf<Job?>(null) }
    var progressState by remember { mutableStateOf(ReadinessProgressState()) }
    var report by remember { mutableStateOf<AgenticReadinessReport?>(null) }
    val clipboardManager = LocalClipboardManager.current

    fun startSuite() {
        runJob?.cancel()
        report = null
        runJob = scope.launch {
            val rep = AgenticReadinessSuite.runReadinessSuite(
                engine = engine,
                modelName = modelName,
                backend = backend,
                onProgress = { state ->
                    progressState = state
                    if (state.completedReport != null) {
                        report = state.completedReport
                    }
                }
            )
            report = rep
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agentic Readiness Suite", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("$modelName • $backend", color = TextSecondary, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateBg)
            )
        },
        containerColor = SlateBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Intro Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Controlled Agentic Evaluation", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "Evaluates Code-as-Action tool selection, hammer syndrome resistance, self-correction recovery, context grounding, /plan decomposition quality, and on-device hardware telemetry.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { startSuite() },
                            enabled = !progressState.isRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Icon(if (report != null) Icons.Default.Refresh else Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (report != null) "Run Suite Again" else "Run Readiness Suite")
                        }
                    }
                }
            }

            // Running Progress Card
            AnimatedVisibility(visible = progressState.isRunning) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentBlue)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Running Scenario ${progressState.currentScenarioIndex} of ${progressState.totalScenarios}",
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }

                        LinearProgressIndicator(
                            progress = { progressState.currentScenarioIndex / progressState.totalScenarios.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AccentBlue,
                            trackColor = SubCardBg
                        )

                        Text(progressState.currentScenarioName, color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }

            // Results Card
            report?.let { rep ->
                ReadinessReportView(
                    report = rep,
                    onCopyMarkdown = {
                        val md = formatReportMarkdown(rep)
                        clipboardManager.setText(AnnotatedString(md))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadinessReportView(
    report: AgenticReadinessReport,
    onCopyMarkdown: () -> Unit
) {
    val tierColor = when (report.tier) {
        ReadinessTier.AUTONOMOUS_READY -> SuccessGreen
        ReadinessTier.ASSISTED_AGENT -> AccentBlue
        ReadinessTier.EXPERIMENTAL -> WarningAmber
        ReadinessTier.CHAT_ONLY -> ErrorRed
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Summary Header Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("AGENTIC READINESS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(report.tier.displayName, color = tierColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .background(tierColor.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                            .border(1.dp, tierColor, shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("${report.totalScore} / 100", color = tierColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }

                Text(report.tier.description, color = TextPrimary, fontSize = 13.sp)

                // Recommended Workers Pill
                Surface(
                    color = SubCardBg,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Recommended Concurrency: ${report.recommendedConcurrentWorkers} Workers (Safe Mobile Limit)",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onCopyMarkdown) {
                        Text("Copy Report as Markdown", color = TextPrimary, fontSize = 12.sp)
                    }
                }
            }
        }

        // Scenario Breakdown Cards
        Text("Detailed Scenario Breakdown", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

        for (scenario in report.scenarioResults) {
            ScenarioResultCard(scenario)
        }
    }
}

@Composable
private fun ScenarioResultCard(scenario: ScenarioResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        if (scenario.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (scenario.passed) SuccessGreen else WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(scenario.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }

                Text(
                    "${scenario.score}/${scenario.maxScore} pts",
                    color = if (scenario.passed) SuccessGreen else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Text(scenario.details, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)

            scenario.telemetry?.let { tel ->
                Surface(
                    color = SubCardBg,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Text("TTFT: ${tel.ttftMs}ms", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Speed: ${"%.1f".format(tel.tokensPerSecond)} tps", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("RAM Δ: ${tel.memoryDeltaMb}MB", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private fun formatReportMarkdown(report: AgenticReadinessReport): String {
    val sb = StringBuilder()
    sb.append("# Agentic Readiness Suite Report\n")
    sb.append("- **Model**: ${report.modelName}\n")
    sb.append("- **Backend**: ${report.backend}\n")
    sb.append("- **Total Score**: ${report.totalScore}/100\n")
    sb.append("- **Readiness Tier**: ${report.tier.displayName}\n")
    sb.append("- **Recommended Concurrency**: ${report.recommendedConcurrentWorkers} Workers\n\n")
    sb.append("## Scenario Results\n")
    for (s in report.scenarioResults) {
        sb.append("### ${s.name} (${s.score}/${s.maxScore} pts) - ${if (s.passed) "PASSED" else "FAILED"}\n")
        sb.append("${s.details}\n\n")
    }
    return sb.toString().trim()
}
