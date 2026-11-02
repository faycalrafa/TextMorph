package com.textmorph.app.agent.perception

import com.textmorph.app.service.overlay.UiElementNode
import java.security.MessageDigest

/**
 * Utility for generating deterministic cryptographic layout hashes (SHA-256) of screen element trees.
 * Used for instant spatial loop detection and perceptual caching.
 */
object ScreenHashUtils {

    fun computeScreenHash(nodes: List<UiElementNode>, packageName: String? = null): String {
        if (nodes.isEmpty()) return "empty_screen"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            if (packageName != null) {
                digest.update(packageName.toByteArray(Charsets.UTF_8))
            }
            for (node in nodes) {
                val line = "${node.displayText}|${node.className}|${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}|${node.isClickable}|${node.isEditable}"
                digest.update(line.toByteArray(Charsets.UTF_8))
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "hash_err_${nodes.size}_${System.currentTimeMillis()}"
        }
    }
}
