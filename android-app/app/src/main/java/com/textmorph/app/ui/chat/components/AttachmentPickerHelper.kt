package com.textmorph.app.ui.chat.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.textmorph.app.data.model.ChatAttachment
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.UUID

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

    /**
     * Persists an attachment to internal app storage (filesDir/chat_attachments)
     * so that it survives app reboots without losing SAF uri access permissions.
     */
    suspend fun persistAttachmentLocally(context: Context, item: AttachedMediaItem): AttachedMediaItem = withContext(Dispatchers.IO) {
        try {
            val attachmentsDir = File(context.filesDir, "chat_attachments").apply { if (!exists()) mkdirs() }
            if (item.isImage && item.bitmap != null) {
                val targetFile = File(attachmentsDir, "attach_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.png")
                FileOutputStream(targetFile).use { out ->
                    item.bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                val localUri = Uri.fromFile(targetFile)
                AppLogger.d(TAG, "Cached chat image to internal storage: ${targetFile.absolutePath}")
                return@withContext item.copy(uri = localUri)
            } else if (!item.isImage && !item.textContent.isNullOrBlank()) {
                val safeName = item.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val targetFile = File(attachmentsDir, "doc_${System.currentTimeMillis()}_$safeName")
                targetFile.writeText(item.textContent)
                val localUri = Uri.fromFile(targetFile)
                return@withContext item.copy(uri = localUri)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed caching attachment to local storage: ${e.message}", e)
        }
        item
    }

    /**
     * Restores an AttachedMediaItem from a persisted ChatAttachment entity.
     */
    fun restoreAttachedMediaItem(context: Context, attachment: ChatAttachment): AttachedMediaItem? {
        val uriStr = attachment.localUri ?: return null
        val uri = Uri.parse(uriStr)
        val isImage = attachment.mimeType.startsWith("image/")

        return if (isImage) {
            val bmp = try {
                if (uri.scheme == "file") {
                    BitmapFactory.decodeFile(uri.path)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed loading restored image bitmap from $uri: ${e.message}")
                null
            }
            AttachedMediaItem(
                uri = uri,
                name = attachment.name,
                mimeType = attachment.mimeType,
                isImage = true,
                bitmap = bmp
            )
        } else {
            AttachedMediaItem(
                uri = uri,
                name = attachment.name,
                mimeType = attachment.mimeType,
                isImage = false,
                textContent = attachment.contentSnippet
            )
        }
    }
}
