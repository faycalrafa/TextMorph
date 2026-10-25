package com.textmorph.app.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.textmorph.app.util.AppLogger

/**
 * Tool for launching installed applications and web URLs.
 */
class AppLauncherTool(private val context: Context) {

    private val TAG = "AppLauncherTool"

    companion object {
        val COMMON_PACKAGE_ALIASES = mapOf(
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "whatsapp" to "com.whatsapp",
            "settings" to "com.android.settings",
            "camera" to "com.google.android.GoogleCamera",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "photos" to "com.google.android.apps.photos",
            "spotify" to "com.spotify.music",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "files" to "com.google.android.documentsui",
            "telegram" to "org.telegram.messenger",
            "messages" to "com.google.android.apps.messaging",
            "messenger" to "com.facebook.orca",
            "facebook messenger" to "com.facebook.orca",
            "google messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "contacts" to "com.google.android.contacts",
            "phone" to "com.google.android.dialer"
        )
    }

    fun openApp(appName: String): Result<String> {
        val cleanName = appName.trim()
        if (cleanName.isBlank()) {
            return Result.failure(IllegalArgumentException("App name cannot be empty."))
        }

        try {
            val pm = context.packageManager

            // 1. Check common package aliases first
            val aliasPkg = COMMON_PACKAGE_ALIASES[cleanName.lowercase()]
            if (aliasPkg != null) {
                val aliasIntent = pm.getLaunchIntentForPackage(aliasPkg)
                if (aliasIntent != null) {
                    aliasIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(aliasIntent)
                    AppLogger.i(TAG, "Launched app via alias: $cleanName -> $aliasPkg")
                    return Result.success("Opened $cleanName")
                }
            }

            // 2. Direct package match
            val directIntent = pm.getLaunchIntentForPackage(cleanName)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(directIntent)
                AppLogger.i(TAG, "Launched app via direct package: $cleanName")
                return Result.success("Opened $cleanName")
            }

            // 3. Query launcher resolve activities (standard Android launcher way)
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
            for (ri in resolveInfos) {
                val label = ri.loadLabel(pm).toString()
                val pkg = ri.activityInfo.packageName
                if (label.equals(cleanName, ignoreCase = true) || pkg.equals(cleanName, ignoreCase = true)) {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        AppLogger.i(TAG, "Opened app via launcher query exact match: $label ($pkg)")
                        return Result.success("Opened $label")
                    }
                }
            }

            // Substring match on launcher resolve activities
            for (ri in resolveInfos) {
                val label = ri.loadLabel(pm).toString()
                val pkg = ri.activityInfo.packageName
                if (label.contains(cleanName, ignoreCase = true) || pkg.contains(cleanName, ignoreCase = true)) {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        AppLogger.i(TAG, "Opened app via launcher query substring match: $label ($pkg)")
                        return Result.success("Opened $label")
                    }
                }
            }

            // 4. Fallback: scan installed apps
            val installed = pm.getInstalledApplications(0)
            for (app in installed) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.contains(cleanName, ignoreCase = true) || app.packageName.contains(cleanName, ignoreCase = true)) {
                    val intent = pm.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        AppLogger.i(TAG, "Opened app via installed list: $label (${app.packageName})")
                        return Result.success("Opened $label")
                    }
                }
            }

            // 5. Web fallback for media/search services
            if (cleanName.contains("youtube", ignoreCase = true)) {
                return openUrl("https://youtube.com")
            } else if (cleanName.contains("map", ignoreCase = true)) {
                return openUrl("https://maps.google.com")
            }

            AppLogger.w(TAG, "Could not find installed app matching '$cleanName'")
            return Result.failure(Exception("Could not find app '$cleanName' on this device."))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed launching app '$cleanName': ${e.message}", e)
            return Result.failure(e)
        }
    }

    fun openPackage(packageName: String): Result<String> {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Result.success("Launched $packageName")
            } else {
                Result.failure(Exception("Package $packageName not found or has no launchable Activity."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openUrl(url: String): Result<String> {
        return try {
            val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else url
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.i(TAG, "Opened URL: $cleanUrl")
            Result.success("Opened $cleanUrl")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error opening URL '$url': ${e.message}", e)
            Result.failure(e)
        }
    }
}
