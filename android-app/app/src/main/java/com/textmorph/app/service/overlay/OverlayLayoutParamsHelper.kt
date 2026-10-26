package com.textmorph.app.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Utility for calculating window layout parameters across TextMorph overlay display states.
 */
object OverlayLayoutParamsHelper {

    data class ScreenBounds(
        val screenWidth: Int,
        val screenHeight: Int,
        val density: Float,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    fun calculateBounds(metrics: DisplayMetrics, pillSizeDp: Int = 36, marginDp: Int = 6): ScreenBounds {
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = metrics.density
        val pillSizePx = (pillSizeDp * density).roundToInt()
        val marginPx = (marginDp * density).roundToInt()

        val minX = marginPx
        val maxX = (screenWidth - pillSizePx - marginPx).coerceAtLeast(minX)
        val minY = (40 * density).roundToInt()
        val maxY = (screenHeight - pillSizePx - (40 * density).roundToInt()).coerceAtLeast(minY)

        return ScreenBounds(screenWidth, screenHeight, density, minX, maxX, minY, maxY)
    }

    fun resolveOverlayWindowType(context: Context): Int {
        return if (Settings.canDrawOverlays(context)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
    }

    fun createInitialParams(initialX: Int, initialY: Int, windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
    }

    fun applyCollapsedBubble(params: WindowManager.LayoutParams, x: Int, y: Int) {
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        params.x = x
        params.y = y
    }

    fun applyExpandedToolbar(params: WindowManager.LayoutParams, bounds: ScreenBounds, userY: Int?) {
        val marginPx = (8 * bounds.density).roundToInt()
        params.width = bounds.screenWidth - (marginPx * 2)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        params.x = marginPx
        params.y = (userY ?: (bounds.screenHeight / 2)).coerceIn(bounds.minY, bounds.maxY)
    }

    fun applyListeningRequest(params: WindowManager.LayoutParams, bounds: ScreenBounds, anchorX: Int, anchorY: Int) {
        val bubbleSizePx = (56 * bounds.density).roundToInt()
        params.width = bubbleSizePx
        params.height = bubbleSizePx
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        params.x = (anchorX - bubbleSizePx / 2).coerceIn(bounds.minX, bounds.maxX)
        params.y = (anchorY - bubbleSizePx / 2).coerceIn(bounds.minY, bounds.maxY)
    }

    fun applyAutomatingAction(params: WindowManager.LayoutParams, bounds: ScreenBounds, targetX: Int, targetY: Int) {
        val bubbleSizePx = (56 * bounds.density).roundToInt()
        params.width = bubbleSizePx
        params.height = bubbleSizePx
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        params.x = (targetX - bubbleSizePx / 2).coerceIn(bounds.minX, bounds.maxX)
        params.y = (targetY - bubbleSizePx / 2).coerceIn(bounds.minY, bounds.maxY)
    }
}
