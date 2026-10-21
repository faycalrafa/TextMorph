package com.textmorph.app.service.overlay

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class FloatingButtonState {
    IDLE,
    EXPANDED_MENU,
    GENERATING,
    REVERT_WINDOW
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FloatingToolbarContent(
    getLiveInputText: () -> String,
    prompts: List<PromptEntity>,
    onInsertText: (String) -> Unit,
    onRevertText: (String) -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onTakeScreenshotContext: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    engineProvider: () -> LlmEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(FloatingButtonState.IDLE) }
    var originalTextBeforeGeneration by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Laser Animation for GENERATING state
    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val laserAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing)
        ),
        label = "laserAngle"
    )

    // Countdown Animation for 5s REVERT_WINDOW state
    val revertCountdown = remember { Animatable(1f) }

    LaunchedEffect(state) {
        if (state == FloatingButtonState.REVERT_WINDOW) {
            revertCountdown.snapTo(1f)
            revertCountdown.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 5000, easing = LinearEasing)
            )
            // After 5s without user tap, transition back to IDLE
            if (state == FloatingButtonState.REVERT_WINDOW) {
                state = FloatingButtonState.IDLE
            }
        }
    }

    fun startGeneration(systemInstruction: String, directExecute: Boolean = false) {
        val liveText = getLiveInputText()
        if (liveText.isBlank()) {
            Toast.makeText(context, "Please type text in the field first.", Toast.LENGTH_SHORT).show()
            return
        }

        originalTextBeforeGeneration = liveText
        state = FloatingButtonState.GENERATING

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val engine = engineProvider()
                val effectiveInstruction = if (directExecute) {
                    "You are an on-device writing assistant. Execute the request or instruction provided in the user's text. Return ONLY the final output with zero conversational commentary, zero intros/outros, zero quotes, and zero reasoning traces."
                } else {
                    "$systemInstruction. Strict rule: Output ONLY the transformed result directly. Do not include commentary, explanations, or thinking blocks."
                }

                val sb = StringBuilder()
                engine.generateStream(liveText, effectiveInstruction).collect { token ->
                    sb.append(token)
                }

                val finalOutput = sb.toString().trim()
                if (finalOutput.isNotEmpty()) {
                    onInsertText(finalOutput)
                    // Trigger the 5-second revert window
                    state = FloatingButtonState.REVERT_WINDOW
                } else {
                    state = FloatingButtonState.IDLE
                }
            } catch (e: Exception) {
                AppLogger.e("FloatingToolbar", "Inference error during generation: ${e.message}", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                state = FloatingButtonState.IDLE
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        // Expanded Action Card
        if (state == FloatingButtonState.EXPANDED_MENU) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .width(300.dp)
                    .padding(end = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
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
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TextMorph AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = { state = FloatingButtonState.IDLE },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Primary Button: Execute Request Typed in Input Box
                    Button(
                        onClick = {
                            startGeneration(systemInstruction = "", directExecute = true)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Execute Request in Field",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Quick Transforms",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        prompts.take(6).forEach { prompt ->
                            AssistChip(
                                onClick = {
                                    startGeneration(systemInstruction = prompt.systemInstruction)
                                },
                                label = { Text("${prompt.iconEmoji} ${prompt.title}", fontSize = 11.sp) }
                            )
                        }

                        if (onTakeScreenshotContext != null) {
                            AssistChip(
                                onClick = onTakeScreenshotContext,
                                label = { Text("📷 Screen Context", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // Circular Floating Button (54dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(54.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.x, dragAmount.y)
                        }
                    )
                }
                .clickable {
                    when (state) {
                        FloatingButtonState.IDLE -> {
                            state = FloatingButtonState.EXPANDED_MENU
                        }
                        FloatingButtonState.EXPANDED_MENU -> {
                            state = FloatingButtonState.IDLE
                        }
                        FloatingButtonState.GENERATING -> {
                            // User pressed Stop button
                            activeJob?.cancel()
                            activeJob = null
                            state = FloatingButtonState.IDLE
                            Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                        }
                        FloatingButtonState.REVERT_WINDOW -> {
                            // User pressed Return within 5 seconds
                            onRevertText(originalTextBeforeGeneration)
                            state = FloatingButtonState.IDLE
                            Toast.makeText(context, "Reverted to original text.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        ) {
            // Background surface
            Surface(
                shape = CircleShape,
                color = when (state) {
                    FloatingButtonState.GENERATING -> Color(0xFF2C3930)
                    FloatingButtonState.REVERT_WINDOW -> Color(0xFF3F4F44)
                    else -> MaterialTheme.colorScheme.primary
                },
                shadowElevation = 8.dp,
                modifier = Modifier.size(50.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (state) {
                        FloatingButtonState.GENERATING -> {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Generation",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        FloatingButtonState.REVERT_WINDOW -> {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Revert to Original",
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "TextMorph AI",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // Laser Tracing Animation Border for GENERATING State
            if (state == FloatingButtonState.GENERATING) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Base track
                    drawCircle(
                        color = Color(0x33C59B76),
                        radius = size.minDimension / 2 - 2.dp.toPx(),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    // Rotating luminous laser beam
                    drawArc(
                        brush = Brush.sweepGradient(
                            0.0f to Color.Transparent,
                            0.7f to Color(0xFFC59B76),
                            1.0f to Color(0xFFFFD54F)
                        ),
                        startAngle = laserAngle,
                        sweepAngle = 100f,
                        useCenter = false,
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // 5-Second Countdown Ring for REVERT_WINDOW State
            if (state == FloatingButtonState.REVERT_WINDOW) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = Color(0xFFFFD54F),
                        startAngle = -90f,
                        sweepAngle = 360f * revertCountdown.value,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}
