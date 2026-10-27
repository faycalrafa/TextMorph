package com.textmorph.app.ui.chat.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.gif.GifEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages export and sharing of HTML artifacts:
 * 1. PNG Snapshot Share
 * 2. Raw HTML Code Share
 * 3. 10-Second 24fps Animated GIF Recording and Share
 */
object HtmlArtifactShareHelper {

    private const val TAG = "ArtifactShareHelper"

    fun shareHtmlCode(context: Context, htmlContent: String) {
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, htmlContent)
                putExtra(Intent.EXTRA_TITLE, "HTML Code")
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Share HTML Code")
            context.startActivity(shareIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed sharing HTML code: ${e.message}", e)
            Toast.makeText(context, "Could not share HTML code", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareAsImage(context: Context, webView: WebView) {
        try {
            val width = webView.width
            val height = webView.height
            if (width <= 0 || height <= 0) {
                Toast.makeText(context, "View not ready for capture", Toast.LENGTH_SHORT).show()
                return
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)

            val dir = File(context.cacheDir, "shared_artifacts").apply { if (!exists()) mkdirs() }
            val file = File(dir, "artifact_snapshot_${System.currentTimeMillis()}.png")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, "Interactive View Snapshot")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Artifact Snapshot"))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed capturing snapshot image: ${e.message}", e)
            Toast.makeText(context, "Failed generating snapshot", Toast.LENGTH_SHORT).show()
        }
    }

    fun recordAndShareGif(
        context: Context,
        webView: WebView,
        scope: CoroutineScope,
        onProgress: (Float, String) -> Unit,
        onStateChange: (Boolean) -> Unit
    ): Job {
        return scope.launch(Dispatchers.Main) {
            onStateChange(true)
            val dir = File(context.cacheDir, "shared_artifacts").apply { if (!exists()) mkdirs() }
            val gifFile = File(dir, "artifact_animation_${System.currentTimeMillis()}.gif")

            val encoder = GifEncoder()
            encoder.setFrameRate(24f) // 24 frames per second
            encoder.setRepeat(0)      // Infinite loop

            val fos = FileOutputStream(gifFile)
            encoder.start(fos)

            val totalSeconds = 10f
            val fps = 24
            val totalFrames = (totalSeconds * fps).toInt() // 240 frames
            val frameDelayMs = 1000L / fps // ~41.6ms

            val originalW = webView.width.coerceAtLeast(1)
            val originalH = webView.height.coerceAtLeast(1)
            // Scale to 480px width to keep encoding super fast and responsive on mobile
            val targetW = 480
            val targetH = ((originalH.toFloat() / originalW.toFloat()) * targetW).toInt().coerceAtLeast(1)
            val scaleFactor = targetW.toFloat() / originalW.toFloat()

            try {
                for (frameIndex in 0 until totalFrames) {
                    if (!isActive) break

                    val frameBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(frameBitmap)
                    canvas.scale(scaleFactor, scaleFactor)
                    webView.draw(canvas)

                    withContext(Dispatchers.IO) {
                        encoder.addFrame(frameBitmap)
                    }
                    frameBitmap.recycle()

                    val elapsedSeconds = (frameIndex + 1).toFloat() / fps
                    val progressFraction = (frameIndex + 1).toFloat() / totalFrames
                    val statusText = "Recording: ${String.format("%.1f", elapsedSeconds)}s / 10s"
                    onProgress(progressFraction, statusText)

                    delay(frameDelayMs)
                }

                withContext(Dispatchers.IO) {
                    encoder.finish()
                    fos.close()
                }

                onStateChange(false)
                AppLogger.i(TAG, "GIF recording completed successfully: ${gifFile.length()} bytes")

                val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", gifFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/gif"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, "Interactive View GIF")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Animation as GIF"))
            } catch (e: Exception) {
                AppLogger.e(TAG, "GIF recording failed: ${e.message}", e)
                try { fos.close() } catch (_: Exception) {}
                onStateChange(false)
                Toast.makeText(context, "GIF recording cancelled or failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
