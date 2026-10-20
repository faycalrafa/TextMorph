package com.textmorph.app

import android.app.Application
import com.textmorph.app.util.AppLogger

class TextMorphApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.i("TextMorphApp", "TextMorph Application initialized successfully.")
    }
}
