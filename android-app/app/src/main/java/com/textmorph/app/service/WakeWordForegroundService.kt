package com.textmorph.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.textmorph.app.MainActivity
import com.textmorph.app.R
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.PermissionUtils
import com.textmorph.app.voice.VoiceCommandManager
import com.textmorph.app.voice.WakeWordSpotter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Android Foreground Service holding the microphone wake-lock and running [WakeWordSpotter].
 * Uses foregroundServiceType="microphone" so the system does not revoke mic access
 * when the screen turns off or the app is placed in the background.
 */
class WakeWordForegroundService : Service() {

    private val TAG = "WakeWordFgsService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeWordSpotter: WakeWordSpotter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "WakeWordForegroundService onCreate")

        try {
            instance = this
            if (!PermissionUtils.hasRecordAudioPermission(this)) {
                AppLogger.w(TAG, "RECORD_AUDIO permission missing; cannot start WakeWordForegroundService.")
                stopSelf()
                return
            }

            createNotificationChannel()
            safeStartForeground("Initializing wake-word detection...")

            val spotter = WakeWordSpotter(this)
            wakeWordSpotter = spotter
            val started = spotter.start()
            if (!started) {
                AppLogger.e(TAG, "WakeWordSpotter failed to start. Stopping WakeWordForegroundService.")
                updateNotification("Wake-word detection unavailable (see Dev Logs)")
                stopSelf()
                return
            }

            val wakeRepo = com.textmorph.app.data.repository.WakeWordRepository.getInstance(this)
            wakeRepo.wakeWordsState.onEach { words ->
                val activeWords = words.filter { it.isEnabled }
                val activeNames = if (activeWords.isNotEmpty()) {
                    activeWords.joinToString(" & ") { "'${it.displayName}'" }
                } else {
                    "none"
                }
                val keywordsStr = wakeRepo.buildActiveKeywordsString()
                spotter.updateActiveKeywords(keywordsStr)
                if (!VoiceCommandManager.isDictating.value) {
                    val status = if (activeWords.isNotEmpty()) {
                        "Listening for $activeNames"
                    } else {
                        "Wake-word detector idle"
                    }
                    updateNotification(status)
                }
            }.launchIn(serviceScope)

            VoiceCommandManager.isDictating.onEach { isDictating ->
                val activeWords = wakeRepo.wakeWordsState.value.filter { it.isEnabled }
                val activeNames = if (activeWords.isNotEmpty()) {
                    activeWords.joinToString(" & ") { "'${it.displayName}'" }
                } else {
                    "none"
                }
                val status = if (isDictating) {
                    "Processing voice command..."
                } else {
                    if (activeWords.isNotEmpty()) "Listening for $activeNames" else "Wake-word detector idle"
                }
                updateNotification(status)
            }.launchIn(serviceScope)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Fatal failure during WakeWordForegroundService onCreate: ${t.message}", t)
            try {
                updateNotification("Wake-word error (see Dev Logs)")
            } catch (ignored: Throwable) {
                AppLogger.w(TAG, "Failed posting error notification: ${ignored.message}")
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "onStartCommand received (flags=$flags, startId=$startId)")
        return try {
            if (!PermissionUtils.hasRecordAudioPermission(this)) {
                AppLogger.w(TAG, "RECORD_AUDIO missing onStartCommand. Stopping service.")
                stopSelf()
                START_NOT_STICKY
            } else {
                START_STICKY
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error in onStartCommand: ${t.message}", t)
            START_NOT_STICKY
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TextMorph Wake-Word Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps offline wake-word detector active for 'Computer' and 'Hey Tamim'."
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
            .setContentTitle("TextMorph Voice Trigger")
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
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AppLogger.i(TAG, "Foreground service started successfully with microphone type.")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed starting foreground service: ${t.message}", t)
        }
    }

    private fun updateNotification(statusText: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed updating wake-word notification: ${t.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "WakeWordForegroundService onDestroy: releasing wake-word spotter.")
        if (instance == this) {
            instance = null
        }
        try {
            serviceScope.cancel()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error cancelling serviceScope in onDestroy: ${t.message}")
        }
        try {
            wakeWordSpotter?.release()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error releasing wakeWordSpotter in onDestroy: ${t.message}")
        }
        wakeWordSpotter = null
    }

    companion object {
        const val CHANNEL_ID = "textmorph_wakeword_channel"
        const val NOTIFICATION_ID = 2002

        @Volatile
        private var instance: WakeWordForegroundService? = null

        fun getInstance(): WakeWordForegroundService? = instance

        fun pauseListening() {
            instance?.wakeWordSpotter?.pauseListening()
        }

        fun resumeListening() {
            instance?.wakeWordSpotter?.resumeListening()
        }

        fun startService(context: Context) {
            try {
                val intent = Intent(context, WakeWordForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLogger.d("WakeWordForegroundService", "Dispatched startService intent.")
            } catch (t: Throwable) {
                AppLogger.e("WakeWordForegroundService", "startService failed: ${t.message}", t)
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, WakeWordForegroundService::class.java)
                context.stopService(intent)
                AppLogger.d("WakeWordForegroundService", "Dispatched stopService intent.")
            } catch (t: Throwable) {
                AppLogger.e("WakeWordForegroundService", "stopService failed: ${t.message}", t)
            }
        }
    }
}
