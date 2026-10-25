package com.textmorph.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.textmorph.app.MainActivity
import com.textmorph.app.R
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.server.LocalHttpServer
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class LlmForegroundService : Service() {

    private val TAG = "LlmForegroundService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binder = LocalBinder()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var liteRtEngine: LiteRtLmEngine
    private lateinit var llamaCppEngine: LlamaCppEngine
    private var httpServer: LocalHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): LlmForegroundService = this@LlmForegroundService
        fun getLiteRtEngine(): LiteRtLmEngine = liteRtEngine
        fun getLlamaCppEngine(): LlamaCppEngine = llamaCppEngine
        fun getActiveEngine(): LlmEngine = if (settingsRepository.settingsState.value.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP) llamaCppEngine else liteRtEngine
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "LlmForegroundService onCreate")
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        liteRtEngine = LiteRtLmEngineManager.getInstance(applicationContext)
        llamaCppEngine = LlamaCppEngineManager.getInstance(applicationContext)

        acquireWakeLock()
        createNotificationChannel()
        safeStartForeground("Initializing TextMorph AI Engine...")

        // Observe settings for model changes and local server toggle
        settingsRepository.settingsState.onEach { settings ->
            AppLogger.d(TAG, "Observed settings update: localServer=${settings.localServerEnabled}, model=${settings.activeModelName}, engine=${settings.selectedEngine}")
            
            // Manage Local Server
            if (settings.localServerEnabled) {
                if (httpServer == null) {
                    val activeEngine = if (settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP) llamaCppEngine else liteRtEngine
                    httpServer = LocalHttpServer(
                        llmEngine = activeEngine,
                        port = settings.localServerPort,
                        bearerToken = settings.localServerBearerToken,
                        activeModelName = settings.activeModelName
                    ).also { it.start() }
                    updateNotification("Engine Active • Localhost :${settings.localServerPort}")
                }
            } else {
                httpServer?.stop()
                httpServer = null
                updateNotification("Engine Active • On-Device Ready")
            }

            // Warm up model session if path or URI is specified
            val isLlama = settings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP
            if (isLlama) {
                if ((settings.activeModelPath != null || settings.activeModelUri != null) && !llamaCppEngine.isLoaded()) {
                    serviceScope.launch {
                        val uri = settings.activeModelUri?.let { android.net.Uri.parse(it) }
                        val mmprojUri = settings.activeMmprojUri?.let { android.net.Uri.parse(it) }
                        llamaCppEngine.initialize(
                            modelPath = settings.activeModelPath,
                            modelName = settings.activeModelName,
                            modelUri = uri,
                            mmprojPath = settings.activeMmprojPath,
                            mmprojUri = mmprojUri,
                            preferredAccelerator = settings.preferredAccelerator,
                            probeTimeoutMs = settings.probeTimeoutMs
                        )
                    }
                }
            } else {
                if ((settings.activeModelPath != null || settings.activeModelUri != null) && !liteRtEngine.isLoaded()) {
                    serviceScope.launch {
                        val uri = settings.activeModelUri?.let { android.net.Uri.parse(it) }
                        liteRtEngine.initialize(settings.activeModelPath, settings.activeModelName, uri, settings.preferredAccelerator, settings.probeTimeoutMs)
                    }
                }
            }
        }.launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "LlmForegroundService onStartCommand action=${intent?.action}")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "LlmForegroundService onDestroy")
        httpServer?.stop()
        httpServer = null
        serviceScope.launch {
            liteRtEngine.unload()
            llamaCppEngine.unload()
        }
        serviceScope.cancel()
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TextMorph:LlmWakeLock").apply {
                acquire(10 * 60 * 1000L /* 10 minutes timeout */)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing wake lock: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TextMorph Background AI Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the local on-device LLM warm in memory and powers the localhost API."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TextMorph AI")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun safeStartForeground(statusText: String) {
        try {
            val notification = buildNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AppLogger.i(TAG, "Foreground service started successfully with specialUse type.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed starting foreground service: ${e.message}", e)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        AppLogger.w(TAG, "Foreground service timeout received for type $fgsType (startId=$startId). Stopping gracefully.")
        stopSelf(startId)
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    companion object {
        const val CHANNEL_ID = "textmorph_llm_channel"
        const val NOTIFICATION_ID = 2001

        fun startService(context: Context) {
            try {
                val intent = Intent(context, LlmForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e("LlmForegroundService", "startService invocation failed: ${e.message}", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LlmForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
