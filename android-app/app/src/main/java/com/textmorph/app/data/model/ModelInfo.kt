package com.textmorph.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val path: String? = null,
    val uriString: String? = null,
    val sizeBytes: Long = 0L,
    val isDownloaded: Boolean = false,
    val isBuiltInCatalog: Boolean = false,
    val isExternalStorage: Boolean = false,
    val isVisionCapable: Boolean = true,
    val description: String = ""
)
