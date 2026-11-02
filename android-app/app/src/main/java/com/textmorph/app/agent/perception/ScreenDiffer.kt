package com.textmorph.app.agent.perception

import com.textmorph.app.service.overlay.UiElementNode
import com.textmorph.app.util.AppLogger

/**
 * Computes semantic layout tree diffs between consecutive perception frames.
 * Allows the agent to isolate newly appeared buttons, modals, or state changes
 * without re-prompting the LLM with hundreds of unchanged elements.
 */
object ScreenDiffer {

    private const val TAG = "ScreenDiffer"

    fun computeDiff(
        previousNodes: List<UiElementNode>,
        currentNodes: List<UiElementNode>,
        activePackage: String
    ): ScreenDiffResult {
        return try {
            val prevHash = if (previousNodes.isNotEmpty()) ScreenHashUtils.computeScreenHash(previousNodes, activePackage) else null
            val currHash = ScreenHashUtils.computeScreenHash(currentNodes, activePackage)

            if (prevHash != null && prevHash == currHash) {
                return ScreenDiffResult(
                    isIdentical = true,
                    activePackage = activePackage,
                    currentHash = currHash,
                    previousHash = prevHash,
                    addedElements = emptyList(),
                    removedElementsCount = 0,
                    totalElementsCount = currentNodes.size
                )
            }

            // Create lookup set of previous element signatures
            val prevSignatures = previousNodes.map { nodeSignature(it) }.toHashSet()
            val currSignatures = currentNodes.map { nodeSignature(it) }.toHashSet()

            val added = currentNodes.filter { nodeSignature(it) !in prevSignatures }
            val removedCount = previousNodes.count { nodeSignature(it) !in currSignatures }

            ScreenDiffResult(
                isIdentical = false,
                activePackage = activePackage,
                currentHash = currHash,
                previousHash = prevHash,
                addedElements = added,
                removedElementsCount = removedCount,
                totalElementsCount = currentNodes.size
            )
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error computing screen tree diff: ${t.message}", t)
            ScreenDiffResult(
                isIdentical = false,
                activePackage = activePackage,
                currentHash = "diff_err_${System.currentTimeMillis()}",
                previousHash = null,
                addedElements = currentNodes,
                removedElementsCount = 0,
                totalElementsCount = currentNodes.size
            )
        }
    }

    private fun nodeSignature(node: UiElementNode): String {
        return "${node.displayText}|${node.className}|${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}"
    }
}
