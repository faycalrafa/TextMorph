package com.textmorph.app.agent.guardrails

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.util.AppLogger
import java.security.MessageDigest

sealed class GuardrailDecision {
    object Allowed : GuardrailDecision()
    data class LoopDetected(val feedbackPrompt: String) : GuardrailDecision()
    data class BudgetExceeded(val message: String) : GuardrailDecision()

    val allowed: Boolean get() = this is Allowed
    val reason: String get() = when (this) {
        is Allowed -> ""
        is LoopDetected -> feedbackPrompt
        is BudgetExceeded -> message
    }
}

/**
 * Execution control and circuit breaker.
 * Computes deterministic hashes of (tool_name, normalized_args) to block back-to-back
 * identical tool calls and enforces strict turn/hop budgets to prevent runaway execution on 1B–2B SLMs.
 */
class ExecutionGuardrail(val maxHops: Int = 5) {

    val maxStepBudget: Int get() = maxHops

    private val TAG = "ExecutionGuardrail"
    private val executedCallSignatures = mutableSetOf<String>()
    var currentHops = 0
        private set

    fun reset() {
        executedCallSignatures.clear()
        currentHops = 0
    }

    fun evaluate(toolName: String, params: Map<String, kotlinx.serialization.json.JsonElement>): GuardrailDecision {
        return evaluate(AgentAction(action = toolName, params = params))
    }

    /**
     * Inspects a candidate action against loop history and hop limits.
     */
    fun evaluate(action: AgentAction): GuardrailDecision {
        currentHops++
        if (currentHops > maxHops) {
            val msg = "Tool execution budget exceeded ($maxHops hops)."
            AppLogger.w(TAG, msg)
            return GuardrailDecision.BudgetExceeded(msg)
        }

        val signature = computeCallSignature(action)
        AppLogger.d(TAG, "Evaluating call signature: $signature (hop $currentHops/$maxHops)")

        if (executedCallSignatures.contains(signature)) {
            val feedback = """
                [Harness Notice]: You already executed '${action.action}' with those exact parameters.
                Do not repeat this action. Review your existing observations and provide your final response to the user,
                or select a different action with new parameters.
            """.trimIndent()
            AppLogger.w(TAG, "Blocked identical tool invocation: $signature")
            return GuardrailDecision.LoopDetected(feedback)
        }

        executedCallSignatures.add(signature)
        return GuardrailDecision.Allowed
    }

    fun computeCallSignature(action: AgentAction): String {
        val toolName = action.action.lowercase().trim()
        val sortedParams = action.params.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value.toString().lowercase().trim()}" }
        val raw = "$toolName?$sortedParams"

        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
            digest.fold("") { str, it -> str + "%02x".format(it) }
        } catch (e: Exception) {
            raw
        }
    }
}
