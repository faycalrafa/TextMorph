package com.textmorph.app.ui.chat.components

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * ImageDownloadHelper downloads remote image assets and exports them
 * into the device's public Pictures/TextMorph directory with user feedback.
 */
object ImageDownloadHelper {

    private const val TAG = "ImageDownloadHelper"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Downloads an image URL and saves it to the public Pictures/TextMorph directory.
     */
    suspend fun downloadAndSaveImage(
        context: Context,
        imageUrl: String,
        caption: String? = null
    ) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Starting image download: $imageUrl (caption='$caption')")
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Downloading image to Pictures/TextMorph…", Toast.LENGTH_SHORT).show()
        }

        try {
            val request = Request.Builder().url(imageUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "Download HTTP error: ${response.code}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Image download failed (HTTP ${response.code})", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }

                val body = response.body ?: run {
                    AppLogger.w(TAG, "Empty image response body")
                    return@withContext
                }

                val ext = resolveExtension(imageUrl, response.header("Content-Type"))
                val baseName = sanitizeFileName(caption ?: "image")
                val fileName = "TextMorph_${baseName}_${System.currentTimeMillis() % 100000}.$ext"

                val savedUri = saveToPictures(context, body.byteStream(), fileName, ext)
                if (savedUri != null) {
                    AppLogger.i(TAG, "Successfully exported image to: $savedUri")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Saved image to Pictures/TextMorph", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AppLogger.w(TAG, "Failed to write image stream to storage")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Could not save image to storage", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Exception during image download: ${t.message}", t)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Download failed: ${t.message ?: "Network error"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToPictures(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        ext: String
    ): Uri? {
        val mimeType = if (ext == "png") "image/png" else if (ext == "webp") "image/webp" else "image/jpeg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TextMorph")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    copyStream(inputStream, outputStream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                uri
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Error writing to MediaStore: ${t.message}", t)
                resolver.delete(uri, null, null)
                null
            }
        } else {
            try {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val targetDir = File(picturesDir, "TextMorph").apply { mkdirs() }
                val targetFile = File(targetDir, fileName)
                FileOutputStream(targetFile).use { out ->
                    copyStream(inputStream, out)
                }
                Uri.fromFile(targetFile)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Error writing to legacy storage: ${t.message}", t)
                null
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        output.flush()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30).trim('_')
    }

    private fun resolveExtension(url: String, contentType: String?): String {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".png") || contentType?.contains("png") == true -> "png"
            lower.endsWith(".webp") || contentType?.contains("webp") == true -> "webp"
            lower.endsWith(".gif") || contentType?.contains("gif") == true -> "gif"
            else -> "jpg"
        }
    }
}
