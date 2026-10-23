package com.textmorph.app.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

object TextMorphCoroutineExceptionHandler {

    fun create(tag: String, actionDescription: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { context: CoroutineContext, throwable: Throwable ->
            AppLogger.e(tag, "Uncaught coroutine failure during $actionDescription (context=$context): ${throwable.message}", throwable)
            AppErrorBus.postError(
                title = "Error in $tag",
                message = "Failed during $actionDescription: ${throwable.message}",
                throwable = throwable
            )
        }
    }
}
