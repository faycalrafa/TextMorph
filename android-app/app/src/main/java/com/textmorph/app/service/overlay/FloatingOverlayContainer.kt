package com.textmorph.app.service.overlay

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.textmorph.app.util.AppLogger
import kotlin.math.hypot

/**
 * Dedicated ViewGroup container that wraps ComposeView and intercepts touch gestures
 * before child views (including AndroidComposeView) can consume them.
 *
 * Guarantees instant swipe-to-drag and instant tap response without pointer conflicts.
 */
class FloatingOverlayContainer(
    context: Context,
    private val isExpandedProvider: () -> Boolean,
    private val onDragStart: () -> Unit,
    private val onDragMove: (deltaX: Float, deltaY: Float) -> Unit,
    private val onDragEnd: () -> Unit,
    private val onSingleTap: () -> Unit,
    private val onOutsideTap: () -> Unit
) : FrameLayout(context) {

    private val TAG = "FloatingOverlayContainer"
    private var downRawX = 0f
    private var downRawY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.coerceAtLeast(16)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // When expanded, let Compose handle horizontal chip scrolling and button taps
        if (isExpandedProvider()) {
            return false
        }

        // When collapsed (36dp edge pill), intercept ALL touches so this container directly
        // handles instant swipe dragging and instant taps without child pointer conflicts.
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            AppLogger.d(TAG, "ACTION_OUTSIDE received on overlay container")
            onOutsideTap()
            return true
        }

        if (isExpandedProvider()) {
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                isDragging = false
                AppLogger.d(TAG, "Touch DOWN on collapsed companion at ($downRawX, $downRawY)")
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                if (!isDragging && dist > touchSlop) {
                    isDragging = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    AppLogger.d(TAG, "Touch slop exceeded ($dist > $touchSlop): Starting instant swipe drag")
                    onDragStart()
                }

                if (isDragging) {
                    onDragMove(dx, dy)
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    AppLogger.d(TAG, "Drag released on ACTION_UP, completing snap")
                    onDragEnd()
                    return true
                } else {
                    AppLogger.i(TAG, "Clean tap detected on collapsed companion (dist < touchSlop)")
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSingleTap()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    AppLogger.d(TAG, "Drag canceled on ACTION_CANCEL")
                    onDragEnd()
                    return true
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
