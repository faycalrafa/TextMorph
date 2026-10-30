package com.textmorph.app.agent.tools.js

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Sandboxed, persistent JavaScript tool execution engine using Android's WebView.
 * Provides a high-performance native JavascriptInterface bridge for instant completion,
 * asynchronous Promise awaiting, and zero-CORS HTTP network capabilities.
 */
class JsToolRunner private constructor(private val context: Context) {

    private val TAG = "JsToolRunner"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val callbacks = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Volatile
    private var webView: WebView? = null
    @Volatile
    private var isPageInitialized = false

    inner class JsHostBridge {
        @JavascriptInterface
        fun postResult(callId: String, success: Boolean, result: String) {
            AppLogger.d(TAG, "Native bridge received result for call $callId: success=$success")
            callbacks.remove(callId)?.complete(Pair(success, result))
        }

        @JavascriptInterface
        fun httpGet(url: String): String {
            AppLogger.d(TAG, "Bridge httpGet invoked for URL: $url")
            return try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.string().orEmpty()
                    } else {
                        "Error: HTTP ${resp.code} (${resp.message})"
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Bridge httpGet failed for $url: ${t.message}")
                "Error: ${t.message ?: "Network request failed"}"
            }
        }
    }

    init {
        mainHandler.post {
            try {
                getOrCreateWebView()
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed pre-warming WebView: ${t.message}", t)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        var wv = webView
        if (wv == null) {
            wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(JsHostBridge(), "TextMorphHost")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isPageInitialized = true
                        AppLogger.d(TAG, "Headless JS WebView page initialized.")
                    }
                }
                loadDataWithBaseURL("https://localhost", "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body></body></html>", "text/html", "UTF-8", null)
            }
            webView = wv
        }
        return wv
    }

    /**
     * Executes a registered JavaScript tool with parameters and returns (success, output).
     */
    suspend fun execute(
        tool: JsToolEntity,
        params: Map<String, JsonElement> = emptyMap()
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val callId = UUID.randomUUID().toString()
        AppLogger.i(TAG, "Executing JS tool '${tool.id}' [callId=$callId] with ${params.size} parameters")
        val paramsJson = JsonObject(params).toString()

        val harness = """
            (async function() {
                try {
                    ${tool.script}
                    let fn = typeof run === 'function' ? run : null;
                    if (!fn && typeof window !== 'undefined' && typeof window.run === 'function') fn = window.run;
                    if (!fn) throw new Error("No run(params) function defined in tool script");
                    const res = await Promise.resolve(fn($paramsJson));
                    const resStr = String(res !== undefined && res !== null ? res : "");
                    if (typeof TextMorphHost !== 'undefined' && typeof TextMorphHost.postResult === 'function') {
                        TextMorphHost.postResult('$callId', true, resStr);
                    }
                    return JSON.stringify({ success: true, result: resStr });
                } catch (err) {
                    const errStr = err.message || String(err);
                    if (typeof TextMorphHost !== 'undefined' && typeof TextMorphHost.postResult === 'function') {
                        TextMorphHost.postResult('$callId', false, errStr);
                    }
                    return JSON.stringify({ success: false, error: errStr });
                }
            })()
        """.trimIndent()

        val deferred = CompletableDeferred<Pair<Boolean, String>>()
        callbacks[callId] = deferred

        mainHandler.post {
            try {
                val wv = getOrCreateWebView()
                wv.evaluateJavascript(harness) { rawResult ->
                    if (deferred.isCompleted) return@evaluateJavascript
                    if (rawResult == null || rawResult == "null") {
                        return@evaluateJavascript
                    }

                    try {
                        val unquoted = if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                            try {
                                json.parseToJsonElement(rawResult).jsonPrimitive.content
                            } catch (_: Exception) {
                                rawResult.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                            }
                        } else {
                            rawResult
                        }

                        val parsed = json.parseToJsonElement(unquoted).jsonObject
                        val success = parsed["success"]?.jsonPrimitive?.booleanOrNull ?: true
                        val resultStr = parsed["result"]?.jsonPrimitive?.content
                        val errorStr = parsed["error"]?.jsonPrimitive?.content

                        if (success && resultStr != null) {
                            callbacks.remove(callId)?.complete(Pair(true, resultStr))
                        } else if (errorStr != null) {
                            callbacks.remove(callId)?.complete(Pair(false, errorStr))
                        }
                    } catch (pe: Exception) {
                        AppLogger.d(TAG, "evaluateJavascript returned raw scalar: $rawResult")
                        callbacks.remove(callId)?.complete(Pair(true, rawResult.removeSurrounding("\"")))
                    }
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Error evaluating JS in WebView: ${t.message}", t)
                callbacks.remove(callId)?.complete(Pair(false, "Failed to run JavaScript tool: ${t.message}"))
            }
        }

        // Enforce 10-second timeout safeguard against infinite loops
        val timedResult = withTimeoutOrNull(10000L) {
            deferred.await()
        }

        callbacks.remove(callId)

        if (timedResult == null) {
            AppLogger.w(TAG, "JS tool '${tool.id}' [callId=$callId] timed out after 10 seconds")
            Pair(false, "JavaScript tool timed out after 10 seconds.")
        } else {
            timedResult
        }
    }

    companion object {
        @Volatile
        private var instance: JsToolRunner? = null

        fun getInstance(context: Context): JsToolRunner {
            return instance ?: synchronized(this) {
                instance ?: JsToolRunner(context.applicationContext).also { instance = it }
            }
        }
    }
}
