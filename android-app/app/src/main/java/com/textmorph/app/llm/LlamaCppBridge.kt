package com.textmorph.app.llm

import com.textmorph.app.util.AppLogger

/**
 * Native JNI bridge for llama.cpp and ggml.
 * Provides safe loading of native libraries and declarations for model lifecycle and token generation.
 */
object LlamaCppBridge {

    private const val TAG = "LlamaCppBridge"
    private var libraryLoaded: Boolean = false
    private var loadAttempted: Boolean = false

    @Synchronized
    fun isLibraryLoaded(): Boolean {
        if (!loadAttempted) {
            loadAttempted = true
            libraryLoaded = tryLoadNativeLibraries()
        }
        return libraryLoaded
    }

    private fun tryLoadNativeLibraries(): Boolean {
        val candidates = listOf("llama", "ggml", "llamacpp_jni")
        var anyLoaded = false
        for (lib in candidates) {
            try {
                System.loadLibrary(lib)
                AppLogger.i(TAG, "Successfully loaded native library: $lib")
                anyLoaded = true
            } catch (unsatisfied: UnsatisfiedLinkError) {
                AppLogger.d(TAG, "Native library $lib not found on this platform/ABI: ${unsatisfied.message}")
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Unexpected error loading $lib: ${e.message}", e)
            }
        }
        return anyLoaded
    }

    // Native declarations (invoked when native library is loaded)
    external fun nativeInit(
        modelPath: String,
        mmprojPath: String?,
        nThreads: Int,
        nGpuLayers: Int
    ): Long

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        imageBytes: ByteArray?,
        callback: (String) -> Unit
    ): Int

    external fun nativeUnload(handle: Long)
}
