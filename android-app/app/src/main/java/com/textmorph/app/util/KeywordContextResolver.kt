package com.textmorph.app.util

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.textmorph.app.ui.common.ClipboardCaptureActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Result of keyword interpolation containing the processed text
 * and any attached multimodal image bytes.
 */
data class ResolvedPromptContext(
    val processedText: String,
    val imageBytes: ByteArray? = null
)

/**
 * Resolves context keywords embedded in user prompts or commands:
 * - [clipboard]: replaced by clipboard text or attaches clipboard image.
 * - [screenshot]: captures current screen and attaches as multimodal image.
 * - [image]: retrieves the most recent photo/image from device storage (MediaStore).
 */
object KeywordContextResolver {

    private const val TAG = "KeywordResolver"

    private val CLIPBOARD_REGEX = Regex("""(?i)\[clipboard\]""")
    private val SCREENSHOT_REGEX = Regex("""(?i)\[screenshot\]""")
    private val IMAGE_REGEX = Regex("""(?i)\[image\]""")

    fun hasKeywords(text: String): Boolean {
        return CLIPBOARD_REGEX.containsMatchIn(text) ||
                SCREENSHOT_REGEX.containsMatchIn(text) ||
                IMAGE_REGEX.containsMatchIn(text)
    }

    /**
     * Resolves all dynamic keywords asynchronously.
     */
    suspend fun resolve(
        context: Context,
        input: String,
        captureScreenshot: (suspend () -> ByteArray?)? = null
    ): ResolvedPromptContext = withContext(Dispatchers.IO) {
        var currentText = input
        var attachedImage: ByteArray? = null

        // 1. Resolve [image] (latest photo from MediaStore)
        if (IMAGE_REGEX.containsMatchIn(currentText)) {
            val latestImgBytes = fetchLastSavedImage(context)
            if (latestImgBytes != null) {
                attachedImage = latestImgBytes
                currentText = currentText.replace(IMAGE_REGEX, "[Attached Recent Image]")
                AppLogger.i(TAG, "Replaced [image] with latest photo (${latestImgBytes.size} bytes)")
            } else {
                currentText = currentText.replace(IMAGE_REGEX, "[Image not found]")
            }
        }

        // 2. Resolve [screenshot]
        if (SCREENSHOT_REGEX.containsMatchIn(currentText)) {
            val screenshotBytes = captureScreenshot?.invoke()
            if (screenshotBytes != null) {
                attachedImage = screenshotBytes
                currentText = currentText.replace(SCREENSHOT_REGEX, "[Screen Context]")
                AppLogger.i(TAG, "Replaced [screenshot] with captured screen (${screenshotBytes.size} bytes)")
            } else {
                currentText = currentText.replace(SCREENSHOT_REGEX, "[Screen capture unavailable]")
            }
        }

        // 3. Resolve [clipboard]
        if (CLIPBOARD_REGEX.containsMatchIn(currentText)) {
            val clipResult = fetchClipboardData(context)
            if (clipResult.imageBytes != null && attachedImage == null) {
                attachedImage = clipResult.imageBytes
                currentText = currentText.replace(CLIPBOARD_REGEX, "[Clipboard Image]")
                AppLogger.i(TAG, "Replaced [clipboard] with image from clipboard (${clipResult.imageBytes.size} bytes)")
            } else if (!clipResult.text.isNullOrBlank()) {
                currentText = currentText.replace(CLIPBOARD_REGEX, clipResult.text)
                AppLogger.i(TAG, "Replaced [clipboard] with text: length=${clipResult.text.length}")
            } else {
                currentText = currentText.replace(CLIPBOARD_REGEX, "[Clipboard empty]")
            }
        }

        ResolvedPromptContext(processedText = currentText.trim(), imageBytes = attachedImage)
    }

    /**
     * Queries MediaStore for the most recently saved image.
     */
    fun fetchLastSavedImage(context: Context): ByteArray? {
        return try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idCol)
                    val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    context.contentResolver.openInputStream(imageUri)?.use { stream ->
                        return stream.readBytes()
                    }
                }
            }
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed retrieving last saved image: ${e.message}", e)
            null
        }
    }

    /**
     * Reads clipboard data with Android 10+ focus fallback.
     */
    suspend fun fetchClipboardData(context: Context): com.textmorph.app.ui.common.ClipboardResult =
        suspendCancellableCoroutine { continuation ->
            ClipboardCaptureActivity.readClipboard(context) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
}
