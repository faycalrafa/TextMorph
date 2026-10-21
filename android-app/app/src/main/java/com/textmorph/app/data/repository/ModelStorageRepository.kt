package com.textmorph.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.textmorph.app.data.model.ModelInfo
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class ModelStorageRepository(private val context: Context) {

    private val TAG = "ModelStorageRepository"

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    companion object {
        val CATALOG_E2B = ModelInfo(
            id = "catalog_gemma_4_e2b",
            name = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_684_354_560L, // ~2.5 GB
            isDownloaded = false,
            isBuiltInCatalog = true,
            isVisionCapable = true,
            description = "Lightweight on-device multimodal Gemma 4 model. Ideal for 6GB–8GB RAM devices."
        )

        val CATALOG_E4B = ModelInfo(
            id = "catalog_gemma_4_e4b",
            name = "gemma-4-E4B-it.litertlm",
            sizeBytes = 5_153_960_755L, // ~4.8 GB
            isDownloaded = false,
            isBuiltInCatalog = true,
            isVisionCapable = true,
            description = "High-capability multimodal Gemma 4 model. Recommended for 8GB–12GB+ RAM devices."
        )
    }

    suspend fun refreshModels() = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val list = mutableListOf<ModelInfo>()

            // 1. Scan Downloads directory if All Files access is granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null && downloadsDir.exists() && downloadsDir.isDirectory) {
                    val files = downloadsDir.listFiles { file ->
                        file.isFile && file.name.endsWith(".litertlm", ignoreCase = true)
                    }
                    files?.forEach { file ->
                        list.add(
                            ModelInfo(
                                id = "ext_${file.absolutePath.hashCode()}",
                                name = file.name,
                                path = file.absolutePath,
                                sizeBytes = file.length(),
                                isDownloaded = true,
                                isBuiltInCatalog = false,
                                isExternalStorage = true,
                                isVisionCapable = file.name.contains("gemma-4", ignoreCase = true) || file.name.contains("vision", ignoreCase = true),
                                description = "Zero-copy memory-mapped directly from Downloads/"
                            )
                        )
                    }
                }
            }

            // 2. Scan internal app models directory
            val internalModelsDir = File(context.filesDir, "models")
            if (!internalModelsDir.exists()) {
                internalModelsDir.mkdirs()
            }
            internalModelsDir.listFiles { file ->
                file.isFile && file.name.endsWith(".litertlm", ignoreCase = true)
            }?.forEach { file ->
                list.add(
                    ModelInfo(
                        id = "int_${file.name}",
                        name = file.name,
                        path = file.absolutePath,
                        sizeBytes = file.length(),
                        isDownloaded = true,
                        isBuiltInCatalog = false,
                        isExternalStorage = false,
                        isVisionCapable = true,
                        description = "Located in App Private Storage."
                    )
                )
            }

            // 3. Include catalog entries (mark as downloaded if file with matching name was found)
            val foundNames = list.map { it.name }.toSet()
            val catalogModels = listOf(CATALOG_E2B, CATALOG_E4B).map { catalog ->
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
            list.filterNot { it.name.equals(CATALOG_E2B.name, ignoreCase = true) || it.name.equals(CATALOG_E4B.name, ignoreCase = true) }
                .forEach { finalList.add(it) }

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
            // Take persistable permission
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

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

            // Check if direct filesystem path exists and is readable
            var directPath: String? = null
            if (uri.scheme == "file") {
                directPath = uri.path
            } else {
                val docId = try {
                    android.provider.DocumentsContract.getDocumentId(uri)
                } catch (e: Exception) { null }

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
                isVisionCapable = displayName.contains("gemma-4", ignoreCase = true) || displayName.contains("vision", ignoreCase = true),
                description = if (directPath != null) "Direct Mmap: $displayName" else "SAF Zero-Copy Model: $displayName"
            )

            val updated = _availableModels.value.toMutableList()
            updated.removeAll { it.uriString == uri.toString() || it.name == displayName }
            updated.add(0, modelInfo)
            _availableModels.value = updated

            AppLogger.i(TAG, "Successfully registered SAF model: $displayName (directPath=$directPath, uri=$uri, size=$size bytes)")
            modelInfo
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register SAF model URI: ${e.message}", e)
            null
        }
    }
}
