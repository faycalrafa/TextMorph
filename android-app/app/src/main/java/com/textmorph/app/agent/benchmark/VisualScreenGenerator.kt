package com.textmorph.app.agent.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.textmorph.app.util.AppLogger
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Generates synthetic mobile screens with non-overlapping numbered buttons
 * and renders them onto an Android Canvas for multimodal vision benchmarking.
 */
object VisualScreenGenerator {

    private const val TAG = "VisualScreenGenerator"

    const val DEFAULT_WIDTH = 720
    const val DEFAULT_HEIGHT = 1280
    const val DEFAULT_BUTTON_RADIUS = 48
    const val DEFAULT_MARGIN = 64
    const val DEFAULT_BUTTON_COUNT = 6

    /**
     * Generates a list of [VisualButtonTarget] with collision avoidance.
     * Guaranteed non-overlapping placement within the specified bounds.
     */
    fun generateButtonPositions(
        screenWidth: Int = DEFAULT_WIDTH,
        screenHeight: Int = DEFAULT_HEIGHT,
        buttonRadius: Int = DEFAULT_BUTTON_RADIUS,
        count: Int = DEFAULT_BUTTON_COUNT,
        margin: Int = DEFAULT_MARGIN,
        random: Random = Random.Default
    ): List<VisualButtonTarget> {
        val targets = mutableListOf<VisualButtonTarget>()
        val minDistance = (buttonRadius * 2) + 28
        val minDistanceSq = minDistance * minDistance

        val minX = margin + buttonRadius
        val maxX = screenWidth - margin - buttonRadius
        val minY = margin + buttonRadius
        val maxY = screenHeight - margin - buttonRadius

        for (i in 1..count) {
            var placed = false
            var attempts = 0
            var candidateX = minX
            var candidateY = minY

            while (!placed && attempts < 250) {
                attempts++
                candidateX = random.nextInt(minX, maxX + 1)
                candidateY = random.nextInt(minY, maxY + 1)

                val collides = targets.any { existing ->
                    val dx = existing.centerX - candidateX
                    val dy = existing.centerY - candidateY
                    (dx * dx + dy * dy) < minDistanceSq
                }

                if (!collides) {
                    placed = true
                }
            }

            // Fallback deterministic fallback if random attempts fail (extremely rare)
            if (!placed) {
                AppLogger.w(TAG, "Placement attempt cap reached for button $i. Using deterministic fallback slot.")
                val row = (i - 1) / 2
                val col = (i - 1) % 2
                candidateX = minX + (col * (maxX - minX))
                candidateY = minY + (row * ((maxY - minY) / 3))
            }

            targets.add(
                VisualButtonTarget(
                    number = i,
                    centerX = candidateX,
                    centerY = candidateY,
                    radius = buttonRadius
                )
            )
        }

        return targets
    }

    /**
     * Renders the button targets onto an Android Canvas Bitmap.
     */
    fun renderCanvasBitmap(
        buttons: List<VisualButtonTarget>,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Slate Background (#0B1020)
        canvas.drawColor(Color.rgb(11, 16, 32))

        // 2. Subtle Header and Footer Guide Lines
        val guidePaint = Paint().apply {
            color = Color.rgb(31, 41, 55)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(8f, 8f, (width - 8).toFloat(), (height - 8).toFloat(), guidePaint)

        // 3. Header title for visual clarity
        val titlePaint = Paint().apply {
            color = Color.rgb(148, 163, 184) // Slate 400
            textSize = 28f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("TextMorph Visual Agent Test Screen", (width / 2).toFloat(), 48f, titlePaint)

        // 4. Button Paints
        val fillPaint = Paint().apply {
            color = Color.rgb(37, 99, 235) // Primary Blue (#2563EB)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val strokePaint = Paint().apply {
            color = Color.rgb(96, 165, 250) // Accent Ring (#60A5FA)
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 44f
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val textBounds = Rect()

        // 5. Draw each button
        for (button in buttons) {
            val cx = button.centerX.toFloat()
            val cy = button.centerY.toFloat()
            val r = button.radius.toFloat()

            // Fill & Border
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, strokePaint)

            // Center Text
            val label = button.number.toString()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val textY = cy - textBounds.exactCenterY()
            canvas.drawText(label, cx, textY, textPaint)
        }

        return bitmap
    }

    /**
     * Renders buttons to a PNG compressed byte array for multimodal inference.
     */
    fun renderCanvasToPng(
        buttons: List<VisualButtonTarget>,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ): ByteArray {
        val bitmap = renderCanvasBitmap(buttons, width, height)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }
}
