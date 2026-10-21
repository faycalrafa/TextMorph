package com.textmorph.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.textmorph.app.MainActivity
import com.textmorph.app.R
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
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
    private lateinit var engine: LiteRtLmEngine
    private var httpServer: LocalHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): LlmForegroundService = this@LlmForegroundService
        fun getEngine(): LiteRtLmEngine = engine
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "LlmForegroundService onCreate")
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        engine = com.textmorph.app.llm.LiteRtLmEngineManager.getInstance(applicationContext)

        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing TextMorph AI Engine..."))

        // Observe settings for model changes and local server toggle
        settingsRepository.settingsState.onEach { settings ->
            AppLogger.d(TAG, "Observed settings update: localServer=${settings.localServerEnabled}, model=${settings.activeModelName}")
            
            // Manage Local Server
            if (settings.localServerEnabled) {
                if (httpServer == null) {
                    httpServer = LocalHttpServer(
                        llmEngine = engine,
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
            if ((settings.activeModelPath != null || settings.activeModelUri != null) && !engine.isLoaded()) {
                serviceScope.launch {
                    val uri = settings.activeModelUri?.let { android.net.Uri.parse(it) }
                    engine.initialize(settings.activeModelPath, settings.activeModelName, uri, settings.preferredAccelerator)
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
            engine.unload()
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

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    companion object {
        const val CHANNEL_ID = "textmorph_llm_channel"
        const val NOTIFICATION_ID = 2001

        fun startService(context: Context) {
            val intent = Intent(context, LlmForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LlmForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
