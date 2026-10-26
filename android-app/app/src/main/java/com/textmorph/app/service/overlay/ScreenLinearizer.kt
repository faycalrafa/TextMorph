package com.textmorph.app.service.overlay

/**
 * High-efficiency screen linearizer and serializer for LLM screen perception.
 * Supports standard semantic hierarchy trees and PrivateAgent-style compressed,
 * keyword-targeted screen dumps.
 */
object ScreenLinearizer {

    private val STOP_WORDS = setOf(
        "to", "and", "the", "a", "in", "of", "for", "on", "with", "at", "by", "from", "go", "turn", "open"
    )

    private val SYSTEM_NOISE_REGEX = Regex("^\\d{1,2}:\\d{2}$")

    /**
     * Standard indented semantic hierarchy tree for Accessibility Tree mode.
     */
    fun formatSemanticTree(nodes: List<UiElementNode>, currentPackage: String? = null): String {
        if (nodes.isEmpty()) return "Screen is empty or unavailable."

        val sb = StringBuilder()
        if (currentPackage != null) {
            sb.appendLine("Current app: $currentPackage")
        }
        sb.appendLine("Screen elements:")

        for (node in nodes) {
            val indent = "  ".repeat((node.depth).coerceIn(0, 8))
            val text = node.displayText.trim()
            val label = if (text.isNotEmpty()) "\"$text\"" else "(no text)"

            val tags = mutableListOf<String>()
            if (node.isClickable) tags.add("clickable")
            if (node.isEditable) tags.add("editable")
            if (node.isScrollable) tags.add("scrollable")
            val tagStr = if (tags.isNotEmpty()) " {${tags.joinToString(", ")}}" else ""

            val boundsStr = " bounds:[${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}] center:(${node.centerX},${node.centerY})"
            sb.appendLine("$indent[${node.index}] [${node.className}] $label$tagStr$boundsStr")
        }
        return sb.toString().trim()
    }

    /**
     * 1-to-1 port of PrivateAgent's compressed screen dump format.
     * Features task-keyword targeting with an asterisk (*), token-optimized class names,
     * status bar noise filtration, and center coordinates.
     */
    fun formatPrivateAgentWay(
        nodes: List<UiElementNode>,
        taskQuery: String,
        currentPackage: String? = null
    ): String {
        if (nodes.isEmpty()) return "Could not read screen. Make sure accessibility service is enabled."

        val sb = StringBuilder()
        if (currentPackage != null) {
            sb.appendLine("APP: $currentPackage")
        }

        // Extract task keywords for attentional anchoring
        val keywords = taskQuery.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in STOP_WORDS }

        for (node in nodes) {
            var displayText = node.displayText.trim()
            val lowerText = displayText.lowercase()

            // Check if node matches query keywords (attentional anchoring)
            val isQueryRelevant = keywords.any { kw -> lowerText.contains(kw) }

            // Filter out system noise / status bar items, but NEVER filter query-relevant or clickable items
            val isSystemNoiseText = lowerText == "stop macro" ||
                lowerText == "🛑 stop macro" ||
                SYSTEM_NOISE_REGEX.matches(lowerText) ||
                lowerText.contains("do not disturb") ||
                lowerText.contains("three bars")

            val isStatusBarBatteryOrClock = (node.bounds.top <= 120 || currentPackage == "com.android.systemui") &&
                (lowerText.contains("percent") || (lowerText.contains("battery") && !isQueryRelevant))

            if (!isQueryRelevant && !node.isClickable && (isSystemNoiseText || isStatusBarBatteryOrClock)) {
                continue
            }

            if (displayText.isEmpty() && !node.isClickable && !node.isEditable && !node.isScrollable) {
                continue
            }

            // Truncate long text to 50 chars to save tokens
            if (displayText.length > 50) {
                displayText = "${displayText.take(50)}..."
            }

            val tags = mutableListOf<String>()
            if (node.isClickable) tags.add("tap")
            if (node.isEditable) tags.add("edit")
            if (node.isScrollable) tags.add("scroll")
            val tagStr = if (tags.isNotEmpty()) "[${tags.joinToString(",")}]" else ""

            // Simplify class names (PrivateAgent mapping)
            val type = when (node.className) {
                "TextView" -> "text"
                "Button" -> "btn"
                "Switch", "SwitchCompat" -> "toggle"
                "ImageView" -> "img"
                "EditText" -> "input"
                "FrameLayout", "LinearLayout", "RelativeLayout", "ViewGroup" -> "view"
                else -> node.className.lowercase()
            }

            val label = if (displayText.isNotEmpty()) "\"$displayText\"" else ""

            // Task keyword match
            val isTarget = displayText.isNotEmpty() && keywords.any { kw -> lowerText.contains(kw) }
            val targetMark = if (isTarget) "*" else ""

            val boundsStr = " center:(${node.centerX},${node.centerY})"
            val line = "[${node.index}]$targetMark $type $label $tagStr$boundsStr"
                .trim()
                .replace(Regex("\\s+"), " ")

            sb.appendLine(line)
        }
        return sb.toString().trim()
    }
}
