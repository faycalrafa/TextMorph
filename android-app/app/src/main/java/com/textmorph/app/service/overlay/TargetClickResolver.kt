package com.textmorph.app.service.overlay

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.textmorph.app.util.AppLogger
import kotlin.math.abs

/**
 * Precision resolution algorithms for mobile agent interactions:
 * 1. Deepest-leaf text matching to prevent erroneously clicking large parent containers.
 * 2. Magnetic snapping to pull slightly drifting coordinates directly onto interactive targets.
 */
object TargetClickResolver {

    private const val TAG = "TargetClickResolver"

    data class SnapResult(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val snappedX: Float,
        val snappedY: Float,
        val isSnapped: Boolean
    )

    /**
     * Resolves the best matching node using 4-pass precision disambiguation.
     */
    fun resolveMatchingNode(root: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        return findDeepestMatchingNode(root, targetText, exactOnly = true, skipEditable = true)
            ?: findDeepestMatchingNode(root, targetText, exactOnly = false, skipEditable = true)
            ?: findDeepestMatchingNode(root, targetText, exactOnly = true, skipEditable = false)
            ?: findDeepestMatchingNode(root, targetText, exactOnly = false, skipEditable = false)
    }

    /**
     * Finds the deepest/most specific leaf node matching [targetText], avoiding parent containers.
     */
    fun findDeepestMatchingNode(
        node: AccessibilityNodeInfo,
        targetText: String,
        exactOnly: Boolean,
        skipEditable: Boolean
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        collectMatchingNodes(node, targetText, exactOnly, skipEditable, candidates)
        if (candidates.isEmpty()) return null

        // Select candidate with the smallest bounding box area (most specific leaf element)
        val best = candidates.minByOrNull { it.second }?.first
        for ((cand, _) in candidates) {
            if (cand != best) {
                try { cand.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Recycle: ${e.message}") }
            }
        }
        return best
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        targetText: String,
        exactOnly: Boolean,
        skipEditable: Boolean,
        list: MutableList<Pair<AccessibilityNodeInfo, Int>>
    ) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val exactMatch = text.equals(targetText, true) || desc.equals(targetText, true)
        val containsMatch = text.contains(targetText, true) || desc.contains(targetText, true)
        val matches = if (exactOnly) exactMatch else containsMatch

        if (matches && (!skipEditable || !node.isEditable)) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty && r.width() > 0 && r.height() > 0) {
                list.add(Pair(AccessibilityNodeInfo.obtain(node), r.width() * r.height()))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectMatchingNodes(child, targetText, exactOnly, skipEditable, list)
            } finally {
                try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
            }
        }
    }

    /**
     * Checks if coordinates (x, y) hit a clickable node directly, or if they landed slightly below
     * a nearby clickable element (within [maxVerticalDriftPx]), snapping to its center.
     */
    fun findMagneticSnapTarget(
        root: AccessibilityNodeInfo,
        x: Float,
        y: Float,
        maxVerticalDriftPx: Float = 110f
    ): SnapResult? {
        val interactiveNodes = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        collectInteractiveNodes(root, interactiveNodes)
        if (interactiveNodes.isEmpty()) return null

        // 1. Direct hit check
        for ((node, bounds) in interactiveNodes) {
            if (bounds.contains(x.toInt(), y.toInt())) {
                return SnapResult(
                    node = node,
                    bounds = bounds,
                    snappedX = bounds.centerX().toFloat(),
                    snappedY = bounds.centerY().toFloat(),
                    isSnapped = false
                )
            }
        }

        // 2. Magnetic snap: check if tap landed just below an interactive target (y > bounds.bottom)
        var bestCandidate: Pair<AccessibilityNodeInfo, Rect>? = null
        var minDistance = Float.MAX_VALUE

        for ((node, bounds) in interactiveNodes) {
            val dy = y - bounds.bottom
            val dx = abs(x - bounds.centerX())
            val withinHorizontal = x >= (bounds.left - 30) && x <= (bounds.right + 30)

            // Target is directly above the tap point within max vertical drift
            if (dy in 0f..maxVerticalDriftPx && withinHorizontal) {
                val distance = dy + dx * 0.4f
                if (distance < minDistance) {
                    minDistance = distance
                    bestCandidate = Pair(node, bounds)
                }
            }
        }

        return bestCandidate?.let { (node, bounds) ->
            SnapResult(
                node = node,
                bounds = bounds,
                snappedX = bounds.centerX().toFloat(),
                snappedY = bounds.centerY().toFloat(),
                isSnapped = true
            )
        }
    }

    private fun collectInteractiveNodes(
        node: AccessibilityNodeInfo,
        list: MutableList<Pair<AccessibilityNodeInfo, Rect>>
    ) {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (node.isVisibleToUser && (node.isClickable || node.isEditable) && r.width() > 10 && r.height() > 10) {
            list.add(Pair(node, Rect(r)))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectInteractiveNodes(child, list)
            } finally {
                try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
            }
        }
    }
}
