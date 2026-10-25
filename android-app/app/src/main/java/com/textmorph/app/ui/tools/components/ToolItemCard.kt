package com.textmorph.app.ui.tools.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentActionResult
import com.textmorph.app.agent.tools.registry.AgentToolDefinition
import com.textmorph.app.theme.DangerRed
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Interactive card displaying an agent tool with description, parameter inputs,
 * and a live test execution playground.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolItemCard(
    tool: AgentToolDefinition,
    onExecuteAction: suspend (AgentAction) -> AgentActionResult,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<AgentActionResult?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Local state for parameter inputs
    val paramValues = remember(tool) {
        mutableStateMapOf<String, String>().apply {
            tool.parameters.forEach { param ->
                put(param.name, param.defaultValue)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2937), // Elevated Charcoal Slate
        border = BorderStroke(1.dp, Color(0xFF374151)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Name & Expand toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tool.name,
                        color = Color(0xFFF9FAFB),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tool.codeSignature,
                        color = Color(0xFF60A5FA),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = Color(0xFF374151),
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { isExpanded = !isExpanded }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = Color(0xFFE5E7EB),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Description
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tool.description,
                color = Color(0xFF9CA3AF),
                fontSize = 12.5.sp,
                lineHeight = 17.sp
            )

            // Category & Parameter badges
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0x333B82F6),
                    border = BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.4f))
                ) {
                    Text(
                        text = tool.category.label,
                        color = Color(0xFF60A5FA),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (tool.parameters.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0x229CA3AF)
                    ) {
                        Text(
                            text = "No parameters required",
                            color = Color(0xFF9CA3AF),
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    tool.parameters.forEach { param ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x22374151)
                        ) {
                            Text(
                                text = "${param.name}: ${param.type}",
                                color = Color(0xFFD1D5DB),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Expandable Test Playground
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    // Parameter Input Fields
                    tool.parameters.forEach { param ->
                        val currentVal = paramValues[param.name] ?: ""
                        OutlinedTextField(
                            value = currentVal,
                            onValueChange = { paramValues[param.name] = it },
                            label = { Text(param.label, fontSize = 11.5.sp) },
                            placeholder = { Text(param.defaultValue, fontSize = 11.5.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF4B5563),
                                focusedLabelColor = Color(0xFF60A5FA),
                                unfocusedLabelColor = Color(0xFF9CA3AF),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Run Button & Result Feedback
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = {
                                if (isRunning) return@Button
                                isRunning = true
                                lastResult = null
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    val paramsMap = paramValues.mapValues { (_, v) ->
                                        v.toIntOrNull()?.let { JsonPrimitive(it) }
                                            ?: v.toFloatOrNull()?.let { JsonPrimitive(it) }
                                            ?: JsonPrimitive(v)
                                    }
                                    val action = AgentAction(
                                        action = tool.id,
                                        params = paramsMap,
                                        response = "Testing ${tool.name}"
                                    )
                                    val res = onExecuteAction(action)
                                    lastResult = res
                                    isRunning = false
                                }
                            },
                            enabled = !isRunning,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2563EB),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Executing…", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Run Tool", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Result Box
                    lastResult?.let { res ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (res.success) Color(0x2210B981) else Color(0x22DC2626),
                            border = BorderStroke(1.dp, if (res.success) Color(0xFF10B981) else DangerRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (res.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (res.success) Color(0xFF10B981) else DangerRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = res.details,
                                    color = if (res.success) Color(0xFFD1FAE5) else Color(0xFFFCA5A5),
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
