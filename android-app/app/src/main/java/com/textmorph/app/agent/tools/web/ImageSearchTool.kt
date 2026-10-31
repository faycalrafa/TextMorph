package com.textmorph.app.agent.tools.web

import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Clean image search candidate extracted from public image search endpoints.
 */
data class ImageSearchResult(
    val title: String,
    val imageUrl: String,
    val sourceUrl: String = "",
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Resilient, zero-key image discovery tool designed for on-device mobile agents.
 * Connects to public, high-resolution image providers (Wikimedia Commons & DuckDuckGo),
 * filtering out vector SVGs, tracking beacons, and low-res icons.
 */
object ImageSearchTool {

    private const val TAG = "ImageSearchTool"
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Searches for clean, high-resolution image candidates matching the query.
     */
    suspend fun searchImages(query: String, maxResults: Int = 4): List<ImageSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            AppLogger.w(TAG, "searchImages called with empty query")
            return@withContext emptyList()
        }

        AppLogger.i(TAG, "Executing image search for '$trimmed' (maxResults=$maxResults)")
        val candidates = mutableListOf<ImageSearchResult>()

        // Provider 1: Wikimedia Commons API (Fast, public domain/CC, high-res, zero-challenge)
        try {
            val wikiResults = searchWikimediaCommons(trimmed, maxResults * 2)
            candidates.addAll(wikiResults)
            AppLogger.d(TAG, "Wikimedia Commons returned ${wikiResults.size} candidates for '$trimmed'")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Wikimedia Commons search failure: ${t.message}")
        }

        // Provider 2: DuckDuckGo image lookup fallback if needed
        if (candidates.size < maxResults) {
            try {
                val ddgResults = searchDuckDuckGoImages(trimmed, maxResults * 2)
                candidates.addAll(ddgResults)
                AppLogger.d(TAG, "DuckDuckGo returned ${ddgResults.size} candidates for '$trimmed'")
            } catch (t: Throwable) {
                AppLogger.w(TAG, "DuckDuckGo image search fallback failure: ${t.message}")
            }
        }

        // Apply strict filtering and deduplication
        val filtered = filterAndDeduplicate(candidates, maxResults)
        AppLogger.i(TAG, "Image search completed for '$trimmed': ${filtered.size} clean assets returned")
        return@withContext filtered
    }

    /**
     * Queries Wikimedia Commons MediaWiki API for public images.
     */
    private fun searchWikimediaCommons(query: String, limit: Int): List<ImageSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6" +
            "&gsrsearch=$encoded&gsrlimit=$limit&prop=imageinfo&iiprop=url|size|mime&format=json"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TextMorphMobileAgent/4.3 (Android; Privacy-First)")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLogger.w(TAG, "Wikimedia API HTTP error: ${response.code}")
                return emptyList()
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val results = mutableListOf<ImageSearchResult>()
            val root = JSONObject(body)
            val queryObj = root.optJSONObject("query") ?: return emptyList()
            val pagesObj = queryObj.optJSONObject("pages") ?: return emptyList()

            val keys = pagesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val page = pagesObj.optJSONObject(key) ?: continue
                val rawTitle = page.optString("title", "")
                    .removePrefix("File:")
                    .replace(Regex("\\.[a-zA-Z0-9]+$"), "")
                    .replace('_', ' ')
                    .trim()

                val imageInfoArray = page.optJSONArray("imageinfo") ?: continue
                if (imageInfoArray.length() > 0) {
                    val info = imageInfoArray.getJSONObject(0)
                    val imgUrl = info.optString("url", "")
                    val mime = info.optString("mime", "").lowercase()
                    val width = info.optInt("width", 0)
                    val height = info.optInt("height", 0)
                    val descUrl = info.optString("descriptionurl", "")

                    if (isValidImageCandidate(imgUrl, mime, width, height)) {
                        results.add(
                            ImageSearchResult(
                                title = rawTitle.ifBlank { query },
                                imageUrl = cleanCdnUrl(imgUrl),
                                sourceUrl = descUrl,
                                width = width,
                                height = height
                            )
                        )
                    }
                }
            }
            return results
        }
    }

    /**
     * Queries DuckDuckGo image JSON API.
     */
    private fun searchDuckDuckGoImages(query: String, limit: Int): List<ImageSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        // Step 1: obtain vqd token
        val vqdUrl = "https://duckduckgo.com/?q=$encoded&iax=images&ia=images"
        val vqdRequest = Request.Builder()
            .url(vqdUrl)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .build()

        val vqd = httpClient.newCall(vqdRequest).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            val match = Regex("""vqd=["']?([\d-]+)["']?""").find(html)
            match?.groupValues?.get(1)
        } ?: return emptyList()

        // Step 2: query i.js
        val searchUrl = "https://duckduckgo.com/i.js?l=us-en&o=json&q=$encoded&vqd=$vqd&f=,,,&p=1"
        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Referer", "https://duckduckgo.com/")
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val jsonStr = resp.body?.string().orEmpty()
            if (jsonStr.isBlank() || !jsonStr.trim().startsWith("{")) return emptyList()

            val root = JSONObject(jsonStr)
            val resultsArr = root.optJSONArray("results") ?: return emptyList()
            val results = mutableListOf<ImageSearchResult>()

            for (i in 0 until resultsArr.length()) {
                val item = resultsArr.optJSONObject(i) ?: continue
                val imgUrl = item.optString("image", "")
                val title = item.optString("title", "").trim()
                val srcUrl = item.optString("url", "")
                val width = item.optInt("width", 0)
                val height = item.optInt("height", 0)

                if (isValidImageCandidate(imgUrl, "", width, height)) {
                    results.add(
                        ImageSearchResult(
                            title = title.ifBlank { query },
                            imageUrl = cleanCdnUrl(imgUrl),
                            sourceUrl = srcUrl,
                            width = width,
                            height = height
                        )
                    )
                }
                if (results.size >= limit) break
            }
            return results
        }
    }

    /**
     * Checks if image candidate meets quality, safety, and non-icon constraints.
     */
    fun isValidImageCandidate(url: String, mime: String, width: Int, height: Int): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase()

        // Filter out vector SVGs
        if (lowerUrl.endsWith(".svg") || lowerUrl.contains(".svg?") || mime.contains("svg")) {
            return false
        }

        // Filter out tracking beacons, spacer GIFs, and icons
        val blockedTokens = listOf(
            "pixel", "tracking", "beacon", "spacer", "favicon",
            "1x1", "blank.gif", "dot.gif", "analytics", "badge"
        )
        if (blockedTokens.any { lowerUrl.contains(it) }) {
            return false
        }

        // Filter out tiny icons if dimensions are reported
        if (width in 1..99 || height in 1..99) {
            return false
        }

        return lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")
    }

    /**
     * Strips tracking query parameters from CDN URLs.
     */
    private fun cleanCdnUrl(url: String): String {
        return if (url.contains("?utm_")) {
            url.substringBefore("?utm_")
        } else {
            url
        }
    }

    /**
     * Filters and de-duplicates image results preserving highest resolution.
     */
    private fun filterAndDeduplicate(candidates: List<ImageSearchResult>, maxResults: Int): List<ImageSearchResult> {
        val seenUrls = mutableSetOf<String>()
        val distinct = mutableListOf<ImageSearchResult>()

        for (candidate in candidates) {
            val normalizedUrl = candidate.imageUrl.trim()
            if (normalizedUrl.isNotBlank() && seenUrls.add(normalizedUrl)) {
                distinct.add(candidate)
                if (distinct.size >= maxResults) break
            }
        }
        return distinct
    }
}
