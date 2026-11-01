package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.util.AppLog
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * The `java` object bound into 阅读 JS rules. Only the helpers commonly used
 * by subscription sources are provided; they are synchronous on purpose
 * because the rule engine runs on a background thread.
 */
class LegadoJsHelpers(
    private val sourceId: Long = 0L,
    /** `java.getString(rule)` on the current content. */
    private val textProvider: ((String) -> String?)? = null,
    /** `java.getElements(rule)` on the current content. */
    private val elementsProvider: ((String) -> List<Any>)? = null,
) {

    // --- source.getVariable / setVariable / put / get -------------------------

    fun getVariable(): String = RssSourceVariables.get(sourceId)

    fun setVariable(variable: String?): String {
        RssSourceVariables.set(sourceId, variable)
        return variable.orEmpty()
    }

    fun putVariable(variable: String?): String = setVariable(variable)

    fun put(key: String, value: String): String = RssSourceVariables.put(sourceId, key, value)

    fun get(key: String): String = RssSourceVariables.get(sourceId, key)

    // --- login helpers (阅读: source.getLoginInfo / putLoginInfo / putLoginHeader) ---

    /** The values typed into the `loginUi` form, keyed by field name. */
    fun getLoginInfoMap(): Map<String, String> {
        val raw = RssLoginStore.getUserInfo(sourceId) ?: return emptyMap()
        return try {
            (com.wallpaperswitcher.engine.Json.parse(raw) as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) ->
                    val key = k as? String ?: return@mapNotNull null
                    if (v == null) null else key to v.toString()
                }
                ?.toMap()
                .orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getLoginInfo(): String? = RssLoginStore.getUserInfo(sourceId)

    fun putLoginInfo(json: String?) {
        RssLoginStore.putUserInfo(sourceId, json)
    }

    fun putLoginHeader(json: String?) {
        RssLoginStore.putLoginHeader(sourceId, json)
    }

    fun getLoginHeader(): String? = RssLoginStore.getLoginHeader(sourceId)

    // --- java.getString / getElements -----------------------------------------

    fun getString(rule: String): String = textProvider?.invoke(rule).orEmpty()

    fun getElements(rule: String): List<Any> = elementsProvider?.invoke(rule).orEmpty()

    /** `java.ajax(url)` - plain GET returning the body text. */
    fun ajax(url: String): String {
        val host = hostOf(url)
        if (AjaxCircuit.isOpen(host)) return ""
        // 同一个地址问过太多次就放弃：规则可能在 JS 里对取不到的地址死循环重试
        // （实测「秀人集」的 ruleNextPage → hot_1.html 每 0.5 秒一次、把首屏
        // 拖到 20 秒以上）。图集规则对**不同**的 `_N.html` 探测不受影响。
        if (AjaxCircuit.isUrlExhausted(url)) return ""
        AjaxCircuit.onUrlAttempt(url)
        // 阻塞式调用前后把**实际耗时**还给 JS 超时预算：源限流 + 站点慢响应
        // 几个请求就能吃光 60 秒，watchdog 一中断，正在跑的 OkHttp 就被
        // CANCEL 掉（`StreamResetException: stream was reset: CANCEL`），
        // 正文永远抓不到。网络等待不该算作"规则跑飞"。
        val startedAt = System.currentTimeMillis()
        return try {
            val body = ConcurrentRate.withLimitBlocking(sourceId) {
                LegadoRss.fetchTextSync(url)
            }
            LegadoJsRuntime.refundTimeout(System.currentTimeMillis() - startedAt)
            AjaxCircuit.onSuccess(host)
            AjaxCircuit.onUrlSuccess(url)
            body
        } catch (t: Throwable) {
            // 失败的那次网络等待同样要还回去（超时的判定不该由站点慢响应触发）。
            LegadoJsRuntime.refundTimeout(System.currentTimeMillis() - startedAt)
            // 记下**原因**（http status / 超时 / 地址），否则源的图集翻页失败时
            // 日志里只有一行 "java.ajax failed: FetchException"，无法判断是站点
            // 拒绝、限流还是地址拼错。
            val reason = (t as? com.wallpaperswitcher.engine.FetchException)
                ?.let { "reason=${it.reason} status=${it.httpStatus}" }
                ?: "${t.javaClass.simpleName}: ${t.message?.take(80)}"
            AppLog.w(TAG, "java.ajax failed: $reason url=${url.take(110)}")
            // 只有**传输层**失败（连不上、超时、TLS/重置）才计入熔断。
            // HTTP 状态码不算：图集规则会故意往后多探几页，站点对「超出图集末尾」
            // 的 `_N.html` 一律回 502 —— 那是规则的一部分，熔断会把正常图集也掐掉
            // （实测就会变成「只显示 5 张」）。
            if (AjaxCircuit.isTransportFailure(t)) {
                AjaxCircuit.onFailure(host)?.let { failures ->
                    AppLog.w(
                        TAG,
                        "java.ajax circuit open for $host after $failures failures " +
                            "(cooling down ${AjaxCircuit.COOLDOWN_MS / 1000}s)",
                    )
                }
            }
            ""
        }
    }

    private fun hostOf(url: String): String = try {
        val uri = java.net.URI(UrlOptions.strip(url))
        uri.host ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun base64Encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun base64Decode(text: String): String = try {
        String(Base64.getDecoder().decode(text), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    fun md5Encode(text: String): String = try {
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    // --- 阅读 JsEncodeUtils: 对称加密 / 十六进制（加密源用它解密规则） ------------

    /** `java.aesBase64DecodeToString(str, key, transformation, iv)`。 */
    fun aesBase64DecodeToString(
        str: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? = SymmetricCrypto.decrypt(str, key, transformation, iv)

    fun aesDecodeToString(
        str: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? = SymmetricCrypto.decrypt(str, key, transformation, iv)

    fun desBase64DecodeToString(
        str: String,
        key: String,
        transformation: String,
        iv: String,
    ): String? = SymmetricCrypto.decrypt(str, key, transformation, iv)

    fun hexDecodeToString(hex: String): String? = SymmetricCrypto.fromHex(hex)?.toString(Charsets.UTF_8)

    fun hexEncodeToString(text: String): String =
        text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    /** 阅读 `java.createSymmetricCrypto(transformation, key)`（加密源解密规则用）。 */
    fun createSymmetricCrypto(transformation: String, key: String): LegadoJsCryptoHandle =
        LegadoJsCryptoHandle(transformation, key)

    fun timeFormat(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

    fun log(text: String) {
        AppLog.d(TAG, text.take(200))
    }

    /** `java.encodeURI(text)` - percent-encoding without touching reserved set. */
    fun encodeURI(text: String): String = try {
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        text
    }

    /**
     * `java.get(url, headers)` - a response with `body()` / `statusCode()`.
     * 阅读's signature takes the headers map, which keeps it distinct from the
     * `source.get(key)` variable accessor above.
     */
    fun get(url: String, headers: Any? = null): LegadoJsResponse =
        ConcurrentRate.withLimitBlocking(sourceId) {
            LegadoRss.httpRequestSync(
                url,
                method = "GET",
                body = null,
                headers = LegadoRss.toHeaderMap(headers),
            )
        }

    /**
     * `java.post(url, body, headers)` - used by `@js:` login scripts.
     * [headers] may be a JS object (Rhino map) or a JSON string.
     */
    fun post(url: String, body: String, headers: Any?): LegadoJsResponse =
        ConcurrentRate.withLimitBlocking(sourceId) {
            LegadoRss.httpRequestSync(
                url,
                method = "POST",
                body = body,
                headers = LegadoRss.toHeaderMap(headers),
                mediaType = LegadoRss.mediaTypeOf(LegadoRss.toHeaderMap(headers)),
            )
        }

    /** `java.head(url, headers)`: 只要响应头（JS 源用它读缓存用的 etag）。 */
    fun head(url: String, headers: Any? = null): LegadoJsResponse =
        ConcurrentRate.withLimitBlocking(sourceId) {
            LegadoRss.httpRequestSync(
                url,
                method = "HEAD",
                body = null,
                headers = LegadoRss.toHeaderMap(headers),
            )
        }

    companion object {
        private const val TAG = "LegadoJs"
    }
}
