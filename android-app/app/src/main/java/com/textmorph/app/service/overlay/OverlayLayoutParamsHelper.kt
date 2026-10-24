package com.textmorph.app.service.overlay

import android.graphics.PixelFormat
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

    fun createInitialParams(initialX: Int, initialY: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        params.x = x
        params.y = y
    }

    fun applyExpandedToolbar(params: WindowManager.LayoutParams, bounds: ScreenBounds, userY: Int?) {
        val marginPx = (8 * bounds.density).roundToInt()
        params.width = bounds.screenWidth - (marginPx * 2)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        params.x = marginPx
        params.y = (userY ?: (bounds.screenHeight / 2)).coerceIn(bounds.minY, bounds.maxY)
    }

    fun applyExpandedChat(
        params: WindowManager.LayoutParams,
        bounds: ScreenBounds,
        isInputFocused: Boolean
    ) {
        val marginPx = (12 * bounds.density).roundToInt()
        params.width = bounds.screenWidth - (marginPx * 2)
        params.height = (bounds.screenHeight * 0.65f).roundToInt().coerceAtLeast((420 * bounds.density).roundToInt())
        params.x = marginPx
        params.y = (32 * bounds.density).roundToInt()

        if (isInputFocused) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    fun applyAutomatingAction(params: WindowManager.LayoutParams, bounds: ScreenBounds, targetX: Int, targetY: Int) {
        val bubbleSizePx = (56 * bounds.density).roundToInt()
        params.width = bubbleSizePx
        params.height = bubbleSizePx
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.x = (targetX - bubbleSizePx / 2).coerceIn(bounds.minX, bounds.maxX)
        params.y = (targetY - bubbleSizePx / 2).coerceIn(bounds.minY, bounds.maxY)
    }
}
