package com.textmorph.app.service.overlay

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.ui.camera.CameraCaptureActivity
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class FloatingButtonState {
    IDLE,
    GENERATING,
    REVERT_WINDOW
}

sealed class RadialActionItem {
    data class PromptAction(val prompt: PromptEntity) : RadialActionItem()
    data object UndoAction : RadialActionItem()
}

@Composable
fun FloatingToolbarContent(
    getLiveInputText: () -> String,
    prompts: List<PromptEntity>,
    onInsertText: (String) -> Unit,
    onRevertText: () -> Unit,
    hasUndo: () -> Boolean,
    isPlacedOnRight: () -> Boolean,
    onRadialStateChanged: (isOpen: Boolean, offsetX: Int, offsetY: Int) -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onTakeScreenshotContext: (((ByteArray) -> Unit) -> Unit)? = null,
    onExtractTranscript: (() -> String)? = null,
    onDismiss: () -> Unit,
    engineProvider: () -> LlmEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val TAG = "FloatingToolbar"
    var state by remember { mutableStateOf(FloatingButtonState.IDLE) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Radial Menu Interaction States
    var isRadialOpen by remember { mutableStateOf(false) }
    var hoveredIndex by remember { mutableIntStateOf(-1) }
    var touchPointerPos by remember { mutableStateOf(Offset.Zero) }

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
            if (state == FloatingButtonState.REVERT_WINDOW) {
                state = FloatingButtonState.IDLE
            }
        }
    }

    // Prepare radial items: top prompt actions + persistent bottom Undo action
    val radialItems: List<RadialActionItem> = remember(prompts) {
        val topPrompts = prompts.take(5).map { RadialActionItem.PromptAction(it) }
        topPrompts + listOf(RadialActionItem.UndoAction)
    }

    // Geometry measurements in Dp & Px
    val buttonSizeDp = 54.dp
    val buttonRadiusDp = 27.dp
    val orbitRadiusDp = 135.dp
    val containerWidthDp = 240.dp
    val containerHeightDp = 360.dp

    val containerWidthPx = with(density) { containerWidthDp.toPx() }
    val containerHeightPx = with(density) { containerHeightDp.toPx() }
    val orbitRadiusPx = with(density) { orbitRadiusDp.toPx() }
    val buttonSizePx = with(density) { buttonSizeDp.toPx() }

    val isRightSide = isPlacedOnRight()

    // Button center coordinate within the expanded container
    val buttonCenterX = if (isRightSide) containerWidthPx - buttonSizePx / 2 - with(density) { 16.dp.toPx() }
    else buttonSizePx / 2 + with(density) { 16.dp.toPx() }
    val buttonCenterY = containerHeightPx / 2

    // Offset of the button within the expanded container
    val buttonOffsetXPx = (buttonCenterX - buttonSizePx / 2).roundToInt()
    val buttonOffsetYPx = (buttonCenterY - buttonSizePx / 2).roundToInt()

    fun notifyRadialState(open: Boolean) {
        isRadialOpen = open
        if (open) {
            onRadialStateChanged(true, buttonOffsetXPx, buttonOffsetYPx)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            hoveredIndex = -1
            onRadialStateChanged(false, 0, 0)
        }
    }

    // Calculate node coordinates along the orbit
    // Item 0 is at top, Item N-1 (Undo) is at bottom
    fun getActionPosition(index: Int, total: Int): Offset {
        val t = if (total <= 1) 0.5f else index.toFloat() / (total - 1)
        val angleDeg = if (isRightSide) {
            // Arc curves to the LEFT (255 degrees at top down to 105 degrees at bottom)
            255f - t * 150f
        } else {
            // Arc curves to the RIGHT (-75 degrees at top down to +75 degrees at bottom)
            -75f + t * 150f
        }
        val rad = Math.toRadians(angleDeg.toDouble())
        val x = buttonCenterX + (orbitRadiusPx * cos(rad)).toFloat()
        val y = buttonCenterY + (orbitRadiusPx * sin(rad)).toFloat()
        return Offset(x, y)
    }

    // Execution pipelines
    fun startGeneration(systemInstruction: String, directExecute: Boolean = false) {
        val liveText = getLiveInputText()
        if (liveText.isBlank()) {
            Toast.makeText(context, "Please type or place cursor in a text field first.", Toast.LENGTH_SHORT).show()
            return
        }

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
                if (finalOutput.startsWith("Error:") || finalOutput.startsWith("Error (")) {
                    AppLogger.w(TAG, "Generation returned error: $finalOutput")
                    Toast.makeText(context, finalOutput, Toast.LENGTH_LONG).show()
                    state = FloatingButtonState.IDLE
                    return@launch
                }

                if (finalOutput.isNotEmpty()) {
                    onInsertText(finalOutput)
                    state = FloatingButtonState.REVERT_WINDOW
                } else {
                    state = FloatingButtonState.IDLE
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Inference error during generation: ${e.message}", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                state = FloatingButtonState.IDLE
            }
        }
    }

    fun startVisionGenerationWithBytes(instruction: String, imageBytes: ByteArray) {
        state = FloatingButtonState.GENERATING
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val engine = engineProvider()
                val liveText = getLiveInputText().ifBlank { "Screen Multimodal Context" }
                val sb = StringBuilder()
                engine.generateStream(liveText, instruction, imageBytes).collect { token ->
                    sb.append(token)
                }

                val finalOutput = sb.toString().trim()
                if (finalOutput.isNotEmpty()) {
                    onInsertText(finalOutput)
                    state = FloatingButtonState.REVERT_WINDOW
                } else {
                    state = FloatingButtonState.IDLE
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Vision inference error: ${e.message}", e)
                Toast.makeText(context, "Vision error: ${e.message}", Toast.LENGTH_SHORT).show()
                state = FloatingButtonState.IDLE
            }
        }
    }

    fun startVisionGeneration(instruction: String) {
        if (onTakeScreenshotContext == null) {
            Toast.makeText(context, "Screenshot capture not available.", Toast.LENGTH_SHORT).show()
            return
        }

        onTakeScreenshotContext.invoke { imageBytes ->
            startVisionGenerationWithBytes(instruction, imageBytes)
        }
    }

    fun startOcrGeneration(instruction: String) {
        if (onTakeScreenshotContext == null) {
            Toast.makeText(context, "Screen capture not available for OCR.", Toast.LENGTH_SHORT).show()
            return
        }

        onTakeScreenshotContext.invoke { imageBytes ->
            val ocrInstruction = "$instruction. Transcribe all readable text from this screen clearly and format it cleanly."
            startVisionGenerationWithBytes(ocrInstruction, imageBytes)
        }
    }

    fun startAccessibilityTextGeneration(prompt: PromptEntity) {
        val transcript = onExtractTranscript?.invoke()?.trim() ?: ""
        val liveText = getLiveInputText().trim()
        val contextPrompt = buildString {
            if (transcript.isNotEmpty()) {
                append("Conversation & Visible Screen Context:\n$transcript\n\n")
            }
            if (liveText.isNotEmpty()) {
                append("User Input: $liveText\n\n")
            }
            append("Instruction: ${prompt.systemInstruction}")
        }
        startGeneration(contextPrompt, directExecute = false)
    }

    fun executeRadialItem(item: RadialActionItem) {
        when (item) {
            is RadialActionItem.UndoAction -> {
                if (hasUndo()) {
                    onRevertText()
                    Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Nothing to undo.", Toast.LENGTH_SHORT).show()
                }
            }
            is RadialActionItem.PromptAction -> {
                val prompt = item.prompt
                when (prompt.contextAction) {
                    ContextAction.SCREENSHOT -> startVisionGeneration(prompt.systemInstruction)
                    ContextAction.CAMERA -> {
                        Toast.makeText(context, "Opening camera...", Toast.LENGTH_SHORT).show()
                        CameraCaptureActivity.launch(context) { bytes ->
                            if (bytes != null) {
                                startVisionGenerationWithBytes(prompt.systemInstruction, bytes)
                            } else {
                                AppLogger.d(TAG, "Camera capture returned null bytes.")
                            }
                        }
                    }
                    ContextAction.OCR -> startOcrGeneration(prompt.systemInstruction)
                    ContextAction.ACCESSIBILITY_TEXT -> startAccessibilityTextGeneration(prompt)
                    ContextAction.NONE -> startGeneration(prompt.systemInstruction, directExecute = false)
                }
            }
        }
    }

    // Outer container: when idle, WRAP_CONTENT 54dp. When radial open, expands to container size.
    Box(
        modifier = if (isRadialOpen) {
            Modifier.size(containerWidthDp, containerHeightDp)
        } else {
            Modifier.size(buttonSizeDp)
        }
    ) {
        // Orbital Canvas: Draws Guide Path & Active Glowing Laser Tether
        if (isRadialOpen) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val buttonCenter = Offset(buttonCenterX, buttonCenterY)

                // Draw dashed / dotted orbital track
                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f), 0f)
                val sweepAngles = if (isRightSide) {
                    -105f to -150f
                } else {
                    -75f to 150f
                }
                drawArc(
                    color = Color(0x33A27B5C),
                    startAngle = sweepAngles.first,
                    sweepAngle = sweepAngles.second,
                    useCenter = false,
                    topLeft = Offset(buttonCenterX - orbitRadiusPx, buttonCenterY - orbitRadiusPx),
                    size = androidx.compose.ui.geometry.Size(orbitRadiusPx * 2, orbitRadiusPx * 2),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = pathEffect)
                )

                // Luminous Energy Beam / Laser Tether to Hovered Bubble
                if (hoveredIndex in radialItems.indices) {
                    val targetPos = getActionPosition(hoveredIndex, radialItems.size)

                    // Outer laser glow
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0x8800E5FF), Color(0xCCFFD54F))
                        ),
                        start = buttonCenter,
                        end = targetPos,
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Inner bright laser core
                    drawLine(
                        color = Color.White,
                        start = buttonCenter,
                        end = targetPos,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Node highlight aura
                    drawCircle(
                        color = Color(0x3300E5FF),
                        radius = with(density) { 34.dp.toPx() },
                        center = targetPos
                    )
                }
            }
        }

        // Action Bubbles along the Orbit
        if (isRadialOpen) {
            radialItems.forEachIndexed { index, item ->
                val pos = getActionPosition(index, radialItems.size)
                val isHovered = hoveredIndex == index
                val scale by animateFloatAsState(
                    targetValue = if (isHovered) 1.25f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
                    label = "bubbleScale"
                )

                val itemBubbleSize = 46.dp
                val halfItemPx = with(density) { itemBubbleSize.toPx() / 2 }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pos.x - halfItemPx).roundToInt(),
                                (pos.y - halfItemPx).roundToInt()
                            )
                        }
                        .size(itemBubbleSize)
                        .scale(scale),
                    contentAlignment = Alignment.Center
                ) {
                    when (item) {
                        is RadialActionItem.UndoAction -> {
                            val enabled = hasUndo()
                            Surface(
                                shape = CircleShape,
                                color = if (enabled) Color(0xFFFF7043) else Color(0x66FF7043),
                                shadowElevation = if (isHovered) 12.dp else 4.dp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(
                                        width = if (isHovered) 2.5.dp else 1.5.dp,
                                        color = if (isHovered) Color.White else Color(0xAAFFD54F),
                                        shape = CircleShape
                                    )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Undo,
                                        contentDescription = "Undo Text",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                        is RadialActionItem.PromptAction -> {
                            val prompt = item.prompt
                            val bubbleGradient = when (prompt.contextAction) {
                                ContextAction.SCREENSHOT -> listOf(Color(0xFF0288D1), Color(0xFF01579B))
                                ContextAction.CAMERA -> listOf(Color(0xFFFF8F00), Color(0xFFE65100))
                                ContextAction.OCR -> listOf(Color(0xFF8E24AA), Color(0xFF4A148C))
                                ContextAction.ACCESSIBILITY_TEXT -> listOf(Color(0xFF00897B), Color(0xFF004D40))
                                ContextAction.NONE -> listOf(Color(0xFF5E35B1), Color(0xFF311B92))
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shadow(elevation = if (isHovered) 12.dp else 4.dp, shape = CircleShape)
                                    .background(Brush.linearGradient(bubbleGradient), shape = CircleShape)
                                    .border(
                                        width = if (isHovered) 2.5.dp else 1.dp,
                                        color = if (isHovered) Color(0xFF00E5FF) else Color(0x55FFFFFF),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = prompt.iconEmoji,
                                    fontSize = 20.sp
                                )
                            }
                        }
                    }
                }

                // Hover Tooltip Pill
                if (isHovered) {
                    val tooltipOffsetX = if (isRightSide) {
                        (pos.x - with(density) { 150.dp.toPx() }).roundToInt()
                    } else {
                        (pos.x + with(density) { 28.dp.toPx() }).roundToInt()
                    }
                    val tooltipOffsetY = (pos.y - with(density) { 18.dp.toPx() }).roundToInt()

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xF21C2520),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .offset { IntOffset(tooltipOffsetX, tooltipOffsetY) }
                            .border(1.dp, Color(0x66A27B5C), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val tooltipText = when (item) {
                                is RadialActionItem.UndoAction -> "↩️ Revert Text"
                                is RadialActionItem.PromptAction -> "${item.prompt.iconEmoji} ${item.prompt.title}"
                            }
                            Text(
                                text = tooltipText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Center Floating Action Button (54dp) with Gesture Disambiguation
        Box(
            modifier = Modifier
                .offset {
                    if (isRadialOpen) {
                        IntOffset(buttonOffsetXPx, buttonOffsetYPx)
                    } else {
                        IntOffset.Zero
                    }
                }
                .size(buttonSizeDp)
                .pointerInput(isRightSide, prompts) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val startPos = down.position
                        var hasMovedPastThreshold = false
                        var lastPos = startPos

                        // Coroutine to trigger radial menu after 220ms hold in place
                        val holdJob = scope.launch {
                            delay(220)
                            if (!hasMovedPastThreshold && state == FloatingButtonState.IDLE) {
                                notifyRadialState(true)
                            }
                        }

                        var pointerActive = true
                        while (pointerActive) {
                            val event = awaitPointerEvent()
                            val pointerChange = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (!pointerChange.pressed) {
                                pointerActive = false
                                holdJob.cancel()
                                pointerChange.consume()

                                val elapsed = System.currentTimeMillis() - downTime

                                if (isRadialOpen) {
                                    // Release on Radial Item
                                    if (hoveredIndex in radialItems.indices) {
                                        val selectedItem = radialItems[hoveredIndex]
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        executeRadialItem(selectedItem)
                                    }
                                    notifyRadialState(false)
                                } else if (!hasMovedPastThreshold && elapsed < 200) {
                                    // Quick Tap (< 200ms)
                                    when (state) {
                                        FloatingButtonState.IDLE -> {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            startGeneration("", directExecute = true)
                                        }
                                        FloatingButtonState.GENERATING -> {
                                            activeJob?.cancel()
                                            activeJob = null
                                            state = FloatingButtonState.IDLE
                                            Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                                        }
                                        FloatingButtonState.REVERT_WINDOW -> {
                                            onRevertText()
                                            state = FloatingButtonState.IDLE
                                            Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } else {
                                // Pointer is moving
                                val currentPos = pointerChange.position
                                val deltaTotal = hypot(currentPos.x - startPos.x, currentPos.y - startPos.y)

                                if (!isRadialOpen) {
                                    if (deltaTotal > with(density) { 10.dp.toPx() }) {
                                        hasMovedPastThreshold = true
                                        holdJob.cancel()
                                        val dx = currentPos.x - lastPos.x
                                        val dy = currentPos.y - lastPos.y
                                        onDragDelta(dx, dy)
                                    }
                                } else {
                                    // In Radial Menu Mode: Track Hover Position
                                    pointerChange.consume()
                                    // Translate pointer position to container coordinates
                                    val localX = buttonOffsetXPx + currentPos.x
                                    val localY = buttonOffsetYPx + currentPos.y
                                    touchPointerPos = Offset(localX, localY)

                                    // Hit-test closest action bubble within 44dp hit-box
                                    var closestIndex = -1
                                    var minDistance = with(density) { 46.dp.toPx() }

                                    radialItems.indices.forEach { i ->
                                        val itemPos = getActionPosition(i, radialItems.size)
                                        val dist = hypot(localX - itemPos.x, localY - itemPos.y)
                                        if (dist < minDistance) {
                                            minDistance = dist
                                            closestIndex = i
                                        }
                                    }

                                    if (closestIndex != hoveredIndex) {
                                        hoveredIndex = closestIndex
                                        if (closestIndex != -1) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                }
                                lastPos = currentPos
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Main Button Surface with Holographic Gradient
            Surface(
                shape = CircleShape,
                color = when (state) {
                    FloatingButtonState.GENERATING -> Color(0xFF2C3930)
                    FloatingButtonState.REVERT_WINDOW -> Color(0xFF3F4F44)
                    else -> Color(0xFF1E2822)
                },
                shadowElevation = if (isRadialOpen) 14.dp else 8.dp,
                modifier = Modifier
                    .size(50.dp)
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            listOf(Color(0xFF8A2BE2), Color(0xFF00E5FF), Color(0xFFFF4081))
                        ),
                        shape = CircleShape
                    )
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
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // Rotating Luminous Laser Beam Border during GENERATING state
            if (state == FloatingButtonState.GENERATING) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color(0x33C59B76),
                        radius = size.minDimension / 2 - 2.dp.toPx(),
                        style = Stroke(width = 3.dp.toPx())
                    )
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

            // 5-Second Countdown Ring during REVERT_WINDOW state
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
