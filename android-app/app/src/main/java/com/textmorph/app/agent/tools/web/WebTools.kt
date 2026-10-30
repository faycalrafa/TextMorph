package com.textmorph.app.agent.tools.web

import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Clean search result item extracted from DuckDuckGo HTML.
 */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

/**
 * Zero-cost, API-key-free web browsing toolset using OkHttp and Jsoup.
 * Enforces strict timeout isolation, DOM clutter removal, and markdown transformation.
 */
object WebTools {

    private const val TAG = "WebTools"
    private const val DDG_HTML_ENDPOINT = "https://html.duckduckgo.com/html/"
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Queries DuckDuckGo's public HTML interface and extracts organic search results.
     */
    suspend fun searchWeb(query: String, maxResults: Int = 3): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) {
                AppLogger.w(TAG, "searchWeb called with empty query")
                return@withContext emptyList()
            }

            AppLogger.d(TAG, "Executing DuckDuckGo web search: '$trimmedQuery' (limit=$maxResults)")
            try {
                val formBody = FormBody.Builder()
                    .add("q", trimmedQuery)
                    .build()

                val request = Request.Builder()
                    .url(DDG_HTML_ENDPOINT)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://html.duckduckgo.com/")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .post(formBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "DuckDuckGo search HTTP failure: code=${response.code} msg=${response.message}")
                        return@withContext emptyList()
                    }

                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) {
                        AppLogger.w(TAG, "DuckDuckGo returned empty HTML body for '$trimmedQuery'")
                        return@withContext emptyList()
                    }

                    parseDuckDuckGoHtml(html, maxResults)
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Exception during DuckDuckGo web search for '$trimmedQuery': ${t.message}", t)
                emptyList()
            }
        }

    /**
     * Parses raw DuckDuckGo HTML markup and decodes redirected uddg links.
     */
    fun parseDuckDuckGoHtml(html: String, maxResults: Int): List<SearchResult> {
        return try {
            val doc = Jsoup.parse(html)
            val results = mutableListOf<SearchResult>()

            // DuckDuckGo HTML result items
            var resultElements = doc.select(".result.results_links")
            if (resultElements.isEmpty()) {
                resultElements = doc.select(".result:not(.result--ad)")
            }
            if (resultElements.isEmpty()) {
                resultElements = doc.select(".web-result")
            }

            for (element in resultElements) {
                if (results.size >= maxResults) break

                val titleLink = element.selectFirst(".result__title a")
                    ?: element.selectFirst("a.result__url")
                    ?: element.selectFirst("h2 a")

                val title = titleLink?.text()?.trim().orEmpty()
                val rawHref = titleLink?.attr("href")?.trim().orEmpty()
                val snippet = element.selectFirst(".result__snippet")?.text()?.trim().orEmpty()

                val resolvedUrl = decodeDuckDuckGoUrl(rawHref)
                if (title.isNotBlank() && resolvedUrl.isNotBlank()) {
                    results.add(
                        SearchResult(
                            title = title,
                            snippet = snippet,
                            url = resolvedUrl
                        )
                    )
                }
            }

            AppLogger.d(TAG, "Parsed ${results.size} search results from DuckDuckGo HTML")
            results
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed parsing DuckDuckGo HTML: ${t.message}", t)
            emptyList()
        }
    }

    /**
     * Extracts and URL-decodes the target url from DuckDuckGo's redirection parameter (uddg).
     */
    fun decodeDuckDuckGoUrl(rawHref: String): String {
        if (rawHref.isBlank()) return ""
        try {
            if (rawHref.contains("uddg=")) {
                val encodedPart = rawHref.substringAfter("uddg=").substringBefore("&")
                val decoded = URLDecoder.decode(encodedPart, StandardCharsets.UTF_8.name())
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    return decoded
                }
            }
            if (rawHref.startsWith("//")) {
                return "https:$rawHref"
            }
            if (rawHref.startsWith("http://") || rawHref.startsWith("https://")) {
                return rawHref
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to decode DDG URL '$rawHref': ${t.message}")
        }
        return rawHref
    }

    /**
     * Cleans, unescapes, and normalizes URLs from LLM tool outputs (markdown links, quotes, trailing dots).
     */
    fun sanitizeUrl(rawUrl: String): String {
        var u = rawUrl.trim()
        if (u.isBlank()) return ""

        // Extract URL if wrapped in markdown link: [text](https://example.com)
        val mdMatch = Regex("""\[.*?\]\(\s*(https?://[^\s\)]+)\s*\)""").find(u)
        if (mdMatch != null) {
            u = mdMatch.groupValues[1]
        }

        // Strip surrounding angle brackets <...>, parentheses (...), or quotes
        u = u.removeSurrounding("<", ">").removeSurrounding("(", ")")
            .removeSurrounding("\"", "\"").removeSurrounding("'", "'").trim()

        // Strip trailing punctuation often emitted by LLMs (e.g. "https://site.com).", "https://site.com,")
        u = u.replace(Regex("""[.,;)]+$"""), "").trim()

        // Normalize protocol
        if (u.startsWith("//")) {
            u = "https:$u"
        } else if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
            u = "https://$u"
        }

        return u
    }

    /**
     * HTTP GETs target URL, strips DOM clutter, extracts readable content, and returns clean Markdown.
     */
    suspend fun fetchPage(url: String, charLimit: Int = 3000): String =
        withContext(Dispatchers.IO) {
            val validUrl = sanitizeUrl(url)
            if (validUrl.isBlank() || validUrl == "https://" || validUrl == "http://") {
                return@withContext "Error: Webpage URL is empty or invalid."
            }

            AppLogger.d(TAG, "Fetching webpage: '$validUrl' (charLimit=$charLimit)")
            try {
                val request = Request.Builder()
                    .url(validUrl)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errMsg = "HTTP error ${response.code} (${response.message}) while fetching $validUrl"
                        AppLogger.w(TAG, errMsg)
                        return@withContext "Error: $errMsg"
                    }

                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) {
                        return@withContext "Error: Webpage returned empty content."
                    }

                    extractCleanMarkdown(html, validUrl, charLimit)
                }
            } catch (t: Throwable) {
                val errMsg = "Failed to fetch webpage ($validUrl): ${t.message ?: t.javaClass.simpleName}"
                AppLogger.e(TAG, errMsg, t)
                "Error: $errMsg"
            }
        }

    /**
     * Strips JS, CSS, navigation, and clutter from HTML, converting the core readable body into Markdown.
     */
    fun extractCleanMarkdown(html: String, baseUrl: String, charLimit: Int): String {
        return try {
            val doc = Jsoup.parse(html, baseUrl)

            // Strip clutter elements
            doc.select("script, style, nav, header, footer, iframe, noscript, svg, aside, form, link, meta").remove()

            // Select readable container: article -> main -> body
            val mainContent: Element = doc.selectFirst("article")
                ?: doc.selectFirst("main")
                ?: doc.selectFirst("[role=main]")
                ?: doc.body()

            val pageTitle = doc.title().trim().ifBlank { "Web Page" }
            val sb = StringBuilder()
            sb.append("# ").append(pageTitle).append("\n\n")

            convertElementToMarkdown(mainContent, sb)

            val rawMarkdown = sb.toString()
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()

            if (rawMarkdown.length > charLimit) {
                rawMarkdown.take(charLimit).trim() + "\n\n[Content truncated to protect context window]"
            } else {
                rawMarkdown
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error extracting markdown from HTML: ${t.message}", t)
            "Error: Failed to process webpage HTML."
        }
    }

    private fun convertElementToMarkdown(element: Element, sb: StringBuilder) {
        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> {
                    val text = node.text()
                    if (text.isNotBlank()) {
                        sb.append(text)
                    }
                }
                is Element -> {
                    when (node.tagName().lowercase()) {
                        "h1" -> sb.append("\n\n# ").append(node.text().trim()).append("\n\n")
                        "h2" -> sb.append("\n\n## ").append(node.text().trim()).append("\n\n")
                        "h3" -> sb.append("\n\n### ").append(node.text().trim()).append("\n\n")
                        "h4", "h5", "h6" -> sb.append("\n\n#### ").append(node.text().trim()).append("\n\n")
                        "p" -> {
                            sb.append("\n\n")
                            convertElementToMarkdown(node, sb)
                            sb.append("\n\n")
                        }
                        "li" -> {
                            sb.append("\n- ")
                            convertElementToMarkdown(node, sb)
                        }
                        "a" -> {
                            val linkText = node.text().trim()
                            val href = node.attr("abs:href").ifBlank { node.attr("href") }.trim()
                            if (linkText.isNotBlank() && href.isNotBlank() && !href.startsWith("javascript:")) {
                                sb.append(" [").append(linkText).append("](").append(href).append(") ")
                            } else if (linkText.isNotBlank()) {
                                sb.append(" ").append(linkText).append(" ")
                            }
                        }
                        "br" -> sb.append("\n")
                        "blockquote" -> {
                            sb.append("\n> ").append(node.text().trim()).append("\n\n")
                        }
                        "code", "pre" -> {
                            sb.append("\n```\n").append(node.text().trim()).append("\n```\n")
                        }
                        else -> {
                            convertElementToMarkdown(node, sb)
                        }
                    }
                }
            }
        }
    }

    /**
     * Formats search results into readable Markdown text.
     */
    fun formatSearchResults(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) {
            return "No web search results found for \"$query\"."
        }
        val sb = StringBuilder()
        sb.append("Web Search Results for \"").append(query).append("\":\n\n")
        results.forEachIndexed { index, res ->
            sb.append("${index + 1}. **[${res.title}](${res.url})**\n")
            if (res.snippet.isNotBlank()) {
                sb.append("   ${res.snippet}\n")
            }
            sb.append("   URL: ${res.url}\n\n")
        }
        return sb.toString().trim()
    }
}
