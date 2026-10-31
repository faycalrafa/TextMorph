package com.textmorph.app.agent.concurrency

import com.textmorph.app.agent.tools.web.ImageSearchResult
import com.textmorph.app.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Metadata record for an image registered in the short-ID aliasing pipeline.
 */
data class ImageAssetRecord(
    val alias: String,       // e.g. "IMG_1"
    val originalUrl: String, // e.g. "https://upload.wikimedia.org/.../front.jpg"
    val title: String,       // e.g. "Sony WH-1000XM5 Front View"
    val sourceUrl: String? = null,
    val registeredAt: Long = System.currentTimeMillis()
)

/**
 * ImageAssetRegistry provides short-ID aliasing (e.g. IMG_1, IMG_2) for image URLs.
 * On-device 1B–2B SLMs have small context windows and frequently corrupt or truncate
 * long CDN URLs when copying them into Markdown. By mapping real URLs to short aliases,
 * the model is shielded from hallucination while allowing workers to cite images naturally:
 * `![Caption](IMG_1)`.
 *
 * Short aliases are deterministically resolved into original verified URLs during
 * ArtifactAssemblyStore compilation and UI Markdown rendering.
 */
object ImageAssetRegistry {

    private const val TAG = "ImageAssetRegistry"

    private val aliasToRecord = ConcurrentHashMap<String, ImageAssetRecord>()
    private val urlToAlias = ConcurrentHashMap<String, String>()
    private val counter = AtomicInteger(1)

    /**
     * Registers a URL and returns a unique short alias (e.g. "IMG_1").
     * Deduplicates identical URLs to preserve consistency.
     */
    fun register(url: String, title: String, sourceUrl: String? = null): String {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return ""

        val existing = urlToAlias[trimmedUrl]
        if (existing != null) {
            return existing
        }

        val nextIndex = counter.getAndIncrement()
        val alias = "IMG_$nextIndex"
        val record = ImageAssetRecord(
            alias = alias,
            originalUrl = trimmedUrl,
            title = title.trim(),
            sourceUrl = sourceUrl?.trim()
        )

        aliasToRecord[alias] = record
        urlToAlias[trimmedUrl] = alias

        AppLogger.d(TAG, "Registered image asset: $alias -> '$title' ($trimmedUrl)")
        return alias
    }

    /**
     * Registers a list of ImageSearchResult items and formats a clean, low-token stub
     * for injection into the LLM conversation context or worker tool observations.
     */
    fun registerAndFormatStub(results: List<ImageSearchResult>): String {
        if (results.isEmpty()) {
            return "No relevant image candidates found for this query."
        }

        val sb = StringBuilder()
        sb.append("[Image Search Results]:\n")
        for (item in results) {
            val alias = register(item.imageUrl, item.title, item.sourceUrl)
            sb.append("- ").append(alias).append(": \"").append(item.title).append("\" (URL safely stored in registry)\n")
        }
        sb.append("To reference an image in your section, write standard Markdown: ![Caption](IMG_1)")
        return sb.toString().trim()
    }

    /**
     * Resolves a short alias ("IMG_1") to its original URL.
     */
    fun resolve(alias: String): String? {
        val cleanAlias = alias.trim().removeSurrounding("(", ")").removeSurrounding("[", "]")
        return aliasToRecord[cleanAlias]?.originalUrl
    }

    /**
     * Retrieves the full asset record for an alias.
     */
    fun getRecord(alias: String): ImageAssetRecord? {
        val cleanAlias = alias.trim().removeSurrounding("(", ")").removeSurrounding("[", "]")
        return aliasToRecord[cleanAlias]
    }

    /**
     * Deterministically replaces all short aliases (`IMG_1`, `IMG_2`, etc.) in a Markdown
     * string with their original, verified URLs.
     *
     * Supports both Markdown image syntax: `![Caption](IMG_1)` -> `![Caption](https://...)`
     * and standalone parenthesized or bracketed aliases: `(IMG_1)` -> `(https://...)`.
     */
    fun resolveMarkdownAliases(markdown: String): String {
        if (markdown.isBlank() || aliasToRecord.isEmpty()) {
            return markdown
        }

        // Regex matches ![alt](IMG_N) or (IMG_N)
        val imageRegex = Regex("""!\[(.*?)\]\(\s*(IMG_\d+)\s*\)""")
        var resolved = imageRegex.replace(markdown) { match ->
            val alt = match.groupValues[1]
            val alias = match.groupValues[2]
            val originalUrl = resolve(alias)
            if (originalUrl != null) {
                "![$alt]($originalUrl)"
            } else {
                match.value
            }
        }

        // Also replace any lingering (IMG_N) links
        val linkRegex = Regex("""\(\s*(IMG_\d+)\s*\)""")
        resolved = linkRegex.replace(resolved) { match ->
            val alias = match.groupValues[1]
            val originalUrl = resolve(alias)
            if (originalUrl != null) "($originalUrl)" else match.value
        }

        return resolved
    }

    /**
     * Resets registry state.
     */
    fun clear() {
        aliasToRecord.clear()
        urlToAlias.clear()
        counter.set(1)
        AppLogger.d(TAG, "Cleared ImageAssetRegistry")
    }

    fun getAllRecords(): Map<String, ImageAssetRecord> = aliasToRecord.toMap()
}
