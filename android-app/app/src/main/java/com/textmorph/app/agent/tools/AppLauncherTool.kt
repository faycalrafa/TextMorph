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

    fun openApp(appName: String): Result<String> {
        val cleanName = appName.trim()
        if (cleanName.isBlank()) {
            return Result.failure(IllegalArgumentException("App name cannot be empty."))
        }

        try {
            val pm = context.packageManager
            // 1. Direct package match
            val directIntent = pm.getLaunchIntentForPackage(cleanName)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(directIntent)
                AppLogger.i(TAG, "Launched app via direct package: $cleanName")
                return Result.success("Opened $cleanName")
            }

            // 2. Scan installed apps for label or package substring
            val installed = pm.getInstalledApplications(0)
            var bestMatch: ApplicationInfo? = null

            // Check exact label match first
            for (app in installed) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.equals(cleanName, ignoreCase = true)) {
                    bestMatch = app
                    break
                }
            }

            // Fallback: substring match on label or package
            if (bestMatch == null) {
                for (app in installed) {
                    val label = pm.getApplicationLabel(app).toString()
                    if (label.contains(cleanName, ignoreCase = true) || app.packageName.contains(cleanName, ignoreCase = true)) {
                        bestMatch = app
                        break
                    }
                }
            }

            if (bestMatch != null) {
                val launchIntent = pm.getLaunchIntentForPackage(bestMatch.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    val label = pm.getApplicationLabel(bestMatch).toString()
                    AppLogger.i(TAG, "Opened app: $label (${bestMatch.packageName})")
                    return Result.success("Opened $label")
                }
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
