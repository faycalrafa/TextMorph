package com.textmorph.app

import com.textmorph.app.agent.tools.web.SearchResult
import com.textmorph.app.agent.tools.web.WebTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsUnitTest {

    @Test
    fun testDecodeDuckDuckGoUrl() {
        val rawHref = "//duckduckgo.com/l/?kh=-1&uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fhome.html&rut=1"
        val decoded = WebTools.decodeDuckDuckGoUrl(rawHref)
        assertEquals("https://kotlinlang.org/docs/home.html", decoded)

        val directHref = "https://example.com/direct/page"
        assertEquals("https://example.com/direct/page", WebTools.decodeDuckDuckGoUrl(directHref))

        val protocolRelative = "//example.com/test"
        assertEquals("https://example.com/test", WebTools.decodeDuckDuckGoUrl(protocolRelative))
    }

    @Test
    fun testParseDuckDuckGoHtml() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <div class="result results_links">
                    <h2 class="result__title">
                        <a class="result__url" href="//duckduckgo.com/l/?kh=-1&uddg=https%3A%2F%2Fdeveloper.android.com%2Fcompose">Jetpack Compose Documentation</a>
                    </h2>
                    <div class="result__snippet">Build modern native Android UI with declarative Compose components and Kotlin.</div>
                </div>
                <div class="result results_links">
                    <h2 class="result__title">
                        <a class="result__url" href="//duckduckgo.com/l/?kh=-1&uddg=https%3A%2F%2Fgithub.com%2Fandroid%2Fcompose-samples">Android Compose Samples</a>
                    </h2>
                    <div class="result__snippet">Official Jetpack Compose sample applications created by Google.</div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val results = WebTools.parseDuckDuckGoHtml(sampleHtml, maxResults = 2)
        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("Jetpack Compose Documentation", first.title)
        assertEquals("https://developer.android.com/compose", first.url)
        assertTrue(first.snippet.contains("Build modern native Android UI"))

        val second = results[1]
        assertEquals("Android Compose Samples", second.title)
        assertEquals("https://github.com/android/compose-samples", second.url)
    }

    @Test
    fun testExtractCleanMarkdown_stripsClutterAndRespectsCharLimit() {
        val clutteredHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Android 16 Features</title>
                <style>body { background: #000; }</style>
                <script>console.log("analytics tracking script");</script>
            </head>
            <body>
                <header><nav><a href="/home">Home</a><a href="/login">Login</a></nav></header>
                <article>
                    <h1>Major Android 16 Highlights</h1>
                    <p>Android 16 introduces advanced on-device agent perception and streamlined notifications.</p>
                    <ul>
                        <li>On-Device LLM acceleration</li>
                        <li>Enhanced privacy indicators</li>
                    </ul>
                    <p>Read more on the <a href="https://android-developers.googleblog.com">official Android developer blog</a>.</p>
                </article>
                <footer><p>© 2026 Android Open Source Project. All rights reserved.</p></footer>
            </body>
            </html>
        """.trimIndent()

        val markdown = WebTools.extractCleanMarkdown(clutteredHtml, "https://android.com", charLimit = 500)

        // Clutter must be stripped
        assertFalse(markdown.contains("analytics tracking script"))
        assertFalse(markdown.contains("background: #000"))
        assertFalse(markdown.contains("Home"))
        assertFalse(markdown.contains("All rights reserved"))

        // Readable article content must be retained and converted
        assertTrue(markdown.contains("# Android 16 Features"))
        assertTrue(markdown.contains("Major Android 16 Highlights"))
        assertTrue(markdown.contains("On-Device LLM acceleration"))
        assertTrue(markdown.contains("[official Android developer blog](https://android-developers.googleblog.com)"))
    }

    @Test
    fun testExtractCleanMarkdown_truncatesWhenExceedingLimit() {
        val longArticle = buildString {
            append("<html><body><article>")
            repeat(100) {
                append("<p>Paragraph number $it detailing extensive system architectural features and benchmarks.</p>")
            }
            append("</article></body></html>")
        }

        val limit = 300
        val markdown = WebTools.extractCleanMarkdown(longArticle, "https://test.com", charLimit = limit)
        assertTrue(markdown.contains("[Content truncated to protect context window]"))
        assertTrue(markdown.length <= limit + 100)
    }

    @Test
    fun testFormatSearchResults() {
        val items = listOf(
            SearchResult("TextMorph GitHub", "Modern on-device Android AI agent.", "https://github.com/textmorph"),
            SearchResult("DuckDuckGo", "Privacy search engine.", "https://duckduckgo.com")
        )
        val formatted = WebTools.formatSearchResults("textmorph", items)
        assertTrue(formatted.contains("Web Search Results for \"textmorph\":"))
        assertTrue(formatted.contains("1. **[TextMorph GitHub](https://github.com/textmorph)**"))
        assertTrue(formatted.contains("2. **[DuckDuckGo](https://duckduckgo.com)**"))
    }

    @Test
    fun testSanitizeUrl() {
        // Markdown link
        assertEquals("https://en.wikipedia.org/wiki/Kotlin", WebTools.sanitizeUrl("[Kotlin](https://en.wikipedia.org/wiki/Kotlin)"))

        // Surrounding quotes and angle brackets
        assertEquals("https://example.com/test", WebTools.sanitizeUrl("\"https://example.com/test\""))
        assertEquals("https://example.com/test", WebTools.sanitizeUrl("<https://example.com/test>"))
        assertEquals("https://example.com/test", WebTools.sanitizeUrl("(https://example.com/test)"))

        // Trailing dots and punctuation
        assertEquals("https://example.com/path", WebTools.sanitizeUrl("https://example.com/path)."))
        assertEquals("https://example.com/path", WebTools.sanitizeUrl("https://example.com/path,"))

        // Protocol-relative
        assertEquals("https://example.com/path", WebTools.sanitizeUrl("//example.com/path"))

        // Missing scheme
        assertEquals("https://kotlinlang.org", WebTools.sanitizeUrl("kotlinlang.org"))
    }
}
