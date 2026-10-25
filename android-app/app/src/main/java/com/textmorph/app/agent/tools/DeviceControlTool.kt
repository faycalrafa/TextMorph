package com.textmorph.app.agent.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import com.textmorph.app.util.AppLogger

/**
 * Tool for managing device settings (audio volume, display brightness, alarms, timers).
 */
class DeviceControlTool(private val context: Context) {

    private val TAG = "DeviceControlTool"

    fun setVolume(levelPercent: Int): Result<String> {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return Result.failure(Exception("AudioManager not available"))

            val clamped = levelPercent.coerceIn(0, 100)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((clamped / 100f) * maxVol).toInt()

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            AppLogger.i(TAG, "Set volume to $clamped% (index $target/$maxVol)")
            Result.success("Volume set to $clamped%")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed setting volume: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun setBrightness(levelPercent: Int): Result<String> {
        val clamped = levelPercent.coerceIn(0, 100)
        return try {
            if (Settings.System.canWrite(context)) {
                val value = ((clamped / 100f) * 255).toInt()
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    value
                )
                AppLogger.i(TAG, "Set screen brightness to $clamped% (raw $value)")
                Result.success("Brightness set to $clamped%")
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Result.success("Please grant 'Modify System Settings' permission to allow automatic brightness control.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error controlling brightness: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String?): Result<String> {
        return try {
            val h = hour.coerceIn(0, 23)
            val m = minute.coerceIn(0, 59)
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val timeStr = String.format("%02d:%02d", h, m)
            val labelStr = if (!label.isNullOrBlank()) " ($label)" else ""
            AppLogger.i(TAG, "Set alarm for $timeStr$labelStr")
            Result.success("Alarm set for $timeStr$labelStr")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error setting alarm: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun setTimer(seconds: Int, label: String?): Result<String> {
        return try {
            val sec = seconds.coerceAtLeast(1)
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, sec)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val mins = sec / 60
            val remainderSecs = sec % 60
            val timeStr = if (mins > 0) "${mins}m ${remainderSecs}s" else "${sec}s"
            val labelStr = if (!label.isNullOrBlank()) " ($label)" else ""
            AppLogger.i(TAG, "Set timer for $timeStr$labelStr")
            Result.success("Timer set for $timeStr$labelStr")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error setting timer: ${e.message}", e)
            Result.failure(e)
        }
    }
}
