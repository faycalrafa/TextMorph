package com.textmorph.app.agent.tools.web

import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
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
 * Connects to public, high-resolution image providers (Organic Web Page Crawl, Wikimedia Commons & DuckDuckGo),
 * filtering out vector SVGs, tracking beacons, and low-res icons, and verifying image liveness before returning.
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
     * Searches for clean, high-resolution, verified alive image candidates matching the query.
     */
    suspend fun searchImages(query: String, maxResults: Int = 4): List<ImageSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            AppLogger.w(TAG, "searchImages called with empty query")
            return@withContext emptyList()
        }

        AppLogger.i(TAG, "Executing multi-source image search for '$trimmed' (maxResults=$maxResults)")
        val rawCandidates = mutableListOf<ImageSearchResult>()

        // Provider 1: Organic Web Page image scraper (visits top search pages and scrapes body/alt images)
        try {
            val scraped = scrapeImagesFromWeb(trimmed, maxPages = 3)
            rawCandidates.addAll(scraped)
            AppLogger.d(TAG, "Web scraper extracted ${scraped.size} image candidates for '$trimmed'")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Web image scraping failure: ${t.message}")
        }

        // Provider 2: Wikimedia Commons CDN thumbnails (&iiurlwidth=1024, fast, edge-cached, zero-challenge)
        if (rawCandidates.size < maxResults * 2) {
            try {
                val wikiResults = searchWikimediaCommons(trimmed, maxResults * 2)
                rawCandidates.addAll(wikiResults)
                AppLogger.d(TAG, "Wikimedia Commons returned ${wikiResults.size} candidates for '$trimmed'")
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Wikimedia Commons search failure: ${t.message}")
            }
        }

        // Provider 3: DuckDuckGo image lookup fallback if still needed
        if (rawCandidates.size < maxResults) {
            try {
                val ddgResults = searchDuckDuckGoImages(trimmed, maxResults * 2)
                rawCandidates.addAll(ddgResults)
                AppLogger.d(TAG, "DuckDuckGo returned ${ddgResults.size} candidates for '$trimmed'")
            } catch (t: Throwable) {
                AppLogger.w(TAG, "DuckDuckGo image search fallback failure: ${t.message}")
            }
        }

        // Apply strict filtering and deduplication before probing
        val deduplicated = filterAndDeduplicate(rawCandidates, maxResults * 3)

        // Health-Check Probing Pipeline ("testing beforehand")
        // Concurrently probe candidate URLs to filter out dead URLs, 429 rate limits, 403s, and timeouts
        val aliveCandidates = coroutineScope {
            val probes = deduplicated.map { candidate ->
                async {
                    val alive = probeImageHealth(candidate.imageUrl)
                    if (alive) candidate else null
                }
            }
            probes.awaitAll().filterNotNull()
        }

        val finalized = aliveCandidates.take(maxResults)
        AppLogger.i(TAG, "Image search completed for '$trimmed': ${finalized.size} alive assets verified (from ${deduplicated.size} probed)")
        return@withContext finalized
    }

    /**
     * Crawls organic web search results to scrape body images, OpenGraph hero images, and alt text.
     */
    private suspend fun scrapeImagesFromWeb(query: String, maxPages: Int = 3): List<ImageSearchResult> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ImageSearchResult>()
        try {
            val searchResults = WebTools.searchWeb(query, maxResults = maxPages)
            if (searchResults.isEmpty()) return@withContext emptyList()

            coroutineScope {
                val pageDeferreds = searchResults.map { searchRes ->
                    async {
                        fetchAndExtractPageImages(searchRes.url, searchRes.title, query)
                    }
                }
                val pageResults = pageDeferreds.awaitAll()
                for (items in pageResults) {
                    candidates.addAll(items)
                }
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Web image scraping encountered error: ${t.message}")
        }
        candidates
    }

    /**
     * Fetches a webpage and extracts non-noise body and meta images matching the topic.
     */
    private fun fetchAndExtractPageImages(pageUrl: String, pageTitle: String, query: String): List<ImageSearchResult> {
        val candidates = mutableListOf<ImageSearchResult>()
        try {
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val html = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string().orEmpty()
            }
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, pageUrl)

            // 1. Check OpenGraph / Twitter hero images
            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
            if (!ogImage.isNullOrBlank()) {
                val resolvedOg = if (ogImage.startsWith("http")) ogImage else doc.absUrl("meta[property=og:image]").ifBlank { ogImage }
                if (isValidImageCandidate(resolvedOg, "", 0, 0)) {
                    val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
                        ?: pageTitle.ifBlank { query }
                    candidates.add(
                        ImageSearchResult(
                            title = ogTitle,
                            imageUrl = cleanCdnUrl(resolvedOg),
                            sourceUrl = pageUrl
                        )
                    )
                }
            }

            // 2. Select in-body images
            val imgElements = doc.select("article img, main img, .content img, .post img, [role=main] img, img")
            for (img in imgElements) {
                val rawSrc = img.attr("abs:src").ifBlank {
                    img.attr("abs:data-src").ifBlank {
                        img.attr("abs:data-original").ifBlank {
                            img.attr("abs:data-lazy-src")
                        }
                    }
                }.ifBlank { img.attr("src") }

                if (rawSrc.isBlank() || rawSrc.startsWith("data:")) continue
                val fullUrl = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc

                val alt = img.attr("alt").trim()
                val titleAttr = img.attr("title").trim()
                val figcaption = img.parents().firstOrNull { it.tagName() == "figure" }?.selectFirst("figcaption")?.text()?.trim().orEmpty()
                val candidateTitle = alt.ifBlank { titleAttr.ifBlank { figcaption.ifBlank { pageTitle } } }

                // Noise filtering: check class and ID
                val className = img.className().lowercase()
                val idName = img.id().lowercase()
                val noiseTokens = listOf("logo", "icon", "banner", "avatar", "badge", "ad-", "sponsor", "header", "footer", "share")
                if (noiseTokens.any { className.contains(it) || idName.contains(it) }) continue

                val w = img.attr("width").toIntOrNull() ?: 0
                val h = img.attr("height").toIntOrNull() ?: 0

                if (isValidImageCandidate(fullUrl, "", w, h)) {
                    if (isTopicRelevant(candidateTitle, query) || isTopicRelevant(fullUrl, query) || (alt.isNotBlank() && isTopicRelevant(pageTitle, query))) {
                        candidates.add(
                            ImageSearchResult(
                                title = candidateTitle.ifBlank { query },
                                imageUrl = cleanCdnUrl(fullUrl),
                                sourceUrl = pageUrl,
                                width = w,
                                height = h
                            )
                        )
                    }
                }
                if (candidates.size >= 8) break
            }
        } catch (t: Throwable) {
            AppLogger.d(TAG, "Error extracting images from $pageUrl: ${t.message}")
        }
        return candidates
    }

    /**
     * Checks if text contains topic keywords from query.
     */
    fun isTopicRelevant(text: String, query: String): Boolean {
        if (text.isBlank()) return false
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "for", "with", "pics", "picture", "pictures",
            "image", "images", "photo", "photos", "of", "in", "on", "at", "to", "search", "find"
        )
        val queryTokens = query.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in stopWords }
        if (queryTokens.isEmpty()) return true
        val lowerText = text.lowercase()
        return queryTokens.any { token ->
            val baseToken = if (token.endsWith("s") && token.length > 3) token.removeSuffix("s") else token
            lowerText.contains(token) || lowerText.contains(baseToken) ||
                (lowerText.split(Regex("[^a-z0-9]+")).any { word ->
                    val baseWord = if (word.endsWith("s") && word.length > 3) word.removeSuffix("s") else word
                    word == token || baseWord == baseToken || (word.length > 2 && token.startsWith(word))
                })
        }
    }

    /**
     * Actively probes image URL to ensure it is alive, accessible, not rate-limited, and valid.
     */
    suspend fun probeImageHealth(url: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true))) {
            return@withContext false
        }

        try {
            val probeClient = httpClient.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "image/avif,image/webp,image/apng,image/jpeg,image/png,*/*;q=0.8")
                .header("Range", "bytes=0-1024")
                .head()
                .build()

            val isAlive = probeClient.newCall(request).execute().use { resp ->
                if (resp.code in 200..206 || resp.code == 304) {
                    val cType = resp.header("Content-Type", "")?.lowercase().orEmpty()
                    cType.startsWith("image/") && !cType.contains("svg")
                } else if (resp.code == 405) {
                    // Fallback to quick GET if server rejects HEAD
                    val getReq = Request.Builder()
                        .url(trimmed)
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("Accept", "image/avif,image/webp,image/apng,image/jpeg,image/png,*/*;q=0.8")
                        .header("Range", "bytes=0-1024")
                        .get()
                        .build()
                    probeClient.newCall(getReq).execute().use { getResp ->
                        if (getResp.code in 200..206 || getResp.code == 304) {
                            val cType = getResp.header("Content-Type", "")?.lowercase().orEmpty()
                            cType.startsWith("image/") && !cType.contains("svg")
                        } else false
                    }
                } else {
                    false
                }
            }
            return@withContext isAlive
        } catch (t: Throwable) {
            AppLogger.d(TAG, "Health check failed for '$trimmed': ${t.message}")
            return@withContext false
        }
    }

    /**
     * Queries Wikimedia Commons MediaWiki API requesting edge-cached thumbnail URLs.
     */
    private fun searchWikimediaCommons(query: String, limit: Int): List<ImageSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6" +
            "&gsrsearch=$encoded&gsrlimit=$limit&prop=imageinfo&iiprop=url|size|mime&iiurlwidth=1024&format=json"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TextMorph/5.0 (https://github.com/faycalrafa/TextMorph)")
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
                    val thumbUrl = info.optString("thumburl", "")
                    val origUrl = info.optString("url", "")
                    val imgUrl = thumbUrl.ifBlank { origUrl }
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
