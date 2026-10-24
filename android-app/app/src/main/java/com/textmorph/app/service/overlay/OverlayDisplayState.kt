package com.textmorph.app.service.overlay

/**
 * Unified display states for the TextMorph floating overlay entity.
 */
enum class OverlayDisplayState {
    /** Closed 56x56 dp draggable circular Messenger-style bubble head */
    COLLAPSED_BUBBLE,

    /** Horizontal quick-transform prompt ribbon with the 'Open Chat' button */
    EXPANDED_TOOLBAR,

    /** Floating interactive chat window over the active screen */
    EXPANDED_CHAT,

    /** Bubble head smoothly animating across the screen toward target element coordinates */
    AUTOMATING_ACTION
}
