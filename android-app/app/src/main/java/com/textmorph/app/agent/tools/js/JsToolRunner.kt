package com.textmorph.app.agent.tools.js

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
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

/**
 * Sandboxed, headless JavaScript tool execution engine using Android's built-in WebView.
 * Provides asynchronous coroutine bridging with timeout safeguards and error isolation.
 */
class JsToolRunner(private val context: Context) {

    private val TAG = "JsToolRunner"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        var wv = webView
        if (wv == null) {
            wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
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
        AppLogger.i(TAG, "Executing JS tool '${tool.id}' with ${params.size} parameters")
        val paramsJson = JsonObject(params).toString()

        val harness = """
            (async function() {
                try {
                    ${tool.script}
                    let fn = typeof run === 'function' ? run : null;
                    if (!fn && typeof window !== 'undefined' && typeof window.run === 'function') fn = window.run;
                    if (!fn) throw new Error("No run(params) function defined in tool script");
                    const res = await Promise.resolve(fn($paramsJson));
                    return JSON.stringify({ success: true, result: String(res) });
                } catch (err) {
                    return JSON.stringify({ success: false, error: err.message || String(err) });
                }
            })()
        """.trimIndent()

        val deferred = CompletableDeferred<Pair<Boolean, String>>()

        mainHandler.post {
            try {
                val wv = getOrCreateWebView()
                wv.evaluateJavascript(harness) { rawResult ->
                    if (rawResult == null || rawResult == "null") {
                        deferred.complete(Pair(true, "Tool executed without output."))
                        return@evaluateJavascript
                    }

                    try {
                        val unquoted = if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                            // Unescape JSON string returned by evaluateJavascript
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
                            deferred.complete(Pair(true, resultStr))
                        } else {
                            val msg = errorStr ?: "Script evaluation failed"
                            deferred.complete(Pair(false, msg))
                        }
                    } catch (pe: Exception) {
                        AppLogger.w(TAG, "Parsing JS result failed, returning raw: $rawResult (${pe.message})")
                        deferred.complete(Pair(true, rawResult.removeSurrounding("\"")))
                    }
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Error evaluating JS in WebView: ${t.message}", t)
                deferred.complete(Pair(false, "Failed to run JavaScript tool: ${t.message}"))
            }
        }

        // Enforce 10-second timeout safeguard against infinite loops
        val timedResult = withTimeoutOrNull(10000L) {
            deferred.await()
        }

        if (timedResult == null) {
            AppLogger.w(TAG, "JS tool '${tool.id}' timed out after 10 seconds")
            Pair(false, "JavaScript tool timed out after 10 seconds.")
        } else {
            timedResult
        }
    }
}
