package com.textmorph.app.agent.voice

/**
 * Lifecycle states for hands-free and read-aloud voice interactions.
 */
enum class VoiceChatState {
    IDLE,
    GREETING,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * Active context target for voice sessions.
 */
enum class VoiceSessionMode {
    IN_APP_CHAT,
    SCREEN_ASSIST
}
