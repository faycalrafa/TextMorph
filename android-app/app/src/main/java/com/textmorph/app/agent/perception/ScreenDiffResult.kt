package com.textmorph.app.agent.perception

import com.textmorph.app.service.overlay.UiElementNode

/**
 * Result model representing the structural difference between two consecutive screen perception passes.
 */
data class ScreenDiffResult(
    val isIdentical: Boolean,
    val activePackage: String,
    val currentHash: String,
    val previousHash: String?,
    val addedElements: List<UiElementNode>,
    val removedElementsCount: Int,
    val totalElementsCount: Int
) {
    val hasMeaningfulChanges: Boolean
        get() = !isIdentical && (addedElements.isNotEmpty() || removedElementsCount > 0)
}
