package com.textmorph.app.service.overlay

import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event bus dispatching media selection events from the transparent trampoline
 * activity back to active listeners (such as FloatingChatBubbleContent).
 */
object OverlayMediaPickerBus {
    private val _pickedMediaFlow = MutableSharedFlow<List<Uri>>(extraBufferCapacity = 4)
    val pickedMediaFlow: SharedFlow<List<Uri>> = _pickedMediaFlow.asSharedFlow()

    fun emitPicked(uris: List<Uri>) {
        _pickedMediaFlow.tryEmit(uris)
    }
}
