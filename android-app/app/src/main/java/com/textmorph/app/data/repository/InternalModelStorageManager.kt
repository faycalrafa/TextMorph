package com.textmorph.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages the app's private internal storage for on-device AI models (filesDir/models/).
 * Eliminates direct loading from external Download directories and SAF file descriptors,
 * preventing SELinux permission revocations, stale descriptors, and process crashes.
 */
class InternalModelStorageManager(private val context: Context) {

    private val TAG = "InternalModelStorage"

    val modelsDir: File
        get() = File(context.filesDir, "models").apply {
            if (!exists()) mkdirs()
        }

    /**
     * Lists all model files (.litertlm and .gguf) present in internal app private storage.
     */
    fun listInternalModelFiles(): List<File> {
        val dir = modelsDir
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { file ->
            file.isFile && (file.name.endsWith(".litertlm", ignoreCase = true) ||
                    (file.name.endsWith(".gguf", ignoreCase = true) && !file.name.startsWith("mmproj", ignoreCase = true)))
        }?.toList() ?: emptyList()
    }

    /**
     * Lists mmproj vision projectors stored internally.
     */
    fun listInternalMmprojFiles(): List<File> {
        val dir = modelsDir
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("mmproj", ignoreCase = true) && file.name.endsWith(".gguf", ignoreCase = true)
        }?.toList() ?: emptyList()
    }

    /**
     * Copies a model from an external content URI (e.g., SAF picker) into app private storage.
     */
    suspend fun copyFromUri(
        uri: Uri,
        targetFileName: String,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        val destFile = File(modelsDir, targetFileName)
        val tempFile = File(modelsDir, "$targetFileName.tmp_${System.currentTimeMillis()}")

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var totalBytes = -1L
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx != -1) totalBytes = cursor.getLong(sizeIdx)
                    }
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Could not determine total size for $uri: ${t.message}")
            }

            inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Failed to open input stream for $uri"))
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(256 * 1024) // 256KB buffer for fast multi-GB copy
            var bytesCopied = 0L
            var read: Int

            AppLogger.i(TAG, "Copying model from $uri to internal storage: ${destFile.name} (expected bytes: $totalBytes)")
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                bytesCopied += read
                onProgress(bytesCopied, totalBytes)
            }
            outputStream.flush()
            outputStream.fd.sync()

            // Close before renaming
            try { outputStream.close() } catch (t: Throwable) { /* ignore */ }
            outputStream = null
            try { inputStream.close() } catch (t: Throwable) { /* ignore */ }
            inputStream = null

            if (destFile.exists()) {
                destFile.delete()
            }
            val renamed = tempFile.renameTo(destFile)
            if (!renamed) {
                // Fallback copy if renameTo fails across mounts
                FileInputStream(tempFile).use { `in` ->
                    FileOutputStream(destFile).use { out ->
                        `in`.copyTo(out)
                    }
                }
                tempFile.delete()
            }

            AppLogger.i(TAG, "Successfully copied ${destFile.name} (${destFile.length()} bytes) to app internal storage.")
            Result.success(destFile)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed copying model to internal storage: ${t.message}", t)
            if (tempFile.exists()) tempFile.delete()
            Result.failure(t)
        } finally {
            try { inputStream?.close() } catch (t: Throwable) { /* ignore */ }
            try { outputStream?.close() } catch (t: Throwable) { /* ignore */ }
        }
    }

    /**
     * Deletes a model from internal storage.
     */
    suspend fun deleteInternalModel(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(modelsDir, fileName)
            val deleted = if (file.exists()) file.delete() else true
            AppLogger.i(TAG, "Deleted internal model $fileName: success=$deleted")
            deleted
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed deleting internal model $fileName: ${t.message}", t)
            false
        }
    }

    /**
     * Returns true if a model with the specified file name exists internally.
     */
    fun hasModel(fileName: String): Boolean {
        return File(modelsDir, fileName).exists()
    }
}
