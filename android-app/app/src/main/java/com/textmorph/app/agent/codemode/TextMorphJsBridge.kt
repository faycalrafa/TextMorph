package com.textmorph.app.agent.codemode

import android.content.Context
import android.webkit.JavascriptInterface
import com.textmorph.app.agent.tools.web.WebTools
import com.textmorph.app.service.overlay.ScreenAutomationController
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Native JavaScript Interface exposed into the Mobile CodeMode WebView sandbox under window.TextMorph.
 * Allows on-device models to compose batch tool workflows (web search, screen gestures, formatting)
 * into a single native JavaScript execution pass, reducing turn latency and battery consumption.
 */
class TextMorphJsBridge(
    private val context: Context,
    private val controllerProvider: () -> ScreenAutomationController?
) {
    private val TAG = "TextMorphJsBridge"
    val pendingCallbacks = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()

    @JavascriptInterface
    fun postResult(callId: String, success: Boolean, resultJson: String) {
        AppLogger.d(TAG, "CodeMode script completion received for $callId: success=$success")
        pendingCallbacks.remove(callId)?.complete(Pair(success, resultJson))
    }

    @JavascriptInterface
    fun clickText(text: String): Boolean {
        AppLogger.d(TAG, "CodeMode bridge clickText('$text')")
        return controllerProvider()?.clickByText(text) ?: false
    }

    @JavascriptInterface
    fun clickAt(x: Float, y: Float): Boolean {
        AppLogger.d(TAG, "CodeMode bridge clickAt($x, $y)")
        return controllerProvider()?.clickAtCoordinates(x, y) ?: false
    }

    @JavascriptInterface
    fun pressHome(): Boolean {
        AppLogger.d(TAG, "CodeMode bridge pressHome()")
        return controllerProvider()?.pressHome() ?: false
    }

    @JavascriptInterface
    fun pressBack(): Boolean {
        AppLogger.d(TAG, "CodeMode bridge pressBack()")
        return controllerProvider()?.pressBack() ?: false
    }

    @JavascriptInterface
    fun typeText(text: String): Boolean {
        AppLogger.d(TAG, "CodeMode bridge typeText(length=${text.length})")
        return controllerProvider()?.typeIntoField(text) ?: false
    }

    @JavascriptInterface
    fun searchWeb(query: String): String {
        AppLogger.d(TAG, "CodeMode bridge searchWeb('$query')")
        return try {
            runBlocking {
                val results = WebTools.searchWeb(query, maxResults = 4)
                JSONObject().apply {
                    put("query", query)
                    put("count", results.size)
                    put("results", results.map { "${it.title}: ${it.snippet} (${it.url})" })
                }.toString()
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "searchWeb bridge failed: ${t.message}", t)
            "{\"error\": \"${t.message}\"}"
        }
    }

    @JavascriptInterface
    fun fetchPage(url: String): String {
        AppLogger.d(TAG, "CodeMode bridge fetchPage('$url')")
        return try {
            runBlocking {
                val pageContent = WebTools.fetchPage(url, charLimit = 2000)
                JSONObject().apply {
                    put("url", url)
                    put("content", pageContent)
                }.toString()
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "fetchPage bridge failed: ${t.message}", t)
            "{\"error\": \"${t.message}\"}"
        }
    }

    @JavascriptInterface
    fun log(message: String) {
        AppLogger.d(TAG, "[CodeMode Console]: $message")
    }
}
