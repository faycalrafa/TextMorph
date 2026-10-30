package com.textmorph.app.ui.tools.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.agent.tools.js.JsToolEntity
import com.textmorph.app.agent.tools.js.JsToolRunner
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Interactive playground dialog for testing a JavaScript tool with live parameter inputs.
 */
@Composable
fun JsToolTesterDialog(
    tool: JsToolEntity,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { JsToolRunner.getInstance(context) }
    val jsonParser = remember { Json { ignoreUnknownKeys = true; isLenient = true } }

    val defaultParamsJson = remember(tool) {
        val map = mutableMapOf<String, String>()
        tool.parameters.forEach { param ->
            map[param.name] = param.defaultValue.ifEmpty { "10" }
        }
        if (map.isEmpty()) {
            when (tool.id) {
                "get_weather" -> "{\"location\": \"Paris\"}"
                "calculate_tip" -> "{\"bill\": 65.50, \"tip_percent\": 18, \"split\": 2}"
                "unit_converter" -> "{\"value\": 25, \"from_unit\": \"c\", \"to_unit\": \"f\"}"
                else -> "{}"
            }
        } else {
            "{\n" + map.entries.joinToString(",\n") { "  \"${it.key}\": \"${it.value}\"" } + "\n}"
        }
    }

    var paramsInput by remember { mutableStateOf(defaultParamsJson) }
    var isRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF374151)),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Test Tool: ${tool.name}",
                            color = Color(0xFFF9FAFB),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF9CA3AF))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = tool.description,
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Input Parameters (JSON):",
                    fontSize = 12.sp,
                    color = Color(0xFFD1D5DB),
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = paramsInput,
                    onValueChange = { paramsInput = it },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedTextColor = Color(0xFF38BDF8),
                        unfocusedTextColor = Color(0xFF38BDF8)
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        scope.launch {
                            isRunning = true
                            testResult = null
                            try {
                                val parsedParams = try {
                                    jsonParser.parseToJsonElement(paramsInput).jsonObject
                                } catch (_: Exception) {
                                    JsonObject(emptyMap())
                                }
                                val (ok, res) = runner.execute(tool, parsedParams)
                                isSuccess = ok
                                testResult = res
                            } catch (e: Exception) {
                                isSuccess = false
                                testResult = "Execution error: ${e.message}"
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Executing…")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Run Tool", fontWeight = FontWeight.Bold)
                    }
                }

                // Output Result Display
                if (testResult != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (isSuccess) "Execution Output:" else "Execution Error:",
                        fontSize = 12.sp,
                        color = if (isSuccess) Color(0xFF34D399) else Color(0xFFEF4444),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0B1020),
                        border = BorderStroke(1.dp, if (isSuccess) Color(0xFF059669) else Color(0xFFDC2626)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = testResult.orEmpty(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            color = Color(0xFFF9FAFB),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, Color(0xFF4B5563)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close", color = Color(0xFFD1D5DB))
                }
            }
        }
    }
}
