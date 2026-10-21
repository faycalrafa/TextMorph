package com.textmorph.app.worker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.textmorph.app.MainActivity
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "DownloadWorker"
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notificationId: Int = params.id.hashCode()

    companion object {
        const val CHANNEL_ID = "textmorph_model_download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SAVE_TO_DOWNLOADS = "save_to_downloads"

        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_RECEIVED_BYTES = "received_bytes"
        const val KEY_DOWNLOAD_RATE = "download_rate"
        const val KEY_REMAINING_MS = "remaining_ms"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_OUTPUT_PATH = "output_path"

        private const val TMP_EXTENSION = "tmp"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background downloading for on-device AI models"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: "model"
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "AI Model"
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
        val expectedTotalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val saveToDownloads = inputData.getBoolean(KEY_SAVE_TO_DOWNLOADS, false)

        if (downloadUrl.isNullOrBlank()) {
            AppLogger.e(TAG, "DownloadWorker aborted: downloadUrl is null or empty for $modelName")
            return@withContext Result.failure(
                Data.Builder().putString(KEY_ERROR_MESSAGE, "Download URL not provided.").build()
            )
        }

        AppLogger.i(TAG, "Starting download: modelName=$modelName, url=$downloadUrl, saveToDownloads=$saveToDownloads")

        // Target directory: Downloads folder if requested and accessible, otherwise internal app storage
        val targetDir: File = if (saveToDownloads && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            File(applicationContext.filesDir, "models").apply { if (!exists()) mkdirs() }
        }

        val targetFileName = if (modelName.endsWith(".litertlm", ignoreCase = true)) modelName else "$modelName.litertlm"
        val finalFile = File(targetDir, targetFileName)
        val tmpFile = File(targetDir, "$targetFileName.$TMP_EXTENSION")

        // If target file already exists and is full size, complete immediately
        if (finalFile.exists() && finalFile.length() > 0 && (expectedTotalBytes == 0L || finalFile.length() >= expectedTotalBytes)) {
            AppLogger.i(TAG, "Model file already exists at ${finalFile.absolutePath} (${finalFile.length()} bytes)")
            return@withContext Result.success(
                Data.Builder()
                    .putString(KEY_OUTPUT_PATH, finalFile.absolutePath)
                    .putLong(KEY_RECEIVED_BYTES, finalFile.length())
                    .putInt(KEY_PROGRESS_PERCENT, 100)
                    .build()
            )
        }

        try {
            // Set foreground notification
            setForeground(createForegroundInfo(0, modelName, 0L, expectedTotalBytes, 0L, 0L))

            val url = URL(downloadUrl)
            var connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000

            // Check if partial tmp file exists for resuming
            var existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
            if (existingBytes > 0) {
                AppLogger.i(TAG, "Resuming download from byte offset: $existingBytes")
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
                connection.setRequestProperty("Accept-Encoding", "identity")
            }

            connection.connect()

            // Follow HTTP 301/302 redirects if needed
            var responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                val newUrl = connection.getHeaderField("Location")
                AppLogger.i(TAG, "Redirected to: $newUrl")
                connection.disconnect()
                val redirectedUrl = URL(newUrl)
                connection = redirectedUrl.openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                if (existingBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=$existingBytes-")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                }
                connection.connect()
                responseCode = connection.responseCode
            }

            var downloadedBytes = existingBytes
            val isPartialContent = responseCode == HttpURLConnection.HTTP_PARTIAL
            val isFullContent = responseCode == HttpURLConnection.HTTP_OK

            if (!isPartialContent && !isFullContent) {
                if (responseCode == 416) { // Range Not Satisfiable
                    AppLogger.w(TAG, "Range not satisfiable. Resetting tmp file and restarting download.")
                    tmpFile.delete()
                    existingBytes = 0L
                    downloadedBytes = 0L
                    connection.disconnect()
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.connect()
                } else {
                    throw IOException("Server returned HTTP error code: $responseCode")
                }
            }

            val serverContentLength = connection.contentLengthLong
            val totalBytes = if (serverContentLength > 0L) {
                if (isPartialContent) existingBytes + serverContentLength else serverContentLength
            } else {
                expectedTotalBytes
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tmpFile, isPartialContent && existingBytes > 0)

            val buffer = ByteArray(32 * 1024) // 32 KB chunk buffer
            var bytesRead: Int
            var lastUpdateTs = System.currentTimeMillis()
            var deltaBytes = 0L
            val sizeHistory = mutableListOf<Long>()
            val latencyHistory = mutableListOf<Long>()

            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    deltaBytes += bytesRead

                    val now = System.currentTimeMillis()
                    val elapsedSinceUpdate = now - lastUpdateTs
                    if (elapsedSinceUpdate >= 250) {
                        if (sizeHistory.size >= 5) {
                            sizeHistory.removeAt(0)
                            latencyHistory.removeAt(0)
                        }
                        sizeHistory.add(deltaBytes)
                        latencyHistory.add(elapsedSinceUpdate)
                        deltaBytes = 0L

                        val totalSizeHistory = sizeHistory.sum()
                        val totalLatencyHistory = latencyHistory.sum()
                        val bytesPerSec = if (totalLatencyHistory > 0) {
                            (totalSizeHistory * 1000L) / totalLatencyHistory
                        } else 0L

                        val remainingMs = if (bytesPerSec > 0 && totalBytes > downloadedBytes) {
                            ((totalBytes - downloadedBytes) * 1000L) / bytesPerSec
                        } else 0L

                        val progressPercent = if (totalBytes > 0L) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else 0

                        setProgress(
                            Data.Builder()
                                .putInt(KEY_PROGRESS_PERCENT, progressPercent)
                                .putLong(KEY_RECEIVED_BYTES, downloadedBytes)
                                .putLong(KEY_TOTAL_BYTES, totalBytes)
                                .putLong(KEY_DOWNLOAD_RATE, bytesPerSec)
                                .putLong(KEY_REMAINING_MS, remainingMs)
                                .build()
                        )

                        setForeground(
                            createForegroundInfo(
                                progress = progressPercent,
                                modelName = modelName,
                                receivedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                bytesPerSec = bytesPerSec,
                                remainingMs = remainingMs
                            )
                        )
                        lastUpdateTs = now
                    }
                }
                outputStream.flush()
            } finally {
                try { outputStream.close() } catch (e: Exception) { AppLogger.w(TAG, "Error closing output stream: ${e.message}") }
                try { inputStream.close() } catch (e: Exception) { AppLogger.w(TAG, "Error closing input stream: ${e.message}") }
                connection.disconnect()
            }

            // Atomic rename from .tmp to final .litertlm
            if (finalFile.exists()) {
                finalFile.delete()
            }
            val renamed = tmpFile.renameTo(finalFile)
            if (!renamed) {
                AppLogger.w(TAG, "Failed to rename ${tmpFile.name} to ${finalFile.name}. Copying fallback...")
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }

            AppLogger.i(TAG, "Model download completed successfully: ${finalFile.absolutePath} (${finalFile.length()} bytes)")

            Result.success(
                Data.Builder()
                    .putString(KEY_OUTPUT_PATH, finalFile.absolutePath)
                    .putLong(KEY_RECEIVED_BYTES, finalFile.length())
                    .putInt(KEY_PROGRESS_PERCENT, 100)
                    .build()
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception during model download: ${e.message}", e)
            Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR_MESSAGE, e.message ?: "Unknown download failure")
                    .build()
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "AI Model"
        return createForegroundInfo(0, modelName, 0L, 0L, 0L, 0L)
    }

    @SuppressLint("PendingIntentMutability")
    private fun createForegroundInfo(
        progress: Int,
        modelName: String,
        receivedBytes: Long,
        totalBytes: Long,
        bytesPerSec: Long,
        remainingMs: Long
    ): ForegroundInfo {
        val title = "Downloading $modelName"
        val speedMb = bytesPerSec / (1024.0 * 1024.0)
        val etaSec = remainingMs / 1000

        val content = if (totalBytes > 0) {
            val downloadedMb = receivedBytes / (1024 * 1024)
            val totalMb = totalBytes / (1024 * 1024)
            "$progress% ($downloadedMb / $totalMb MB) • %.1f MB/s • %ds left".format(speedMb, etaSec)
        } else {
            "Downloading in progress..."
        }

        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, totalBytes <= 0)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
