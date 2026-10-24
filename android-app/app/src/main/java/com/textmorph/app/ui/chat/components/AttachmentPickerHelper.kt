package com.textmorph.app.ui.chat.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Data representation of an attached image or text/code document.
 */
data class AttachedMediaItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val isImage: Boolean,
    val textContent: String? = null,
    val bitmap: Bitmap? = null
)

/**
 * Handles picking, decoding, and validating multi-image and file attachments.
 * Strictly safeguards native C++/JNI runtimes against unsupported multi-image inputs.
 */
object AttachmentPickerHelper {

    private const val TAG = "AttachmentPickerHelper"

    suspend fun resolveMediaItem(context: Context, uri: Uri): AttachedMediaItem? = withContext(Dispatchers.IO) {
        try {
            val cr = context.contentResolver
            val mimeType = cr.getType(uri) ?: "application/octet-stream"
            var displayName = "Attachment"

            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex) ?: "Attachment"
                    }
                }
            }

            val isImage = mimeType.startsWith("image/")
            if (isImage) {
                cr.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = BitmapFactory.decodeStream(stream, null, opts)
                    return@withContext AttachedMediaItem(
                        uri = uri,
                        name = displayName,
                        mimeType = mimeType,
                        isImage = true,
                        bitmap = bmp
                    )
                }
            } else {
                // Read text/code documents
                cr.openInputStream(uri)?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    val sb = StringBuilder()
                    var line: String? = reader.readLine()
                    var linesCount = 0
                    while (line != null && linesCount < 600) {
                        sb.appendLine(line)
                        linesCount++
                        line = reader.readLine()
                    }
                    val text = sb.toString()
                    return@withContext AttachedMediaItem(
                        uri = uri,
                        name = displayName,
                        mimeType = mimeType,
                        isImage = false,
                        textContent = text
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error resolving attachment from URI: ${e.message}", e)
        }
        null
    }

    /**
     * Verifies attached media against active model capabilities to avoid JNI out-of-memory or C++ asserts.
     */
    fun validateAttachments(
        modelName: String,
        isVisionSupported: Boolean,
        items: List<AttachedMediaItem>
    ): Pair<List<AttachedMediaItem>, String?> {
        val images = items.filter { it.isImage }
        val files = items.filter { !it.isImage }

        if (!isVisionSupported && images.isNotEmpty()) {
            return Pair(
                files,
                "Model '$modelName' is text-only. Removed ${images.size} image attachment(s)."
            )
        }

        val maxAllowedImages = when {
            modelName.contains("gemini", true) -> 5
            modelName.contains("gemma", true) -> 1 // LiteRT-LM Gemma on mobile enforces single-image context
            modelName.contains("llama", true) -> 1
            else -> 1
        }

        if (images.size > maxAllowedImages) {
            val trimmedImages = images.take(maxAllowedImages)
            return Pair(
                trimmedImages + files,
                "Model '$modelName' supports max $maxAllowedImages image(s). Using primary image to avoid C++ JNI crash."
            )
        }

        return Pair(items, null)
    }
}
