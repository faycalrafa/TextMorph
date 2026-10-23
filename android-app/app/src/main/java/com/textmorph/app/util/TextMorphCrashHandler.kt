package com.textmorph.app.util

import android.content.Context

class TextMorphCrashHandler(
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    private val TAG = "TextMorphCrashHandler"

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            AppLogger.e(TAG, "FATAL CRASH on thread '${thread.name}': ${throwable.message}", throwable)
            val crashLogManager = CrashLogManager.getInstance(context)
            crashLogManager.recordCrash(
                thread = thread,
                throwable = throwable,
                crashType = "UNCAUGHT_EXCEPTION"
            )
        } catch (fatalEx: Throwable) {
            android.util.Log.e(TAG, "Error in TextMorphCrashHandler: ${fatalEx.message}", fatalEx)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(context: Context) {
            val currentDefault = Thread.getDefaultUncaughtExceptionHandler()
            if (currentDefault !is TextMorphCrashHandler) {
                val handler = TextMorphCrashHandler(currentDefault, context.applicationContext)
                Thread.setDefaultUncaughtExceptionHandler(handler)
                AppLogger.i("TextMorphCrashHandler", "TextMorphCrashHandler installed successfully as default UncaughtExceptionHandler.")
            }
        }
    }
}
