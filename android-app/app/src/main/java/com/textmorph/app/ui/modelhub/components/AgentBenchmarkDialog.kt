package com.textmorph.app.ui.modelhub.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.agent.benchmark.AgentBenchmarkEngine
import com.textmorph.app.agent.benchmark.BenchmarkProgressState
import com.textmorph.app.agent.benchmark.BenchmarkSummaryReport
import com.textmorph.app.agent.benchmark.VisualAgentBenchmarkEngine
import com.textmorph.app.agent.benchmark.VisualBenchmarkProgressState
import com.textmorph.app.agent.benchmark.VisualBenchmarkReport
import com.textmorph.app.llm.LlmEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Benchmark execution mode paradigms.
 */
enum class BenchmarkMode(val title: String) {
    STANDARD_TEXT("Standard (Text)"),
    VISUAL_AGENT("Visual (Vision LLM)")
}

/**
 * Responsive dialog executing LLM agentic benchmark comparing Code-as-Action vs JSON,
 * as well as Visual Agentic Benchmarking for multimodal vision LLMs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentBenchmarkDialog(
    engine: LlmEngine,
    modelName: String,
    backend: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var benchmarkJob by remember { mutableStateOf<Job?>(null) }
    var selectedMode by remember {
        mutableStateOf(if (engine.isVisionCapable) BenchmarkMode.VISUAL_AGENT else BenchmarkMode.STANDARD_TEXT)
    }

    var progressState by remember { mutableStateOf(BenchmarkProgressState()) }
    var summaryReport by remember { mutableStateOf<BenchmarkSummaryReport?>(null) }

    var visualProgressState by remember { mutableStateOf(VisualBenchmarkProgressState()) }
    var visualReport by remember { mutableStateOf<VisualBenchmarkReport?>(null) }

    fun startBenchmark() {
        benchmarkJob?.cancel()
        summaryReport = null
        benchmarkJob = scope.launch {
            try {
                val report = AgentBenchmarkEngine.runBenchmark(
                    engine = engine,
                    modelName = modelName,
                    backend = backend,
                    context = context,
                    onProgress = { state -> progressState = state }
                )
                summaryReport = report
                progressState = progressState.copy(isRunning = false)
            } catch (e: Exception) {
                progressState = progressState.copy(isRunning = false, error = e.message)
            }
        }
    }

    fun startVisualBenchmark() {
        benchmarkJob?.cancel()
        visualReport = null
        benchmarkJob = scope.launch {
            try {
                val report = VisualAgentBenchmarkEngine.runVisualBenchmark(
                    engine = engine,
                    modelName = modelName,
                    backend = backend,
                    context = context,
                    onProgress = { state -> visualProgressState = state }
                )
                visualReport = report
                visualProgressState = visualProgressState.copy(isRunning = false)
            } catch (e: Exception) {
                visualProgressState = visualProgressState.copy(isRunning = false, error = e.message)
            }
        }
    }

    Dialog(
        onDismissRequest = {
            benchmarkJob?.cancel()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Agent Capability Benchmark",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "$modelName • $backend",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    IconButton(onClick = {
                        benchmarkJob?.cancel()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                    }
                }

                // Mode Switcher Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !progressState.isRunning && !visualProgressState.isRunning) {
                                selectedMode = BenchmarkMode.STANDARD_TEXT
                            },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedMode == BenchmarkMode.STANDARD_TEXT) MaterialTheme.colorScheme.primary else Color.Transparent
                    ) {
                        Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Standard (Text)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedMode == BenchmarkMode.STANDARD_TEXT) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !progressState.isRunning && !visualProgressState.isRunning) {
                                selectedMode = BenchmarkMode.VISUAL_AGENT
                            },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedMode == BenchmarkMode.VISUAL_AGENT) MaterialTheme.colorScheme.primary else Color.Transparent
                    ) {
                        Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (selectedMode == BenchmarkMode.VISUAL_AGENT) Color.White else Color(0xFF94A3B8)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Visual (Vision LLM)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedMode == BenchmarkMode.VISUAL_AGENT) Color.White else Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }

                // Mode 1: Standard Text Benchmark Content
                if (selectedMode == BenchmarkMode.STANDARD_TEXT) {
                    if (!progressState.isRunning && summaryReport == null) {
                        BenchmarkIntroCard(
                            testCount = AgentBenchmarkEngine.TEST_CASES.size,
                            onStart = { startBenchmark() }
                        )
                    }

                    if (progressState.isRunning) {
                        BenchmarkRunningCard(
                            state = progressState,
                            onCancel = {
                                benchmarkJob?.cancel()
                                progressState = progressState.copy(isRunning = false)
                            }
                        )
                    }

                    if (summaryReport != null) {
                        BenchmarkResultsView(
                            report = summaryReport!!,
                            context = context,
                            onRunAgain = { startBenchmark() }
                        )
                    }

                    if (progressState.error != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Benchmark error: ${progressState.error}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Mode 2: Visual Agentic Benchmark Content
                if (selectedMode == BenchmarkMode.VISUAL_AGENT) {
                    if (!visualProgressState.isRunning && visualReport == null) {
                        VisualBenchmarkIntroCard(
                            isVisionCapable = engine.isVisionCapable,
                            onStart = { startVisualBenchmark() }
                        )
                    }

                    if (visualProgressState.isRunning) {
                        VisualBenchmarkRunningCard(
                            state = visualProgressState,
                            onCancel = {
                                benchmarkJob?.cancel()
                                visualProgressState = visualProgressState.copy(isRunning = false)
                            }
                        )
                    }

                    if (visualReport != null) {
                        VisualBenchmarkResultsView(
                            report = visualReport!!,
                            context = context,
                            onRunAgain = { startVisualBenchmark() }
                        )
                    }

                    if (visualProgressState.error != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Visual benchmark error: ${visualProgressState.error}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchmarkIntroCard(
    testCount: Int,
    onStart: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Head-to-Head Paradigm Evaluation",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color.White
            )
            Text(
                text = "Tests this LLM across $testCount agentic mobile tasks comparing executable Code-as-Action commands vs structured JSON.\n\n" +
                        "Tracks impact on RAM, CPU/battery heat, latency, and tk/s speed, then generates an actionable recommendation report.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1),
                lineHeight = 18.sp
            )

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
                    Text("Start Benchmark", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BenchmarkRunningCard(
    state: BenchmarkProgressState,
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
                Text(
                    text = state.currentStepDescription,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
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

            val progressFraction = if (state.totalTests > 0) state.currentTestIndex.toFloat() / state.totalTests.toFloat() else 0f
            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0.05f, 1.0f) },
                modifier = Modifier.fillMaxWidth()
            )

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
                        maxLines = 6
                    )
                }
            }
        }
    }
}
