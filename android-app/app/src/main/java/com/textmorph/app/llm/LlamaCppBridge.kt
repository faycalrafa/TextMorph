package com.textmorph.app.llm

import com.textmorph.app.util.AppLogger

/**
 * Native JNI bridge for llama.cpp and ggml.
 * Provides safe verification and loading of native libraries for model lifecycle and token generation.
 */
object LlamaCppBridge {

    private const val TAG = "LlamaCppBridge"
    private var libraryLoaded: Boolean = false
    private var loadAttempted: Boolean = false

    @Synchronized
    fun isLibraryLoaded(): Boolean {
        if (!loadAttempted) {
            loadAttempted = true
            libraryLoaded = checkNativeAvailability()
        }
        return libraryLoaded
    }

    private fun checkNativeAvailability(): Boolean {
        // 1. Check if the production LlamaContext class is present in classpath without forcing static linkage
        val classAvailable = try {
            Class.forName("org.nehuatl.llamacpp.LlamaContext", false, LlamaCppBridge::class.java.classLoader) != null
        } catch (e: Throwable) {
            AppLogger.d(TAG, "LlamaContext class check failed: ${e.message}")
            false
        }

        // 2. Try loading native libraries if present
        val candidates = listOf("rnllama", "llama", "ggml", "llamacpp_jni")
        var anyLoaded = false
        for (lib in candidates) {
            try {
                System.loadLibrary(lib)
                AppLogger.i(TAG, "Successfully loaded native library: $lib")
                anyLoaded = true
            } catch (unsatisfied: UnsatisfiedLinkError) {
                AppLogger.d(TAG, "Native library $lib not found in library path: ${unsatisfied.message}")
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Unexpected error loading $lib: ${e.message}", e)
            }
        }
        return classAvailable || anyLoaded
    }
}
