package com.textmorph.app.llm

import android.content.Context
import com.textmorph.app.util.AppLogger

/**
 * Thread-safe singleton provider ensuring a single active LiteRtLmEngine instance
 * across the entire application process (Activities, Services, Overlays, and HTTP Server).
 * Prevents multiple concurrent GPU context allocations and memory exhaustion.
 */
object LiteRtLmEngineManager {

    private const val TAG = "LiteRtLmEngineManager"

    @Volatile
    private var instance: LiteRtLmEngine? = null

    fun getInstance(context: Context): LiteRtLmEngine {
        return instance ?: synchronized(this) {
            instance ?: LiteRtLmEngine(context.applicationContext).also {
                instance = it
                AppLogger.i(TAG, "Initialized shared LiteRtLmEngine singleton instance.")
            }
        }
    }
}
