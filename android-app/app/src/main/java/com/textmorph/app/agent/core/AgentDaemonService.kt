package com.textmorph.app.agent.core

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
import com.textmorph.app.agent.recovery.CrashRecoveryManager
import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.service.overlay.TextMorphAccessibilityService
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Decoupled Android Foreground Service hosting the Autonomous Agent Engine.
 * Ensures agent missions survive Activity backgrounding and screen rotations.
 */
class AgentDaemonService : Service() {

    private val TAG = "AgentDaemonService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var controller: DaemonExecutionController

    inner class LocalBinder : Binder() {
        fun getService(): AgentDaemonService = this@AgentDaemonService
        fun getController(): DaemonExecutionController = controller
    }

    companion object {
        const val NOTIFICATION_ID = 2002
        const val CHANNEL_ID = "channel_textmorph_agent_daemon"
        const val ACTION_STOP_MISSION = "com.textmorph.app.ACTION_STOP_MISSION"

        fun startService(context: Context) {
            val intent = Intent(context, AgentDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "AgentDaemonService onCreate()")
        acquireWakeLock()
        createNotificationChannel()
        safeStartForeground("TextMorph Autonomous Agent Active")

        // 1. Startup crash recovery sweep
        serviceScope.launch {
            CrashRecoveryManager.getInstance(applicationContext).sweepAndRecover()
        }

        // 2. Initialize DaemonExecutionController
        controller = DaemonExecutionController(
            context = applicationContext,
            engineProvider = { LiteRtLmEngineManager.getInstance(applicationContext) },
            screenToolProvider = {
                val service = TextMorphAccessibilityService.getInstance()
                if (service != null) {
                    ScreenAutomationTool(
                        controllerProvider = { TextMorphAccessibilityService.getScreenAutomationController() },
                        serviceProvider = { service }
                    )
                } else null
            },
            controllerProvider = { TextMorphAccessibilityService.getScreenAutomationController() }
        )

        // 3. Observe mission state for live notification updates
        serviceScope.launch {
            controller.missionState.collect { state ->
                if (state.isRunning) {
                    updateNotification("🤖 Step ${state.currentStep}/${state.maxSteps}: ${state.goal.take(30)}...")
                } else {
                    updateNotification("TextMorph Autonomous Agent Ready")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MISSION) {
            AppLogger.i(TAG, "Received ACTION_STOP_MISSION from notification")
            controller.missionState.value.sessionId?.let { controller.cancelMission(it) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        AppLogger.i(TAG, "AgentDaemonService onDestroy()")
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun safeStartForeground(title: String) {
        val notification = buildNotification(title)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed starting foreground service: ${t.message}", t)
        }
    }

    private fun updateNotification(content: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildNotification(content))
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed updating notification: ${t.message}")
        }
    }

    private fun buildNotification(content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pLaunch = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, AgentDaemonService::class.java).apply { action = ACTION_STOP_MISSION }
        val pStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TextMorph Agent Engine")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pLaunch)
            .addAction(android.R.drawable.ic_delete, "Stop Task", pStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TextMorph Agent Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of autonomous mobile tasks"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TextMorph:AgentDaemonLock")?.apply {
                acquire(10 * 60 * 1000L) // 10 minutes max safety timeout
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed acquiring WakeLock: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed releasing WakeLock: ${t.message}")
        }
    }
}
