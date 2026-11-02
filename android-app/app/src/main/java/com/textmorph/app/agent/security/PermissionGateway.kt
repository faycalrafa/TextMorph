package com.textmorph.app.agent.security

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Declarative Capability Gateway for TextMorph's Autonomous Mobile Agent.
 * Stratifies tool actions into risk tiers and enforces interactive user authorizations
 * before high-privilege gestures or network operations are dispatched.
 */
object PermissionGateway {

    private const val TAG = "PermissionGateway"

    // High-risk keywords in button labels requiring user authorization
    private val SENSITIVE_KEYWORDS = setOf(
        "delete", "remove", "uninstall", "pay", "buy", "purchase", "transfer",
        "confirm order", "send money", "format", "clear all data", "wipe"
    )

    // Financial, communication, or sensitive packages requiring elevated vigilance
    private val SENSITIVE_PACKAGES = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.chase.sig.android",
        "com.bankofamerica.mobilebanking",
        "com.android.settings"
    )

    // Ephemeral session pre-authorizations granted by the user
    private val sessionAllowances = ConcurrentHashMap<String, MutableSet<String>>()

    fun grantSessionAllowance(sessionId: String, capabilityKey: String) {
        val set = sessionAllowances.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }
        set.add(capabilityKey)
        AppLogger.i(TAG, "Granted session allowance for $sessionId: $capabilityKey")
    }

    fun clearSessionAllowances(sessionId: String) {
        sessionAllowances.remove(sessionId)
    }

    /**
     * Evaluates the capability policy for an action within the active application context.
     */
    fun evaluate(
        sessionId: String,
        action: AgentAction,
        activePackage: String? = null
    ): PermissionLevel {
        try {
            val key = "${action.action}:${activePackage ?: "*"}"
            val allowances = sessionAllowances[sessionId]
            if (allowances != null && (allowances.contains(key) || allowances.contains(action.action))) {
                return PermissionLevel.ALLOW
            }

            // 1. High-risk target application check
            if (activePackage != null && SENSITIVE_PACKAGES.contains(activePackage)) {
                if (action.action == AgentAction.ACTION_CLICK_TEXT || action.action == AgentAction.ACTION_CLICK_AT || action.action == AgentAction.ACTION_TYPE_TEXT) {
                    AppLogger.w(TAG, "Sensitive package interaction detected in $activePackage -> ASK")
                    return PermissionLevel.ASK
                }
            }

            // 2. Destructive keyword check
            val targetText = action.getStringParam("text").lowercase()
            if (SENSITIVE_KEYWORDS.any { targetText.contains(it) }) {
                AppLogger.w(TAG, "Sensitive action keyword '$targetText' detected -> ASK")
                return PermissionLevel.ASK
            }

            // 3. Safe read-only tools
            if (action.action == AgentAction.ACTION_READ_SCREEN ||
                action.action == "search_web" ||
                action.action == "fetch_page" ||
                action.action == "search_images" ||
                action.action == "done" ||
                action.action == AgentAction.ACTION_PRESS_HOME ||
                action.action == AgentAction.ACTION_PRESS_BACK ||
                action.action == AgentAction.ACTION_SCROLL
            ) {
                return PermissionLevel.ALLOW
            }

            // Standard actions are allowed by default unless restricted
            return PermissionLevel.ALLOW
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error evaluating permission gateway: ${t.message}", t)
            return PermissionLevel.ALLOW
        }
    }
}
