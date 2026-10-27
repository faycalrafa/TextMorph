package com.textmorph.app.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.textmorph.app.agent.coordinator.TaskExecutionCoordinator
import com.textmorph.app.service.overlay.components.AgentInteractionOverlayContent
import com.textmorph.app.theme.TextMorphTheme
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages the non-touchable, full-screen transparent interaction overlay.
 * Renders the physical side screen edge pulse and animated finger clicking cues at target coordinates.
 */
class AgentInteractionOverlayManager(
    private val service: TextMorphAccessibilityService
) {
    private val TAG = "AgentOverlayMgr"
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var composeView: ComposeView? = null
    private var serviceLifecycleOwner: ServiceLifecycleOwner? = null
    private var isAttached = false

    private val tapTargetState = mutableStateOf<Pair<Float, Float>?>(null)
    private var tapDismissJob: Job? = null

    init {
        scope.launch {
            TaskExecutionCoordinator.state.collect { taskState ->
                if (taskState.isRunning) {
                    attachOverlay()
                } else if (tapTargetState.value == null) {
                    detachOverlay()
                }
            }
        }
    }

    /**
     * Triggers a finger click visual feedback at screen coordinates (x, y).
     */
    fun showTap(x: Float, y: Float) {
        service.mainHandler.post {
            AppLogger.d(TAG, "Showing precision click trace at ($x, $y)")
            attachOverlay()
            tapTargetState.value = Pair(x, y)
            tapDismissJob?.cancel()
            tapDismissJob = scope.launch {
                delay(2100L)
                tapTargetState.value = null
                if (!TaskExecutionCoordinator.state.value.isRunning) {
                    detachOverlay()
                }
            }
        }
    }

    /**
     * Toggles visibility of the interaction overlay view (e.g. during clean screenshots).
     */
    fun setOverlayVisible(visible: Boolean) {
        service.mainHandler.post {
            try {
                composeView?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error toggling interaction overlay visibility: ${e.message}", e)
            }
        }
    }

    private fun attachOverlay() {
        if (isAttached || composeView != null) return

        try {
            serviceLifecycleOwner = ServiceLifecycleOwner()

            val windowType = OverlayLayoutParamsHelper.resolveOverlayWindowType(service)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            composeView = ComposeView(service).apply {
                setViewTreeLifecycleOwner(serviceLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
                setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

                setContent {
                    val taskState by TaskExecutionCoordinator.state.collectAsState()
                    val tapTarget = tapTargetState.value

                    TextMorphTheme {
                        AgentInteractionOverlayContent(
                            isTaskRunning = taskState.isRunning,
                            tapTarget = tapTarget
                        )
                    }
                }
            }

            windowManager.addView(composeView, params)
            isAttached = true
            AppLogger.d(TAG, "Agent interaction full-screen overlay attached successfully.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed attaching agent interaction overlay: ${e.message}", e)
        }
    }

    private fun detachOverlay() {
        if (!isAttached || composeView == null) return

        try {
            windowManager.removeView(composeView)
            composeView = null
            serviceLifecycleOwner?.destroy()
            serviceLifecycleOwner = null
            isAttached = false
            AppLogger.d(TAG, "Agent interaction overlay detached.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed detaching agent interaction overlay: ${e.message}", e)
        }
    }

    fun onDestroy() {
        tapDismissJob?.cancel()
        detachOverlay()
    }
}
