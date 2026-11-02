package com.textmorph.app.agent.inbox

/**
 * Declares how an admitted user prompt or trigger interacts with the agent execution engine.
 */
enum class AdmissionMode {
    /**
     * Injected immediately into the active step boundary.
     * Guides, corrects, or redirects the running agent without aborting overall task progress.
     */
    STEER,

    /**
     * Queued until the active task, plan, or autonomous goal reaches an IDLE state.
     * Standard conversational sequential processing.
     */
    QUEUE,

    /**
     * Forces an immediate, graceful cancellation of the active step and resets state machine to IDLE.
     */
    ABORT
}
