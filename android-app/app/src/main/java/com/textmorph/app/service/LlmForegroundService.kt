package com.textmorph.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.textmorph.app.util.AppLogger

class LlmForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.i("LlmForegroundService", "LlmForegroundService created.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i("LlmForegroundService", "LlmForegroundService destroyed.")
    }
}
