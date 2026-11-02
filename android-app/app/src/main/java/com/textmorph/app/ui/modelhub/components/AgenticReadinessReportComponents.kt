package com.textmorph.app.ui.modelhub.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.benchmark.AgenticReadinessReport
import com.textmorph.app.agent.benchmark.ReadinessTier
import com.textmorph.app.agent.benchmark.ScenarioResult
import com.textmorph.app.agent.benchmark.SubTestResult

// Charcoal Slate Palette
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadinessReportView(
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
                            "Recommended Concurrency: ${report.recommendedConcurrentWorkers} Workers (Safe Limit)",
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
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextPrimary)
                        Spacer(Modifier.width(6.dp))
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
fun ScenarioResultCard(scenario: ScenarioResult) {
    var expanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

            // Sub-Tests Inspection Toggle
            if (scenario.subTests.isNotEmpty()) {
                Surface(
                    color = SubCardBg,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.clickable { expanded = !expanded }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (expanded) "Hide Questions & Model Responses" else "Inspect Questions & Responses (${scenario.subTests.size})",
                            color = AccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scenario.subTests.forEach { sub ->
                            SubTestDetailCard(
                                subTest = sub,
                                onCopyPrompt = {
                                    clipboardManager.setText(AnnotatedString(sub.prompt))
                                    Toast.makeText(context, "Prompt copied!", Toast.LENGTH_SHORT).show()
                                },
                                onCopyResponse = {
                                    clipboardManager.setText(AnnotatedString(sub.rawResponse))
                                    Toast.makeText(context, "Response copied!", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubTestDetailCard(
    subTest: SubTestResult,
    onCopyPrompt: () -> Unit,
    onCopyResponse: () -> Unit
) {
    Surface(
        color = CodeBoxBg,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subTest.title,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "${subTest.pointsAwarded}/${subTest.maxPoints} pts",
                    color = if (subTest.passed) SuccessGreen else ErrorRed,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Prompt Box
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("QUESTION / PROMPT:", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onCopyPrompt, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Prompt", tint = TextSecondary, modifier = Modifier.size(12.dp))
                }
            }
            Surface(
                color = Color(0xFF111827),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = subTest.prompt,
                    color = Color(0xFF93C5FD),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(6.dp)
                )
            }

            // Raw Response Box
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MODEL RAW OUTPUT:", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onCopyResponse, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Response", tint = TextSecondary, modifier = Modifier.size(12.dp))
                }
            }
            Surface(
                color = Color(0xFF111827),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = subTest.rawResponse.ifBlank { "(Empty response)" },
                    color = Color(0xFFE5E7EB),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(6.dp)
                )
            }

            // Explanation
            Text(
                text = "Verdict: ${subTest.explanation}",
                color = if (subTest.passed) Color(0xFF34D399) else Color(0xFFF87171),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

fun formatReportMarkdown(report: AgenticReadinessReport): String {
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
        if (s.subTests.isNotEmpty()) {
            sb.append("#### Sub-Tests:\n")
            for (sub in s.subTests) {
                sb.append("- **${sub.title}** (${sub.pointsAwarded}/${sub.maxPoints} pts): ${sub.explanation}\n")
                sb.append("  - *Prompt*: `${sub.prompt.replace("\n", " ")}`\n")
                sb.append("  - *Response*: `${sub.rawResponse.replace("\n", " ").take(120)}`\n")
            }
            sb.append("\n")
        }
    }
    return sb.toString().trim()
}
