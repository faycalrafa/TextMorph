package com.textmorph.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter

data class AppError(
    val title: String,
    val message: String,
    val stackTrace: String? = null
)

object AppErrorBus {
    private val _currentError = MutableStateFlow<AppError?>(null)
    val currentError: StateFlow<AppError?> = _currentError.asStateFlow()

    fun postError(title: String, message: String, throwable: Throwable? = null) {
        val stack = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        AppLogger.e("AppErrorBus", "$title: $message", throwable)
        _currentError.value = AppError(title, message, stack)
    }

    fun dismiss() {
        _currentError.value = null
    }
}
