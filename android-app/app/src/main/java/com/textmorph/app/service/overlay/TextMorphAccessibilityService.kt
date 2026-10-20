package com.textmorph.app.service.overlay

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.textmorph.app.util.AppLogger

class TextMorphAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.i("AccessibilityService", "TextMorph Accessibility Service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Will be implemented in Module 4
    }

    override fun onInterrupt() {
        AppLogger.w("AccessibilityService", "TextMorph Accessibility Service interrupted.")
    }
}
