package com.textmorph.app.agent.perception

import com.textmorph.app.service.overlay.ScreenLinearizer
import com.textmorph.app.service.overlay.UiElementNode

/**
 * Token-optimized screen linearizer that integrates semantic tree diffing
 * to reduce token consumption during multi-step screen navigation.
 */
object AttentionalScreenLinearizer {

    fun formatPerceptionPrompt(
        allNodes: List<UiElementNode>,
        diff: ScreenDiffResult?,
        taskGoal: String,
        activePackage: String
    ): String {
        if (allNodes.isEmpty()) return "Screen is empty or unavailable."

        val sb = StringBuilder()
        sb.appendLine("APP: $activePackage")

        // 1. Check if screen is completely unchanged
        if (diff != null && diff.isIdentical) {
            sb.appendLine("SCREEN STATUS: UNCHANGED (Identical layout detected).")
            sb.appendLine("Notice: Your previous action did not change the screen. Consider using coordinate tap click_at(x, y), pressing home, or scrolling.")
            sb.appendLine(ScreenLinearizer.formatPrivateAgentWay(allNodes, taskGoal, activePackage))
            return sb.toString().trim()
        }

        // 2. If significant new elements appeared, summarize delta
        if (diff != null && diff.addedElements.isNotEmpty() && diff.addedElements.size < allNodes.size) {
            sb.appendLine("SCREEN STATUS: Changed (${diff.addedElements.size} new elements appeared, ${diff.removedElementsCount} removed).")
            sb.appendLine("NEW / CHANGED ELEMENTS:")
            for (node in diff.addedElements.take(15)) {
                val label = if (node.displayText.isNotEmpty()) "\"${node.displayText.trim()}\"" else "(unlabeled)"
                val tagStr = if (node.isClickable) " [clickable]" else ""
                val boundsStr = " center:(${node.centerX},${node.centerY})"
                sb.appendLine("  [${node.index}] [${node.className}] $label$tagStr$boundsStr")
            }
            sb.appendLine("FULL CONTEXT SUMMARY:")
        }

        // 3. Append compressed targeted dump
        sb.appendLine(ScreenLinearizer.formatPrivateAgentWay(allNodes, taskGoal, activePackage))
        return sb.toString().trim()
    }
}
