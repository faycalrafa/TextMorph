package com.textmorph.app.llm

import android.content.Context
import com.textmorph.app.util.AppLogger

/**
 * Thread-safe singleton provider ensuring a single active LlamaCppEngine instance
 * across the entire application process.
 */
object LlamaCppEngineManager {

    private const val TAG = "LlamaCppEngineManager"

    @Volatile
    private var instance: LlamaCppEngine? = null

    fun getInstance(context: Context): LlamaCppEngine {
        return instance ?: synchronized(this) {
            instance ?: LlamaCppEngine(context.applicationContext).also {
                instance = it
                AppLogger.i(TAG, "Initialized shared LlamaCppEngine singleton instance.")
            }
        }
    }
}
