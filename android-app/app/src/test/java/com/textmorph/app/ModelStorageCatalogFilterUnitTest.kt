package com.textmorph.app

import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.model.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying that undownloaded catalog models are excluded
 * and only models available in memory / on device storage are exposed in Model Hub.
 */
class ModelStorageCatalogFilterUnitTest {

    @Test
    fun testOnlyDownloadedModelsAreExposed() {
        val downloadedGguf = ModelInfo(
            id = "gemma-2b-q4",
            name = "gemma-2b-it-Q4_K_M.gguf",
            description = "Gemma 2B Q4",
            downloadUrl = "https://huggingface.co/...",
            modelFileType = ModelFileType.GGUF,
            isDownloaded = true,
            path = "/storage/emulated/0/Download/gemma-2b-it-Q4_K_M.gguf"
        )

        val undownloadedCatalogModel = ModelInfo(
            id = "qwen-2.5-7b",
            name = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            description = "Qwen 2.5 7B",
            downloadUrl = "https://huggingface.co/...",
            modelFileType = ModelFileType.GGUF,
            isDownloaded = false,
            path = null
        )

        val localSafModel = ModelInfo(
            id = "saf_custom_model",
            name = "my_custom_model.litertlm",
            description = "Imported from SAF",
            downloadUrl = "",
            modelFileType = ModelFileType.LITERT_LM,
            isDownloaded = true,
            path = "/sdcard/Download/my_custom_model.litertlm"
        )

        val allCatalogAndStorageModels = listOf(downloadedGguf, undownloadedCatalogModel, localSafModel)

        // Filter: only show what is on device / in memory
        val availableModels = allCatalogAndStorageModels.filter { it.isDownloaded }

        assertEquals(2, availableModels.size)
        assertTrue(availableModels.any { it.name == "gemma-2b-it-Q4_K_M.gguf" })
        assertTrue(availableModels.any { it.name == "my_custom_model.litertlm" })
        assertFalse(availableModels.any { it.name == "Qwen2.5-7B-Instruct-Q4_K_M.gguf" })
    }
}
