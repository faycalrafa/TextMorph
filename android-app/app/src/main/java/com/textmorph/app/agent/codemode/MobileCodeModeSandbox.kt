package com.textmorph.app.agent.codemode

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.textmorph.app.service.overlay.ScreenAutomationController
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Sandboxed execution environment for Mobile CodeMode.
 * Uses a headless, isolated Android WebView with native JavascriptInterface bindings.
 */
class MobileCodeModeSandbox private constructor(
    private val context: Context,
    controllerProvider: () -> ScreenAutomationController?
) {
    private val TAG = "CodeModeSandbox"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridge = TextMorphJsBridge(context, controllerProvider)

    @Volatile
    private var webView: WebView? = null
    @Volatile
    private var isInitialized = false

    companion object {
        @Volatile
        private var instance: MobileCodeModeSandbox? = null

        fun getInstance(
            context: Context,
            controllerProvider: () -> ScreenAutomationController? = { null }
        ): MobileCodeModeSandbox {
            return instance ?: synchronized(this) {
                instance ?: MobileCodeModeSandbox(context.applicationContext, controllerProvider).also {
                    instance = it
                }
            }
        }
    }

    init {
        mainHandler.post {
            try {
                getOrCreateWebView()
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed initializing CodeMode WebView: ${t.message}", t)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        var wv = webView
        if (wv == null) {
            wv = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(bridge, "_NativeTextMorph")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isInitialized = true
                        AppLogger.i(TAG, "CodeMode WebView sandbox ready.")
                    }
                }
                loadDataWithBaseURL("https://sandbox.textmorph.local/", BOOTSTRAP_HTML, "text/html", "UTF-8", null)
            }
            webView = wv
        }
        return wv
    }

    /**
     * Executes a model-generated CodeMode JavaScript script with a strict execution timeout.
     */
    suspend fun executeScript(
        script: String,
        timeoutMs: Long = 20_000L
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Pair<Boolean, String>>()
        bridge.pendingCallbacks[callId] = deferred

        mainHandler.post {
            try {
                val wv = getOrCreateWebView()
                val escapedScript = script.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
                val runnerCode = """
                    (async function() {
                        try {
                            const result = await (async () => {
                                $script
                            })();
                            _NativeTextMorph.postResult('$callId', true, JSON.stringify(result ?? 'Done'));
                        } catch (err) {
                            _NativeTextMorph.postResult('$callId', false, JSON.stringify(err.message || String(err)));
                        }
                    })();
                """.trimIndent()
                wv.evaluateJavascript(runnerCode, null)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Error evaluating CodeMode script: ${t.message}", t)
                bridge.pendingCallbacks.remove(callId)?.complete(Pair(false, "Evaluation failure: ${t.message}"))
            }
        }

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: run {
            bridge.pendingCallbacks.remove(callId)
            Pair(false, "CodeMode script execution timed out after ${timeoutMs}ms")
        }

        result
    }

    private val BOOTSTRAP_HTML = """
        <!DOCTYPE html>
        <html>
        <head><meta charset="utf-8"/></head>
        <body>
        <script>
            window.TextMorph = {
                screen: {
                    clickText: (text) => _NativeTextMorph.clickText(text),
                    clickAt: (x, y) => _NativeTextMorph.clickAt(x, y),
                    pressHome: () => _NativeTextMorph.pressHome(),
                    pressBack: () => _NativeTextMorph.pressBack(),
                    typeText: (text) => _NativeTextMorph.typeText(text)
                },
                tools: {
                    searchWeb: (query) => JSON.parse(_NativeTextMorph.searchWeb(query)),
                    fetchPage: (url) => JSON.parse(_NativeTextMorph.fetchPage(url))
                },
                log: (msg) => _NativeTextMorph.log(String(msg))
            };
        </script>
        </body>
        </html>
    """.trimIndent()
}
