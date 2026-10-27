package com.textmorph.app.ui.common

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.textmorph.app.util.AppLogger
import java.io.ByteArrayOutputStream

/**
 * Result data extracted from the system clipboard.
 */
data class ClipboardResult(
    val text: String? = null,
    val imageBytes: ByteArray? = null
)

/**
 * Transparent floating single-frame activity that gains foreground window focus
 * on Android 10+ to reliably read ClipboardManager.primaryClip without being blocked
 * by background privacy restrictions.
 */
class ClipboardCaptureActivity : Activity() {

    private val TAG = "ClipboardCaptureActivity"
    private var hasExtracted = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (!hasExtracted) {
            AppLogger.d(TAG, "Window focus timeout reached, attempting clipboard extraction fallback.")
            extractAndFinish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        handler.postDelayed(timeoutRunnable, 600L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasExtracted) {
            AppLogger.d(TAG, "ClipboardCaptureActivity gained window focus. Reading primaryClip...")
            extractAndFinish()
        }
    }

    private fun extractAndFinish() {
        if (hasExtracted) return
        hasExtracted = true
        handler.removeCallbacks(timeoutRunnable)

        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            var text: String? = null
            var imageBytes: ByteArray? = null

            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                text = try {
                    item.coerceToText(this)?.toString()
                } catch (e: Exception) {
                    item.text?.toString()
                }

                val uri: Uri? = item.uri
                if (uri != null) {
                    val mime = contentResolver.getType(uri) ?: clip.description?.getMimeType(0)
                    if (mime != null && mime.startsWith("image/", ignoreCase = true)) {
                        try {
                            contentResolver.openInputStream(uri)?.use { stream ->
                                val bytes = stream.readBytes()
                                imageBytes = bytes
                                AppLogger.i(TAG, "Read image from clipboard URI: ${bytes.size} bytes")
                            }
                        } catch (imgEx: Exception) {
                            AppLogger.e(TAG, "Failed reading image from clipboard URI $uri: ${imgEx.message}", imgEx)
                        }
                    }
                }
            }

            val result = ClipboardResult(text = text, imageBytes = imageBytes)
            callback?.invoke(result)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error in ClipboardCaptureActivity reading clip: ${e.message}", e)
            callback?.invoke(ClipboardResult(null, null))
        } finally {
            callback = null
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timeoutRunnable)
        if (!hasExtracted) {
            callback?.invoke(ClipboardResult(null, null))
            callback = null
        }
    }

    companion object {
        @Volatile
        private var callback: ((ClipboardResult) -> Unit)? = null

        /**
         * Reads clipboard text and potential images.
         * Attempts fast synchronous read first; if null and running on overlay,
         * launches ClipboardCaptureActivity for guaranteed foreground focus access.
         */
        fun readClipboard(context: Context, onResult: (ClipboardResult) -> Unit) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val item = clip.getItemAt(0)
                    val text = try {
                        item.coerceToText(context)?.toString()
                    } catch (e: Exception) {
                        item.text?.toString()
                    }
                    var imageBytes: ByteArray? = null
                    val uri = item.uri
                    if (uri != null) {
                        val mime = context.contentResolver.getType(uri) ?: clip.description?.getMimeType(0)
                        if (mime != null && mime.startsWith("image/", ignoreCase = true)) {
                            try {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    imageBytes = stream.readBytes()
                                }
                            } catch (e: Exception) {
                                AppLogger.w("ClipboardCaptureHelper", "Synchronous image read error: ${e.message}")
                            }
                        }
                    }
                    if (!text.isNullOrBlank() || imageBytes != null) {
                        onResult(ClipboardResult(text = text, imageBytes = imageBytes))
                        return
                    }
                }
            } catch (syncEx: Exception) {
                AppLogger.d("ClipboardCaptureHelper", "Synchronous read threw (expected on Android 10+ background): ${syncEx.message}")
            }

            // Launch transparent focus-borrowing activity
            callback = onResult
            val intent = Intent(context, ClipboardCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
