package com.textmorph.app.ui.modelhub

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.textmorph.app.agent.benchmark.BenchmarkRepository
import com.textmorph.app.agent.benchmark.ReadinessProgressState
import com.textmorph.app.agent.benchmark.ReadinessTier
import com.textmorph.app.agent.benchmark.ScenarioResult
import com.textmorph.app.agent.benchmark.SubTestResult
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.ui.modelhub.components.ReadinessReportView
import com.textmorph.app.ui.modelhub.components.formatReportMarkdown
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Charcoal Slate Palette
private val SlateBg = Color(0xFF0B1020)
private val CardSurface = Color(0xFF111827)
private val CardBorder = Color(0xFF1F2937)
private val SubCardBg = Color(0xFF1F2937)
private val CodeBoxBg = Color(0xFF070B14)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val benchmarkRepository = remember { BenchmarkRepository.getInstance(context) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var progressState by remember { mutableStateOf(ReadinessProgressState()) }
    var report by remember(modelName) { mutableStateOf(benchmarkRepository.getReport(modelName)) }
    val clipboardManager = LocalClipboardManager.current

    val isEngineReady = engine.isLoaded() && modelName.isNotBlank() && !modelName.equals("No Model Loaded", ignoreCase = true)

    fun startSuite() {
        if (!isEngineReady) {
            Toast.makeText(context, "No model loaded. Please load a model or configure an API first.", Toast.LENGTH_LONG).show()
            return
        }
        runJob?.cancel()
        report = null
        runJob = scope.launch {
            try {
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
                benchmarkRepository.saveReport(rep)
            } catch (e: Exception) {
                progressState = progressState.copy(
                    isRunning = false,
                    errorMessage = e.message ?: "Benchmark run failed"
                )
                Toast.makeText(context, "Benchmark failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
            // Unloaded Engine Warning Banner
            if (!isEngineReady) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("No Active Model Loaded", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "To run the benchmark, please load an on-device model (.litertlm or .gguf) in Model Hub or activate a Cloud API in Settings.",
                                color = TextPrimary.copy(alpha = 0.85f),
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }
            }

            // Intro Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Controlled Agentic Evaluation", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "Evaluates Code-as-Action tool selection, hammer syndrome resistance, self-correction recovery, context grounding, /plan decomposition quality, and runtime telemetry.",
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
                            enabled = isEngineReady && !progressState.isRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Icon(if (report != null) Icons.Default.Refresh else Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (report != null) "Run Suite Again" else "Run Readiness Suite")
                        }
                    }
                }
            }

            // Real-Time Live Execution Console
            AnimatedVisibility(visible = progressState.isRunning) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentBlue)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Running Scenario ${progressState.currentScenarioIndex} of ${progressState.totalScenarios}",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(progressState.currentScenarioName, color = AccentBlue, fontSize = 12.sp)
                            }
                        }

                        LinearProgressIndicator(
                            progress = { progressState.currentScenarioIndex / progressState.totalScenarios.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AccentBlue,
                            trackColor = SubCardBg
                        )

                        // Active Sub-Test Box
                        if (progressState.currentSubTestTitle.isNotBlank()) {
                            Surface(
                                color = SubCardBg,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(AccentBlue)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            progressState.currentSubTestTitle,
                                            color = TextPrimary,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    if (progressState.currentSubTestPrompt.isNotBlank()) {
                                        Text("PROMPT DISPATCHED:", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Surface(
                                            color = CodeBoxBg,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = progressState.currentSubTestPrompt,
                                                color = Color(0xFF67E8F9),
                                                fontSize = 11.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
                        Toast.makeText(context, "Report copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

