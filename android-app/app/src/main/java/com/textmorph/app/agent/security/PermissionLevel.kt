package com.textmorph.app.agent.security

/**
 * Declares the execution authorization policy for agent tool capabilities.
 */
enum class PermissionLevel {
    /**
     * Action is pre-authorized and executes immediately without prompting the user.
     */
    ALLOW,

    /**
     * Pauses the step state machine and prompts the user via an interactive overlay dialog.
     */
    ASK,

    /**
     * Blocked unconditionally; returns a policy denial error to the model.
     */
    DENY
}
