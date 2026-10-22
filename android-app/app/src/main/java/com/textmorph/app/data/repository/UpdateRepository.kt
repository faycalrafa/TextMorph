package com.textmorph.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val isUpdateAvailable: Boolean
)

sealed class DownloadState {
    data class Progress(val percent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class UpdateRepository(private val context: Context) {

    private val TAG = "UpdateRepository"
    private val json = Json { ignoreUnknownKeys = true }
    private val RELEASES_URL = "https://api.github.com/repos/faycalrafa/TextMorph/releases/latest"

    fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    suspend fun checkForUpdates(): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "Checking for updates at $RELEASES_URL...")
            val url = URL(RELEASES_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "TextMorph-Android")
            }

            if (conn.responseCode !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
                AppLogger.w(TAG, "Update check returned HTTP ${conn.responseCode}: $error")
                return@withContext Result.failure(Exception("Check failed: $error"))
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = json.parseToJsonElement(body).jsonObject
            val tagName = parsed["tag_name"]?.jsonPrimitive?.content ?: ""
            val releaseTitle = parsed["name"]?.jsonPrimitive?.content ?: tagName
            val releaseNotes = parsed["body"]?.jsonPrimitive?.content ?: ""
            val assets = parsed["assets"]?.jsonArray

            var downloadUrl = ""
            var apkSize = 0L

            assets?.forEach { assetElem ->
                val assetObj = assetElem.jsonObject
                val name = assetObj["name"]?.jsonPrimitive?.content ?: ""
                if (name.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content ?: ""
                    apkSize = assetObj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                }
            }

            val currentVer = getCurrentVersionName()
            val remoteVer = tagName.removePrefix("v").trim()
            val isNewer = isVersionNewer(remoteVer, currentVer)

            AppLogger.i(TAG, "Current version: $currentVer, Remote version: $remoteVer, isNewer: $isNewer")

            val info = AppReleaseInfo(
                tagName = tagName,
                versionName = remoteVer,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                apkSize = apkSize,
                isUpdateAvailable = isNewer && downloadUrl.isNotEmpty()
            )
            Result.success(info)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking for updates: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Volatile
    private var cachedDownloadedApkFile: File? = null

    fun downloadApk(downloadUrl: String): Flow<DownloadState> = flow {
        var activeConnection: HttpURLConnection? = null
        try {
            AppLogger.i(TAG, "Starting high-speed APK download from $downloadUrl...")

            var currentUrl = downloadUrl
            var redirectHops = 0
            var finalConnection: HttpURLConnection? = null

            while (redirectHops < 6) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "TextMorph-Android")
                }
                activeConnection = conn
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        break
                    }
                    currentUrl = location
                    redirectHops++
                    conn.disconnect()
                } else if (code in 200..299) {
                    finalConnection = conn
                    break
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    throw Exception("Server returned HTTP $code: $err")
                }
            }

            val targetConn = finalConnection ?: throw Exception("Failed to resolve download URL after redirects.")
            activeConnection = targetConn

            val totalBytes = targetConn.contentLengthLong
            val cacheDir = File(context.cacheDir, "updates")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val apkFile = File(cacheDir, "TextMorph-update.apk")
            if (apkFile.exists()) apkFile.delete()

            // 64KB high-speed stream buffering
            val bufferSize = 64 * 1024
            java.io.BufferedInputStream(targetConn.inputStream, bufferSize).use { input ->
                java.io.BufferedOutputStream(FileOutputStream(apkFile), bufferSize).use { output ->
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastPercent = -1
                    var lastEmitTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0
                        val now = System.currentTimeMillis()

                        // Throttle progress updates to at most once per 150ms or 2%+ delta to prevent Compose jank
                        if ((percent >= lastPercent + 2 && now - lastEmitTime >= 150L) || percent == 100 || (lastPercent == -1 && percent > 0)) {
                            lastPercent = percent
                            lastEmitTime = now
                            emit(DownloadState.Progress(percent, totalRead, totalBytes))
                        }
                    }
                    output.flush()
                }
            }

            cachedDownloadedApkFile = if (apkFile.exists() && apkFile.length() > 0) apkFile else null
            AppLogger.i(TAG, "APK download completed smoothly: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            emit(DownloadState.Completed(apkFile))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed downloading update APK: ${e.message}", e)
            emit(DownloadState.Error(e.message ?: "Download failed"))
        } finally {
            try {
                activeConnection?.disconnect()
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    fun getDownloadedApkFile(): File? {
        val cached = cachedDownloadedApkFile
        if (cached != null && cached.exists()) return cached

        val cacheDir = File(context.cacheDir, "updates")
        val apkFile = File(cacheDir, "TextMorph-update.apk")
        val result = if (apkFile.exists() && apkFile.length() > 0) apkFile else null
        cachedDownloadedApkFile = result
        return result
    }

    fun installApk(file: File): Boolean {
        return try {
            AppLogger.i(TAG, "Triggering APK install: ${file.absolutePath}")
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }

    private fun isVersionNewer(remote: String, current: String): Boolean {
        try {
            val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (e: Exception) {
            return remote != current
        }
    }
}
