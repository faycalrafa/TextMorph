package com.textmorph.app

import android.app.Application
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.CrashLogManager
import com.textmorph.app.util.NativeFlightRecorder
import com.textmorph.app.util.TextMorphCrashHandler

class TextMorphApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        TextMorphCrashHandler.install(this)
        val crashLogManager = CrashLogManager.getInstance(this)
        NativeFlightRecorder.checkAndRecoverPreviousCrash(this, crashLogManager)
        AppLogger.i("TextMorphApp", "TextMorph Application initialized successfully with CrashHandler and Persistent Logging.")
    }
}
