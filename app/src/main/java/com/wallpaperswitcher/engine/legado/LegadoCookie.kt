package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.engine.RssCookieStore

/**
 * 阅读's `cookie` object inside JS rules (`cookie.setCookie(url, "k=v")`).
 * It writes into the same persistent jar every subscription request uses, so a
 * `@js:` login script can authenticate later fetches.
 */
class LegadoCookieHelper {

    fun setCookie(url: String, cookie: String) {
        RssCookieStore.setCookie(url, cookie)
    }

    fun getCookie(url: String): String =
        try {
            RssCookieStore.cookieHeader(url)
        } catch (_: Throwable) {
            ""
        }
}

/**
 * 阅读's `java.get/post` return value: a response object exposing the same
 * accessors a login script uses.
 */
class LegadoJsResponse(
    private val code: Int,
    private val text: String,
    private val responseHeaders: Map<String, String> = emptyMap(),
    private val responseUrl: String = "",
) {
    fun body(): String = text

    fun statusCode(): Int = code

    fun headers(): Map<String, String> = responseHeaders

    /** 阅读's `response.header(name)`（JS 源用它读 etag）。 */
    fun header(name: String): String? =
        responseHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun url(): String = responseUrl

    fun isSuccessful(): Boolean = code in 200..299
}

/**
 * 阅读 `java.createSymmetricCrypto(transformation, key)` 返回的对称加解密对象：
 * 加密源拿它 `decryptStr(...)` 解出自己的规则（Hutool `SymmetricCrypto` 的常用面）。
 */
class LegadoJsCryptoHandle(
    private val transformation: String,
    private val key: String,
) {
    private var iv: String = ""

    fun setIv(value: String?): LegadoJsCryptoHandle {
        iv = value.orEmpty()
        return this
    }

    fun decryptStr(data: String): String? =
        SymmetricCrypto.decrypt(data, key, transformation, iv)

    fun decrypt(data: String): String? = decryptStr(data)

    fun decryptStrBase64(data: String): String? = decryptStr(data)

    fun encryptBase64(data: String): String? =
        SymmetricCrypto.encryptBase64(data, key, transformation, iv)

    fun encryptStr(data: String): String? = encryptBase64(data)
}
