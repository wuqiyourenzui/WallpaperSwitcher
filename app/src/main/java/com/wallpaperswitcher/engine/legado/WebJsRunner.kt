package com.wallpaperswitcher.engine.legado

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 阅读's `@webjs:` rules: the page is loaded into a hidden WebView and the
 * rule's JavaScript runs **inside the page**, which is how sources handle
 * content that only exists after the site's own scripts have run.
 *
 * The WebView is created on the main thread, loaded with the fetched HTML
 * (`baseUrl` kept so relative scripts/URLs resolve), and destroyed after the
 * script returns or the timeout fires. Callers run on a background dispatcher;
 * calling from the main thread returns null instead of dead-locking.
 */
object WebJsRunner {

    private const val TAG = "WebJsRunner"
    private const val TIMEOUT_MS = 10_000L

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * @param html the page the source fetched
     * @param baseUrl the page URL (for relative resources)
     * @param script the `@webjs:` script; `result` is bound to [resultJson]
     * @param resultJson JSON encoding of the current rule result
     * @return the script's value as text, or null on timeout / no WebView
     */
    fun run(html: String, baseUrl: String, script: String, resultJson: String): String? {
        if (script.isBlank()) return null
        // No app context (unit tests / early startup): fail soft before
        // touching any Android class.
        val context = appContext ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.w(TAG, "webjs called on the main thread; skipping")
            return null
        }
        return try {
            runBlocking {
                withTimeoutOrNull(TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        evaluate(context, html, baseUrl, script, resultJson)
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "webjs failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private suspend fun evaluate(
        context: Context,
        html: String,
        baseUrl: String,
        script: String,
        resultJson: String,
    ): String? = suspendCancellableCoroutine { continuation ->
        val webView = try {
            WebView(context)
        } catch (t: Throwable) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val mainHandler = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(value: String?) {
            if (finished) return
            finished = true
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Throwable) {
            }
            if (continuation.isActive) continuation.resume(value)
        }
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            // The rule only needs the DOM, not the page's images.
            webView.settings.blockNetworkImage = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val prelude = "var result = $resultJson;\n"
                    try {
                        view.evaluateJavascript(prelude + script) { value ->
                            val direct = decodeResult(value)
                            if (direct != null) {
                                finish(direct)
                            } else {
                                // Rule scripts written as a function body
                                // ("...; return x;") are a syntax error at the
                                // top level: retry wrapped in an IIFE.
                                val wrapped = prelude + "(function(){\n" + script + "\n})()"
                                try {
                                    view.evaluateJavascript(wrapped) { retry ->
                                        finish(decodeResult(retry))
                                    }
                                } catch (t: Throwable) {
                                    AppLog.w(TAG, "webjs retry failed: ${t.javaClass.simpleName}")
                                    finish(null)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "webjs evaluate failed: ${t.javaClass.simpleName}")
                        finish(null)
                    }
                }
            }
            mainHandler.postDelayed({ finish(null) }, TIMEOUT_MS)
            continuation.invokeOnCancellation {
                mainHandler.post { finish(null) }
            }
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        } catch (t: Throwable) {
            AppLog.w(TAG, "webjs load failed: ${t.javaClass.simpleName}")
            finish(null)
        }
    }

    /** `evaluateJavascript` returns a JSON literal; unwrap a JSON string. */
    private fun decodeResult(raw: String?): String? {
        if (raw == null) return null
        val text = raw.trim()
        if (text == "null" || text.isEmpty()) return null
        if (text.startsWith("\"")) {
            return try {
                com.wallpaperswitcher.engine.Json.parse(text) as? String
            } catch (_: Throwable) {
                text
            }
        }
        return text
    }
}
