package com.textmorph.app.util

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.textmorph.app.service.overlay.TextMorphAccessibilityService

object PermissionUtils {

    /**
     * Checks if the Accessibility Service is currently active in Android settings.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val expectedComponentName = ComponentName(context, TextMorphAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = enabledServicesSetting.split(":")
            for (service in colonSplitter) {
                val component = ComponentName.unflattenFromString(service)
                if (component != null && component == expectedComponentName) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            AppLogger.e("PermissionUtils", "Error checking accessibility service status: ${e.message}", e)
            false
        }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e("PermissionUtils", "Failed to launch accessibility settings: ${e.message}", e)
        }
    }

    /**
     * Checks if the app is excluded from battery optimizations (necessary for local LLM server).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            AppLogger.e("PermissionUtils", "Error checking battery optimization status: ${e.message}", e)
            false
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e("PermissionUtils", "Failed to request battery optimization ignore: ${e.message}", e)
        }
    }

    /**
     * Checks if MANAGE_EXTERNAL_STORAGE is granted on Android 11+ for zero-copy Downloads/ mmap.
     */
    fun isExternalStorageManager(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun hasAllFilesAccess(): Boolean = isExternalStorageManager()

    fun requestManageAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e("PermissionUtils", "Failed to request all files access permission: ${e.message}", e)
            }
        }
    }

    fun requestAllFilesAccess(context: Context) = requestManageAllFilesAccess(context)

    /**
     * Checks notification permission (POST_NOTIFICATIONS on Android 13+).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        }
    }
}
