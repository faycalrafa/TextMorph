package com.textmorph.app.agent.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.textmorph.app.util.AppLogger

/**
 * Thread-safe, throttled notification helper that displays brief on-screen step toasts
 * so the user easily sees what the autonomous agent is currently attempting.
 */
object AgentToastNotifier {

    private const val TAG = "AgentToastNotifier"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastToastTime = 0L

    @Volatile
    private var lastToastMessage = ""

    /**
     * Posts a brief Toast notifying the user of the active milestone or step.
     * Throttled with a 1500ms debounce to prevent visual spamming on screen.
     */
    fun showStepToast(context: Context, stepIndex: Int, totalSteps: Int? = null, stepTitle: String) {
        val now = System.currentTimeMillis()
        val message = if (totalSteps != null && totalSteps > 0) {
            "Step $stepIndex/$totalSteps: $stepTitle"
        } else {
            "Step $stepIndex: $stepTitle"
        }

        // Throttle duplicate or excessively rapid toasts
        if (message == lastToastMessage && now - lastToastTime < 3000L) {
            return
        }
        if (now - lastToastTime < 1400L) {
            return
        }

        lastToastTime = now
        lastToastMessage = message

        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                AppLogger.d(TAG, "Step toast displayed: '$message'")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed displaying step toast: ${e.message}")
            }
        }
    }

    /**
     * Posts an instant notification toast (e.g. task verified complete or interrupted).
     */
    fun showStatusToast(context: Context, statusMessage: String) {
        val now = System.currentTimeMillis()
        if (statusMessage == lastToastMessage && now - lastToastTime < 2000L) {
            return
        }
        lastToastTime = now
        lastToastMessage = statusMessage

        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, statusMessage, Toast.LENGTH_SHORT).show()
                AppLogger.d(TAG, "Status toast displayed: '$statusMessage'")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed displaying status toast: ${e.message}")
            }
        }
    }

    fun reset() {
        lastToastTime = 0L
        lastToastMessage = ""
    }
}
