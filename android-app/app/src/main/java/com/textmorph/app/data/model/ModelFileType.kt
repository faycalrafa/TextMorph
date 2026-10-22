package com.textmorph.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ModelFileType(val displayName: String, val defaultExtension: String) {
    LITERT_LM("Google LiteRT-LM", ".litertlm"),
    GGUF("llama.cpp GGUF", ".gguf"),
    UNKNOWN("Unknown Format", "");

    companion object {
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF" in ASCII
        private val TFLITE_MAGIC = byteArrayOf(0x54, 0x46, 0x4c, 0x33) // "TFL3" in ASCII

        fun detect(fileName: String, headerBytes: ByteArray? = null): ModelFileType {
            if (headerBytes != null && headerBytes.size >= 4) {
                // Check GGUF magic at index 0..3
                if (headerBytes[0] == GGUF_MAGIC[0] &&
                    headerBytes[1] == GGUF_MAGIC[1] &&
                    headerBytes[2] == GGUF_MAGIC[2] &&
                    headerBytes[3] == GGUF_MAGIC[3]
                ) {
                    return GGUF
                }

                // Check TFLite/LiteRT-LM magic at index 4..7 (standard FlatBuffers file_identifier)
                if (headerBytes.size >= 8 &&
                    headerBytes[4] == TFLITE_MAGIC[0] &&
                    headerBytes[5] == TFLITE_MAGIC[1] &&
                    headerBytes[6] == TFLITE_MAGIC[2] &&
                    headerBytes[7] == TFLITE_MAGIC[3]
                ) {
                    return LITERT_LM
                }
            }

            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".gguf") -> GGUF
                lower.endsWith(".litertlm") || lower.endsWith(".tflite") -> LITERT_LM
                else -> UNKNOWN
            }
        }
    }
}
