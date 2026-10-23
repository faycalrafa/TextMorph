package com.textmorph.app.util

import kotlinx.serialization.Serializable

@Serializable
data class CrashReport(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val crashTimeFormatted: String,
    val crashType: String = "UNCAUGHT_EXCEPTION", // "UNCAUGHT_EXCEPTION", "NATIVE_CRASH_OR_KILL", "COROUTINE_FAILURE"
    val exceptionClass: String,
    val message: String,
    val stackTrace: String,
    val threadName: String,
    val deviceModel: String,
    val androidVersion: String,
    val memoryUsageMb: String,
    val activeModelName: String? = null,
    val activeBackend: String? = null,
    val recentLogs: List<String> = emptyList()
)
