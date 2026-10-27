package com.textmorph.app.service.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Handles smooth target-seeking window animations across the screen coordinates.
 * Coordinates circular thinking indicators and tap scale feedback.
 */
class OverlayCoordinateAnimator(
    private val windowManager: WindowManager,
    private val overlayView: () -> View?,
    private val windowParams: () -> WindowManager.LayoutParams?
) {

    private val TAG = "OverlayCoordAnimator"
    private var currentAnimator: ValueAnimator? = null

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    fun setThinking(thinking: Boolean) {
        _isThinking.value = thinking
        AppLogger.d(TAG, "Thinking progress ring state set to: $thinking")
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun runOnMainThread(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun animateTo(
        targetX: Float,
        targetY: Float,
        durationMs: Long = 350L,
        onArrived: (() -> Unit)? = null
    ) {
        runOnMainThread {
            val view = overlayView() ?: return@runOnMainThread
            val params = windowParams() ?: return@runOnMainThread

            currentAnimator?.cancel()

            val startX = params.x
            val startY = params.y
            val endX = (targetX - (params.width / 2)).roundToInt()
            val endY = (targetY - (params.height / 2)).roundToInt()

            AppLogger.d(TAG, "Animating overlay window from ($startX, $startY) to ($endX, $endY)")

            currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    params.x = (startX + (endX - startX) * fraction).roundToInt()
                    params.y = (startY + (endY - startY) * fraction).roundToInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        AppLogger.d(TAG, "Update layout animation frame error: ${e.message}")
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        currentAnimator = null
                        onArrived?.invoke()
                    }
                })
                start()
            }
        }
    }

    fun cancel() {
        runOnMainThread {
            currentAnimator?.cancel()
            currentAnimator = null
            _isThinking.value = false
        }
    }
}
