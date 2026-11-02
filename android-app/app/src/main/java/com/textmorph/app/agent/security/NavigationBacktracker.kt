package com.textmorph.app.agent.security

import com.textmorph.app.util.AppLogger
import java.util.Stack

data class NavigationBreadcrumb(
    val stepIndex: Int,
    val packageName: String,
    val screenHash: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manages navigation breadcrumbs during autonomous mobile screen exploration.
 * Enables the agent to recover from dead ends by unwinding the navigation stack.
 */
class NavigationBacktracker {

    private val TAG = "NavigationBacktracker"
    private val stack = Stack<NavigationBreadcrumb>()

    fun pushBreadcrumb(
        stepIndex: Int,
        packageName: String,
        screenHash: String,
        title: String
    ) {
        try {
            if (stack.isNotEmpty() && stack.peek().screenHash == screenHash) {
                return // Avoid duplicate entries for identical screens
            }
            val crumb = NavigationBreadcrumb(stepIndex, packageName, screenHash, title)
            stack.push(crumb)
            AppLogger.d(TAG, "Pushed breadcrumb: $packageName ($title) [Stack depth: ${stack.size}]")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error pushing navigation breadcrumb: ${t.message}")
        }
    }

    fun popBreadcrumb(): NavigationBreadcrumb? {
        return try {
            if (stack.isNotEmpty()) {
                val popped = stack.pop()
                AppLogger.d(TAG, "Popped breadcrumb: ${popped.packageName} [Remaining: ${stack.size}]")
                popped
            } else null
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error popping navigation breadcrumb: ${t.message}")
            null
        }
    }

    val canBacktrack: Boolean
        get() = stack.size > 1

    fun clear() {
        stack.clear()
    }
}
