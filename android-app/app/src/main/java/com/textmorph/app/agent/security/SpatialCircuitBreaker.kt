package com.textmorph.app.agent.security

import com.textmorph.app.util.AppLogger

data class CircuitBreakerDecision(
    val allowed: Boolean,
    val reason: String? = null,
    val selfCorrectionSuggestion: String? = null
)

/**
 * Advanced circuit breaker that detects spatial invariance (unresponsive screens)
 * and package ping-ponging (infinite app-switching loops).
 */
class SpatialCircuitBreaker(
    private val maxIdenticalScreens: Int = 3,
    private val maxPackageSwitches: Int = 4
) {
    private val TAG = "SpatialCircuitBreaker"

    private val screenHashHistory = mutableListOf<String>()
    private val packageHistory = mutableListOf<String>()

    fun recordScreen(hash: String, packageName: String? = null) {
        screenHashHistory.add(hash)
        if (packageName != null) {
            packageHistory.add(packageName)
        }
    }

    fun evaluate(): CircuitBreakerDecision {
        try {
            // 1. Detect spatial screen invariance (e.g. repeated clicks without screen change)
            if (screenHashHistory.size >= maxIdenticalScreens) {
                val lastN = screenHashHistory.takeLast(maxIdenticalScreens)
                val allIdentical = lastN.all { it == lastN.first() && it != "empty_screen" }
                if (allIdentical) {
                    val reason = "Screen layout remained identical for $maxIdenticalScreens consecutive steps despite action dispatches."
                    AppLogger.w(TAG, "Circuit breaker tripped: $reason")
                    return CircuitBreakerDecision(
                        allowed = false,
                        reason = reason,
                        selfCorrectionSuggestion = "Notice: The screen has not changed for 3 steps. Your previous actions did not succeed. Try click_at(x, y) coordinates, scroll, or pressBack()."
                    )
                }
            }

            // 2. Detect package ping-ponging (e.g. App A -> App B -> App A -> App B)
            if (packageHistory.size >= maxPackageSwitches) {
                val lastPkgs = packageHistory.takeLast(maxPackageSwitches)
                val uniqueCount = lastPkgs.toSet().size
                if (uniqueCount == 2 && lastPkgs[0] == lastPkgs[2] && lastPkgs[1] == lastPkgs[3]) {
                    val reason = "Detected repetitive package ping-pong loop between '${lastPkgs[0]}' and '${lastPkgs[1]}'."
                    AppLogger.w(TAG, "Circuit breaker tripped: $reason")
                    return CircuitBreakerDecision(
                        allowed = false,
                        reason = reason,
                        selfCorrectionSuggestion = "Notice: You are switching repeatedly between apps without making progress. Please remain in the target app or conclude the task."
                    )
                }
            }

            return CircuitBreakerDecision(allowed = true)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error in spatial circuit breaker evaluation: ${t.message}", t)
            return CircuitBreakerDecision(allowed = true)
        }
    }

    fun reset() {
        screenHashHistory.clear()
        packageHistory.clear()
    }
}
