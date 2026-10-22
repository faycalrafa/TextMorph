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
        // When expanded, let Compose handle chip scrolling and taps
        if (isExpandedProvider()) {
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                isDragging = false
                // Do not intercept ACTION_DOWN immediately so child view hierarchy is initialized,
                // but we record the raw physical touch point.
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dist > touchSlop) {
                    isDragging = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onDragStart()
                    AppLogger.d(TAG, "Touch slop exceeded ($dist > $touchSlop): Intercepting touch for instant swipe")
                    // Intercept gesture from child views! Android will deliver ACTION_CANCEL to children
                    // and route all subsequent moves to onTouchEvent.
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
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
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                if (!isDragging && dist > touchSlop) {
                    isDragging = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onDragStart()
                }

                if (isDragging) {
                    onDragMove(dx, dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    onDragEnd()
                    return true
                } else {
                    // Touch released without exceeding slop -> clean, instant tap!
                    onSingleTap()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDragEnd()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
