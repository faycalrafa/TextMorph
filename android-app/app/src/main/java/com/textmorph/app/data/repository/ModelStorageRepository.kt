package com.textmorph.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
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

/**
 * Progress metadata for copying external models into app private internal storage.
 */
data class ModelImportProgress(
    val isImporting: Boolean = false,
    val fileName: String = "",
    val bytesCopied: Long = 0L,
    val totalBytes: Long = 0L
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Repository responsible for managing on-device AI models stored exclusively in app internal storage.
 * Eliminates direct loading from external Download directories to guarantee stability and prevent SELinux crashes.
 */
class ModelStorageRepository(private val context: Context) {

    private val TAG = "ModelStorageRepository"
    val internalStorageManager = InternalModelStorageManager(context)

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _importProgress = MutableStateFlow(ModelImportProgress())
    val importProgress: StateFlow<ModelImportProgress> = _importProgress.asStateFlow()

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

    /**
     * Refreshes model inventory from app private internal storage (filesDir/models/).
     */
    suspend fun refreshModels() = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val list = mutableListOf<ModelInfo>()
            val files = internalStorageManager.listInternalModelFiles()

            files.forEach { file ->
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
                        description = "App Private Storage (${detectedType.displayName})"
                    )
                )
            }

            _availableModels.value = list
            AppLogger.i(TAG, "Scanned internal models complete: ${list.size} models available in private storage.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed scanning internal models: ${e.message}", e)
        } finally {
            _isScanning.value = false
        }
    }

    /**
     * Imports a model from an external SAF URI, copying it into app private internal storage.
     * Reports copy progress via importProgress StateFlow.
     */
    suspend fun registerSafModelUri(uri: Uri): ModelInfo? = withContext(Dispatchers.IO) {
        try {
            var displayName = "custom_model.litertlm"
            var size = 0L

            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Querying SAF display name failed: ${t.message}")
            }

            // Inspect header bytes to identify GGUF or LiteRT binary magic
            val headerBytes = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArray(16)
                    val read = stream.read(buf)
                    if (read >= 4) buf else null
                }
            } catch (e: Exception) {
                null
            }

            val detectedType = ModelFileType.detect(displayName, headerBytes)
            if (detectedType == ModelFileType.GGUF && !displayName.endsWith(".gguf", ignoreCase = true)) {
                displayName = "$displayName.gguf"
            } else if (detectedType == ModelFileType.LITERT_LM && !displayName.endsWith(".litertlm", ignoreCase = true)) {
                displayName = "$displayName.litertlm"
            }

            _importProgress.value = ModelImportProgress(
                isImporting = true,
                fileName = displayName,
                bytesCopied = 0L,
                totalBytes = size
            )

            val copyResult = internalStorageManager.copyFromUri(uri, displayName) { copied, total ->
                _importProgress.value = ModelImportProgress(
                    isImporting = true,
                    fileName = displayName,
                    bytesCopied = copied,
                    totalBytes = if (total > 0L) total else size
                )
            }

            _importProgress.value = ModelImportProgress(isImporting = false)

            copyResult.fold(
                onSuccess = { destFile ->
                    refreshModels()
                    val modelInfo = ModelInfo(
                        id = "int_${destFile.name}",
                        name = destFile.name,
                        path = destFile.absolutePath,
                        uriString = null,
                        sizeBytes = destFile.length(),
                        isDownloaded = true,
                        isBuiltInCatalog = false,
                        isExternalStorage = false,
                        isVisionCapable = detectedType == ModelFileType.LITERT_LM || destFile.name.contains("vision", ignoreCase = true),
                        modelFileType = detectedType,
                        description = "App Private Storage (${detectedType.displayName})"
                    )
                    AppLogger.i(TAG, "Successfully imported SAF model to private storage: ${destFile.name}")
                    modelInfo
                },
                onFailure = { err ->
                    AppLogger.e(TAG, "Failed copying SAF model to private storage: ${err.message}", err)
                    null
                }
            )
        } catch (e: Exception) {
            _importProgress.value = ModelImportProgress(isImporting = false)
            AppLogger.e(TAG, "Failed registering SAF model URI: ${e.message}", e)
            null
        }
    }

    /**
     * Imports an mmproj vision projector into app private internal storage.
     */
    suspend fun registerSafMmprojUri(uri: Uri): Pair<String, String?> = withContext(Dispatchers.IO) {
        try {
            var displayName = "mmproj.gguf"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) {
                        displayName = cursor.getString(nameIndex) ?: displayName
                    }
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Querying mmproj display name failed: ${t.message}")
            }

            val copyResult = internalStorageManager.copyFromUri(uri, displayName)
            val path = copyResult.getOrNull()?.absolutePath
            AppLogger.i(TAG, "Imported mmproj into private storage: $displayName (path=$path)")
            Pair(displayName, path)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to import mmproj URI: ${e.message}", e)
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
     * Enqueues a background WorkManager download task directly to internal models storage.
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
            .putBoolean(DownloadWorker.KEY_SAVE_TO_DOWNLOADS, false) // Always false: internal storage only
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workData)
            .addTag("model_download")
            .addTag(modelInfo.id)
            .build()

        AppLogger.i(TAG, "Enqueuing WorkManager download to internal storage for ${modelInfo.name}")
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

    /** Removes model from app memory/cache. */
    suspend fun removeModelFromMemory(modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            val current = _availableModels.value.toMutableList()
            current.removeAll { it.id == modelInfo.id || it.name.equals(modelInfo.name, ignoreCase = true) }
            _availableModels.value = current
            AppLogger.i(TAG, "Removed model ${modelInfo.name} from memory list.")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error removing model from memory: ${e.message}", e)
            false
        }
    }

    /** Deletes model permanently from internal disk storage. */
    suspend fun deleteModel(modelInfo: ModelInfo, permanentlyDeleteFile: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            val deleted = internalStorageManager.deleteInternalModel(modelInfo.name)
            removeModelFromMemory(modelInfo)
            refreshModels()
            AppLogger.i(TAG, "Deleted model ${modelInfo.name} from internal storage: success=$deleted")
            deleted
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed deleting model ${modelInfo.name}: ${e.message}", e)
            false
        }
    }

    /**
     * Backward compatibility helper: copies model file to internal storage.
     */
    suspend fun importToPrivateStorage(modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            if (internalStorageManager.hasModel(modelInfo.name)) {
                return@withContext true
            }
            val srcPath = modelInfo.path ?: return@withContext false
            val srcFile = File(srcPath)
            if (!srcFile.exists()) return@withContext false

            val destFile = File(internalStorageManager.modelsDir, modelInfo.name)
            srcFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            refreshModels()
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed importing to private storage: ${t.message}", t)
            false
        }
    }
}
