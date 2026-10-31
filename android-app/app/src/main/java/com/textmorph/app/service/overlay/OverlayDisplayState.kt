package com.textmorph.app.service.overlay

/**
 * Unified display states for the TextMorph floating overlay entity.
 */
enum class OverlayDisplayState {
    /** Closed 56x56 dp draggable circular Messenger-style bubble head */
    COLLAPSED_BUBBLE,

    /** Horizontal quick-transform prompt ribbon with the Quick Agent Request button */
    EXPANDED_TOOLBAR,

    /** Floating companion listening for user voice/agent instruction with ear 👂 icon */
    LISTENING_REQUEST,

    /** Bubble head smoothly animating across the screen toward target element coordinates */
    AUTOMATING_ACTION,

    /** Active live voice Screen Assist session with proactive greeting, 15s watchdog, and TTS */
    VOICE_ASSIST_SESSION
}
