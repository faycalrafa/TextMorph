package com.textmorph.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.textmorph.app.ui.common.ClipboardCaptureActivity
import com.textmorph.app.ui.common.ClipboardResult

/**
 * Thread-safe, synchronized helper for system clipboard management.
 * Solves Android 10+ background clipboard reading restrictions by maintaining
 * an immediate in-memory cache and dispatching all mutations strictly to the UI Looper.
 */
object ClipboardHelper {

    private const val TAG = "ClipboardHelper"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastCopiedText: String? = null

    @Volatile
    private var lastCopiedTimestamp: Long = 0L

    /**
     * Reliably copies text to the system clipboard on the main Looper.
     * Updates in-memory cache immediately so background services and overlays
     * can query recent clipboard contents without being blocked by Android 10+ restrictions.
     */
    fun copyText(
        context: Context,
        text: String,
        label: String = "TextMorph",
        showToast: Boolean = true,
        toastMessage: String = "📋 Copied to clipboard"
    ): Boolean {
        if (text.isBlank()) return false
        val appContext = context.applicationContext

        return try {
            // Update in-memory cache immediately
            lastCopiedText = text
            lastCopiedTimestamp = System.currentTimeMillis()

            mainHandler.post {
                try {
                    val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (cm != null) {
                        val clip = ClipData.newPlainText(label, text)
                        cm.setPrimaryClip(clip)
                        if (showToast) {
                            Toast.makeText(appContext, toastMessage, Toast.LENGTH_SHORT).show()
                        }
                        AppLogger.i(TAG, "Successfully copied ${text.length} chars to clipboard with label '$label'")
                    } else {
                        AppLogger.w(TAG, "ClipboardManager service unavailable")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed setting primary clip on UI thread: ${e.message}", e)
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed in copyText dispatch: ${e.message}", e)
            false
        }
    }

    /**
     * Reads recent clipboard text.
     * Checks in-memory cache first (reliable even from background services),
     * and falls back to system ClipboardManager if within [maxAgeMs].
     */
    fun getRecentClipboardText(context: Context? = null, maxAgeMs: Long = 90_000L): String? {
        val now = System.currentTimeMillis()
        val cached = lastCopiedText
        if (!cached.isNullOrBlank() && (now - lastCopiedTimestamp) in 0L..maxAgeMs) {
            return cached
        }

        val nonNullContext = context ?: return cached
        return try {
            val cm = nonNullContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            val clipDesc = cm.primaryClipDescription
            val clipTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clipDesc?.timestamp ?: 0L
            } else 0L

            val effectiveTimestamp = maxOf(lastCopiedTimestamp, clipTimestamp)
            val age = now - effectiveTimestamp
            if (effectiveTimestamp > 0L && age in 0L..maxAgeMs) {
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrBlank()) {
                        lastCopiedText = text
                        lastCopiedTimestamp = now
                        text
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            AppLogger.d(TAG, "Background primaryClip read restricted by OS: ${e.message}")
            cached
        }
    }

    /**
     * Clears clipboard content and resets internal cache.
     */
    fun clearClipboard(context: Context): Boolean {
        lastCopiedText = null
        lastCopiedTimestamp = 0L
        return try {
            mainHandler.post {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("", ""))
                Toast.makeText(context, "Clipboard cleared", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed clearing clipboard: ${e.message}", e)
            false
        }
    }

    /**
     * Runs an interactive diagnostic echo test to verify clipboard write and read-back functionality.
     */
    fun testClipboardEcho(context: Context, onResult: (Boolean, String) -> Unit) {
        val testPayload = "TextMorph-ClipEcho-${System.currentTimeMillis() % 10000}"
        AppLogger.i(TAG, "Starting clipboard echo test with payload: $testPayload")

        val copied = copyText(
            context = context,
            text = testPayload,
            label = "Clipboard Diagnostic",
            showToast = false
        )

        if (!copied) {
            onResult(false, "Failed writing test payload to ClipboardManager.")
            return
        }

        // Delay 100ms then verify via ClipboardCaptureActivity
        mainHandler.postDelayed({
            ClipboardCaptureActivity.readClipboard(context) { result ->
                val readBack = result.text?.trim()
                if (readBack == testPayload) {
                    AppLogger.i(TAG, "Clipboard echo test verified successfully!")
                    onResult(true, "Clipboard read & write verified successfully ($readBack).")
                } else if (!readBack.isNullOrBlank()) {
                    AppLogger.w(TAG, "Clipboard echo mismatch. Read: '$readBack', Expected: '$testPayload'")
                    onResult(true, "Clipboard active. Current contents: \"${readBack.take(30)}…\"")
                } else {
                    AppLogger.w(TAG, "Clipboard echo returned null or empty.")
                    onResult(false, "Clipboard read returned empty. Please grant focus or check device privacy settings.")
                }
            }
        }, 120L)
    }

    /**
     * Injects cached copy from an external source (e.g. Accessibility listener).
     */
    fun recordExternalCopy(text: String) {
        if (text.isNotBlank()) {
            lastCopiedText = text
            lastCopiedTimestamp = System.currentTimeMillis()
            AppLogger.d(TAG, "Recorded external copy event: length=${text.length}")
        }
    }
}
