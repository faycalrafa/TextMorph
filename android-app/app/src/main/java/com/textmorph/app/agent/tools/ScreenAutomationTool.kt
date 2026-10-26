package com.textmorph.app.agent.tools

import android.accessibilityservice.AccessibilityService
import com.textmorph.app.service.overlay.ScreenAutomationController
import com.textmorph.app.service.overlay.TextMorphAccessibilityService
import com.textmorph.app.util.AppLogger

/**
 * Tool for executing screen reading, UI element interactions, and gestures.
 */
class ScreenAutomationTool(
    private val controllerProvider: () -> ScreenAutomationController?,
    private val serviceProvider: () -> AccessibilityService?
) {

    private val TAG = "ScreenAutomationTool"

    fun isAccessibilityReady(): Boolean {
        return controllerProvider() != null || serviceProvider() != null
    }

    fun readScreen(query: String = ""): String {
        val ctrl = controllerProvider() ?: return "Accessibility Service is not active. Please enable TextMorph in Accessibility Settings."
        return ctrl.getCompressedScreenDump(query)
    }

    fun readScreenHierarchy(): String {
        val ctrl = controllerProvider() ?: return "Accessibility Service is not active. Please enable TextMorph in Accessibility Settings."
        return ctrl.getSemanticScreenDump()
    }

    fun readScreenPrivateAgent(query: String = ""): String {
        val ctrl = controllerProvider() ?: return "Accessibility Service is not active. Please enable TextMorph in Accessibility Settings."
        return ctrl.getCompressedScreenDump(query)
    }

    suspend fun captureCleanScreenshotBytes(): ByteArray? {
        val service = serviceProvider() as? TextMorphAccessibilityService
            ?: TextMorphAccessibilityService.getInstance()
            ?: return null
        return service.captureScreenBitmapBytes()
    }

    fun clickText(text: String): Boolean {
        val ctrl = controllerProvider() ?: return false
        val success = ctrl.clickByText(text)
        AppLogger.d(TAG, "clickText('$text') result: $success")
        return success
    }

    fun clickElement(index: Int): Boolean {
        val ctrl = controllerProvider() ?: return false
        val success = ctrl.clickElementByIndex(index)
        AppLogger.d(TAG, "clickElement($index) result: $success")
        return success
    }

    fun clickAt(x: Float, y: Float): Boolean {
        val ctrl = controllerProvider() ?: return false
        val success = ctrl.clickAtCoordinates(x, y)
        AppLogger.d(TAG, "clickAt($x, $y) result: $success")
        return success
    }

    fun typeText(text: String, fieldHint: String? = null): Boolean {
        val ctrl = controllerProvider() ?: return false
        val success = ctrl.typeIntoField(text, fieldHint)
        AppLogger.d(TAG, "typeText(length=${text.length}) result: $success")
        return success
    }

    fun pressEnter(): Boolean {
        val ctrl = controllerProvider() ?: return false
        return ctrl.pressImeEnter()
    }

    fun scroll(direction: String = "down"): Boolean {
        val ctrl = controllerProvider() ?: return false
        return ctrl.performScroll(direction)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val ctrl = controllerProvider() ?: return false
        return ctrl.performSwipe(startX, startY, endX, endY, 300)
    }

    fun pressBack(): Boolean {
        val ctrl = controllerProvider()
        return ctrl?.pressBack() ?: (serviceProvider()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true)
    }

    fun pressHome(): Boolean {
        val ctrl = controllerProvider()
        return ctrl?.pressHome() ?: (serviceProvider()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true)
    }

    fun openNotifications(): Boolean {
        val service = serviceProvider() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun getCurrentPackage(): String? {
        return controllerProvider()?.getCurrentPackage()
    }
}
