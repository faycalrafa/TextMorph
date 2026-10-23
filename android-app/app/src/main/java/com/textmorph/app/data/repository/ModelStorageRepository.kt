package com.textmorph.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.model.ModelInfo
import com.textmorph.app.util.AppLogger
import com.textmorph.app.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ModelStorageRepository(private val context: Context) {

    private val TAG = "ModelStorageRepository"

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    companion object {
        @Volatile
        private var instance: ModelStorageRepository? = null

        fun getInstance(context: Context): ModelStorageRepository {
            return instance ?: synchronized(this) {
                instance ?: ModelStorageRepository(context.applicationContext).also { instance = it }
            }
        }

        val CATALOG_E2B = ModelInfo(
            id = "catalog_gemma_4_e2b",
            name = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_684_354_560L, // ~2.5 GB
            isDownloaded = false,
            isBuiltInCatalog = true,
            isVisionCapable = true,
            description = "Lightweight on-device multimodal Gemma 4 model. Ideal for 6GB–8GB RAM devices.",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        )

        val CATALOG_E4B = ModelInfo(
            id = "catalog_gemma_4_e4b",
            name = "gemma-4-E4B-it.litertlm",
            sizeBytes = 5_153_960_755L, // ~4.8 GB
            isDownloaded = false,
            isBuiltInCatalog = true,
            isVisionCapable = true,
            modelFileType = ModelFileType.LITERT_LM,
            description = "High-capability multimodal Gemma 4 model. Recommended for 8GB–12GB+ RAM devices.",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
        )

        val CATALOG_SMOL_GGUF = ModelInfo(
            id = "catalog_smol_gguf",
            name = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1_060_000_000L, // ~1.0 GB
            isDownloaded = false,
            isBuiltInCatalog = true,
            isVisionCapable = false,
            modelFileType = ModelFileType.GGUF,
            description = "Lightweight on-device GGUF model powered by llama.cpp. Compatible with optional mmproj vision extension.",
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf"
        )
    }

    suspend fun refreshModels() = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val list = mutableListOf<ModelInfo>()

            // 1. Scan External Storage directories if All Files access is granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                val searchDirs = listOfNotNull(
                    Environment.getExternalStorageDirectory(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                ).filter { it.exists() && it.isDirectory }

                val seenPaths = mutableSetOf<String>()
                for (dir in searchDirs) {
                    val files = dir.listFiles { file ->
                        file.isFile && (file.name.endsWith(".litertlm", ignoreCase = true) ||
                                (file.name.endsWith(".gguf", ignoreCase = true) && !file.name.startsWith("mmproj", ignoreCase = true)))
                    }
                    files?.forEach { file ->
                        val canonical = file.canonicalPath
                        if (seenPaths.add(canonical)) {
                            val detectedType = ModelFileType.detect(file.name)
                            list.add(
                                ModelInfo(
                                    id = "ext_${canonical.hashCode()}",
                                    name = file.name,
                                    path = canonical,
                                    sizeBytes = file.length(),
                                    isDownloaded = true,
                                    isBuiltInCatalog = false,
                                    isExternalStorage = true,
                                    isVisionCapable = detectedType == ModelFileType.LITERT_LM || file.name.contains("vision", ignoreCase = true) || file.name.contains("llava", ignoreCase = true),
                                    modelFileType = detectedType,
                                    description = "Zero-copy memory-mapped from ${file.parentFile?.name ?: "storage"}/"
                                )
                            )
                        }
                    }
                }
            }

            // 2. Scan internal app models directory
            val internalModelsDir = File(context.filesDir, "models")
            if (!internalModelsDir.exists()) {
                internalModelsDir.mkdirs()
            }
            internalModelsDir.listFiles { file ->
                file.isFile && (file.name.endsWith(".litertlm", ignoreCase = true) ||
                        (file.name.endsWith(".gguf", ignoreCase = true) && !file.name.startsWith("mmproj", ignoreCase = true)))
            }?.forEach { file ->
                val detectedType = ModelFileType.detect(file.name)
                list.add(
                    ModelInfo(
                        id = "int_${file.name}",
                        name = file.name,
                        path = file.absolutePath,
                        sizeBytes = file.length(),
                        isDownloaded = true,
                        isBuiltInCatalog = false,
                        isExternalStorage = false,
                        isVisionCapable = detectedType == ModelFileType.LITERT_LM || file.name.contains("vision", ignoreCase = true),
                        modelFileType = detectedType,
                        description = "Located in App Private Storage."
                    )
                )
            }

            // 3. Include catalog entries (mark as downloaded if file with matching name was found)
            val catalogList = listOf(CATALOG_E2B, CATALOG_E4B, CATALOG_SMOL_GGUF)
            val catalogModels = catalogList.map { catalog ->
                val matched = list.firstOrNull { it.name.equals(catalog.name, ignoreCase = true) }
                if (matched != null) {
                    catalog.copy(
                        isDownloaded = true,
                        path = matched.path,
                        sizeBytes = matched.sizeBytes
                    )
                } else {
                    catalog
                }
            }

            // Merge unique entries
            val finalList = mutableListOf<ModelInfo>()
            finalList.addAll(catalogModels)
            list.filterNot { item -> catalogList.any { it.name.equals(item.name, ignoreCase = true) } }
                .forEach { finalList.add(it) }

            // Preserve registered SAF models from current availableModels
            val currentSafModels = _availableModels.value.filter { it.uriString != null || it.id.startsWith("saf_") }
            currentSafModels.forEach { safModel ->
                if (finalList.none { it.id == safModel.id || it.name.equals(safModel.name, ignoreCase = true) }) {
                    finalList.add(0, safModel)
                }
            }

            _availableModels.value = finalList
            AppLogger.i(TAG, "Scanned models complete: ${finalList.size} models available (${list.size} local files found).")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed scanning models directory: ${e.message}", e)
        } finally {
            _isScanning.value = false
        }
    }

    suspend fun registerSafModelUri(uri: Uri): ModelInfo? = withContext(Dispatchers.IO) {
        try {
            // Attempt persistable permission grant (safely ignore if not supported by provider)
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (secEx: SecurityException) {
                AppLogger.w(TAG, "Could not take persistable URI permission for $uri: ${secEx.message}")
            }

            var displayName = "custom_model.litertlm"
            var size = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }

            // Inspect header bytes to accurately identify GGUF or LiteRT binary magic
            val headerBytes = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArray(16)
                    val read = stream.read(buf)
                    if (read >= 4) buf else null
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Could not read header bytes for $displayName: ${e.message}")
                null
            }

            var detectedType = ModelFileType.detect(displayName, headerBytes)
            if (detectedType == ModelFileType.GGUF && !displayName.endsWith(".gguf", ignoreCase = true)) {
                displayName = "$displayName.gguf"
            }

            // Check if direct filesystem path exists and is readable
            var directPath: String? = null
            if (uri.scheme == "file") {
                directPath = uri.path
            } else {
                val docId = try {
                    android.provider.DocumentsContract.getDocumentId(uri)
                } catch (e: Exception) {
                    AppLogger.d(TAG, "SAF getDocumentId query skipped/unsupported for $uri: ${e.message}")
                    null
                }

                if (docId != null) {
                    if (docId.startsWith("primary:")) {
                        val relativePath = docId.removePrefix("primary:")
                        val directFile = File(android.os.Environment.getExternalStorageDirectory(), relativePath)
                        if (directFile.exists() && directFile.canRead()) {
                            directPath = directFile.absolutePath
                        }
                    } else if (docId.startsWith("raw:")) {
                        val rawPath = docId.removePrefix("raw:")
                        val directFile = File(rawPath)
                        if (directFile.exists() && directFile.canRead()) {
                            directPath = directFile.absolutePath
                        }
                    }
                }

                if (directPath == null) {
                    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val downloadCandidate = File(downloadDir, displayName)
                    if (downloadCandidate.exists() && downloadCandidate.canRead()) {
                        directPath = downloadCandidate.absolutePath
                    }
                }
            }

            val modelInfo = ModelInfo(
                id = "saf_${uri.toString().hashCode()}",
                name = displayName,
                path = directPath,
                uriString = uri.toString(),
                sizeBytes = size,
                isDownloaded = true,
                isBuiltInCatalog = false,
                isExternalStorage = true,
                isVisionCapable = detectedType == ModelFileType.LITERT_LM || displayName.contains("gemma-4", ignoreCase = true) || displayName.contains("vision", ignoreCase = true),
                modelFileType = detectedType,
                description = if (directPath != null) "Direct Mmap (${detectedType.displayName}): $displayName" else "SAF Zero-Copy (${detectedType.displayName}): $displayName"
            )

            val updated = _availableModels.value.toMutableList()
            updated.removeAll { it.uriString == uri.toString() || it.name == displayName }
            updated.add(0, modelInfo)
            _availableModels.value = updated

            AppLogger.i(TAG, "Successfully registered SAF model: $displayName (format=${detectedType.displayName}, directPath=$directPath, uri=$uri, size=$size bytes)")
            modelInfo
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register SAF model URI: ${e.message}", e)
            null
        }
    }

    suspend fun registerSafMmprojUri(uri: Uri): Pair<String, String?> = withContext(Dispatchers.IO) {
        try {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (secEx: SecurityException) {
                AppLogger.w(TAG, "Could not take persistable URI permission for mmproj $uri: ${secEx.message}")
            }

            var displayName = "mmproj.gguf"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
            }

            var directPath: String? = null
            if (uri.scheme == "file") {
                directPath = uri.path
            } else {
                val docId = try {
                    android.provider.DocumentsContract.getDocumentId(uri)
                } catch (e: Exception) {
                    AppLogger.d(TAG, "SAF mmproj getDocumentId query skipped/unsupported for $uri: ${e.message}")
                    null
                }
                if (docId != null && docId.startsWith("primary:")) {
                    val relativePath = docId.removePrefix("primary:")
                    val directFile = File(Environment.getExternalStorageDirectory(), relativePath)
                    if (directFile.exists() && directFile.canRead()) directPath = directFile.absolutePath
                }
            }
            AppLogger.i(TAG, "Registered SAF mmproj: $displayName (directPath=$directPath)")
            Pair(displayName, directPath)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register SAF mmproj URI: ${e.message}", e)
            Pair("mmproj.gguf", null)
        }
    }

    /**
     * Associates or disassociates an optional mmproj multimodal projector with a model card.
     */
    fun attachMmprojToModel(modelId: String, mmprojName: String?, mmprojPath: String?, mmprojUri: String?): ModelInfo? {
        val current = _availableModels.value.toMutableList()
        val idx = current.indexOfFirst { it.id == modelId }
        return if (idx != -1) {
            val old = current[idx]
            val updated = old.copy(
                mmprojName = mmprojName,
                mmprojPath = mmprojPath,
                mmprojUriString = mmprojUri,
                isVisionCapable = !mmprojPath.isNullOrBlank() || !mmprojUri.isNullOrBlank()
            )
            current[idx] = updated
            _availableModels.value = current
            AppLogger.i(TAG, "Attached mmproj ($mmprojName) to model: ${old.name}")
            updated
        } else {
            null
        }
    }

    fun detachMmprojFromModel(modelId: String): ModelInfo? {
        return attachMmprojToModel(modelId, null, null, null)
    }

    /**
     * Enqueues a background WorkManager download task for a catalog model or custom URL.
     */
    fun startDownload(modelInfo: ModelInfo, customUrl: String? = null, saveToDownloads: Boolean = false) {
        val url = customUrl ?: modelInfo.downloadUrl
        if (url.isNullOrBlank()) {
            AppLogger.e(TAG, "Cannot start download: URL is missing for model ${modelInfo.name}")
            return
        }

        val workData = Data.Builder()
            .putString(DownloadWorker.KEY_MODEL_ID, modelInfo.id)
            .putString(DownloadWorker.KEY_MODEL_NAME, modelInfo.name)
            .putString(DownloadWorker.KEY_DOWNLOAD_URL, url)
            .putLong(DownloadWorker.KEY_TOTAL_BYTES, modelInfo.sizeBytes)
            .putBoolean(DownloadWorker.KEY_SAVE_TO_DOWNLOADS, saveToDownloads)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workData)
            .addTag("model_download")
            .addTag(modelInfo.id)
            .build()

        AppLogger.i(TAG, "Enqueuing WorkManager download for ${modelInfo.name} (uniqueWork=download_${modelInfo.id})")
        WorkManager.getInstance(context).enqueueUniqueWork(
            "download_${modelInfo.id}",
            ExistingWorkPolicy.REPLACE,
            downloadRequest
        )
    }

    /**
     * Cancels an ongoing background download task.
     */
    fun cancelDownload(modelId: String) {
        AppLogger.i(TAG, "Canceling download work for modelId=$modelId")
        WorkManager.getInstance(context).cancelUniqueWork("download_$modelId")
    }

    /**
     * Returns a Flow observing the background download progress/status for a model.
     */
    fun getDownloadWorkInfo(modelId: String): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow("download_$modelId")
            .map { it.firstOrNull() }
    }

    /**
     * Deletes a model file from storage and refreshes available models.
     */
    suspend fun deleteModel(modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = modelInfo.path
            if (path == null) {
                AppLogger.w(TAG, "Cannot delete model ${modelInfo.name}: path is null")
                return@withContext false
            }
            val file = File(path)
            if (file.exists()) {
                val deleted = file.delete()
                AppLogger.i(TAG, "Deleted model file at $path: success=$deleted")
                refreshModels()
                deleted
            } else {
                AppLogger.w(TAG, "File to delete does not exist: $path")
                refreshModels()
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete model ${modelInfo.name}: ${e.message}", e)
            false
        }
    }

    /**
     * Copies a model from Downloads / external storage into App Private Storage (models/),
     * allowing the user to delete the original external file without losing the model.
     */
    suspend fun importToPrivateStorage(modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            val srcPath = modelInfo.path ?: return@withContext false
            val srcFile = File(srcPath)
            if (!srcFile.exists()) {
                AppLogger.w(TAG, "Source file does not exist: $srcPath")
                return@withContext false
            }

            val internalModelsDir = File(context.filesDir, "models")
            if (!internalModelsDir.exists()) {
                internalModelsDir.mkdirs()
            }
            val destFile = File(internalModelsDir, modelInfo.name)
            if (destFile.canonicalPath == srcFile.canonicalPath) {
                AppLogger.i(TAG, "Model is already in app private storage.")
                return@withContext true
            }

            AppLogger.i(TAG, "Importing ${srcFile.name} (${srcFile.length()} bytes) to app private storage: ${destFile.absolutePath}")
            FileInputStream(srcFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            AppLogger.i(TAG, "Import completed successfully.")
            refreshModels()
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed importing model to private storage: ${e.message}", e)
            false
        }
    }
}
