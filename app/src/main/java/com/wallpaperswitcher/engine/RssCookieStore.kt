package com.wallpaperswitcher.engine

import android.content.Context
import android.content.SharedPreferences
import com.wallpaperswitcher.util.AppLog
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Cookies of the 阅读 subscription sources: a persistent OkHttp [CookieJar].
 *
 * Many sites set a session cookie on the first request (or on `loginUrl`) and
 * then expect it on every following request. Legado keeps such a jar per
 * source; we keep one host-keyed jar for all subscription traffic and persist
 * it to SharedPreferences, so the session survives an app restart.
 *
 * The online wallpaper sources deliberately do NOT use this jar - they only
 * touch endpoints the user configured, with no session state.
 */
object RssCookieStore {

    private const val TAG = "RssCookie"
    private const val PREFS = "rss_cookies"
    private const val KEY = "cookies"
    private const val SEP = "|"

    private data class Stored(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
    )

    private val cookies = CopyOnWriteArrayList<Stored>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    val jar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            for (cookie in cookies) {
                val stored = Stored(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    expiresAt = cookie.expiresAt,
                    secure = cookie.secure,
                )
                this@RssCookieStore.cookies.removeAll {
                    it.name == stored.name && it.domain == stored.domain && it.path == stored.path
                }
                if (stored.expiresAt > System.currentTimeMillis()) {
                    this@RssCookieStore.cookies.add(stored)
                }
            }
            persist()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return cookies.asSequence()
                .filter { it.expiresAt <= 0L || it.expiresAt > now }
                .mapNotNull { stored ->
                    try {
                        Cookie.Builder()
                            .name(stored.name)
                            .value(stored.value)
                            .domain(stored.domain)
                            .path(stored.path.ifBlank { "/" })
                            .apply { if (stored.expiresAt > 0L) expiresAt(stored.expiresAt) }
                            .apply { if (stored.secure) secure() }
                            .build()
                    } catch (_: Throwable) {
                        null
                    }
                }
                .filter { it.matches(url) }
                .toList()
        }
    }

    /**
     * Import a `name=value; name2=value2` header (e.g. read back from an
     * interactive WebView login) into the jar for [url].
     */
    fun injectCookieHeader(url: String, header: String?) {
        if (header.isNullOrBlank()) return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val parsed = header.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val name = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim()
            if (name.isEmpty()) return@mapNotNull null
            try {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(httpUrl.host)
                    .path("/")
                    .build()
            } catch (_: Throwable) {
                null
            }
        }
        if (parsed.isNotEmpty()) jar.saveFromResponse(httpUrl, parsed)
    }

    /**
     * 阅读's `cookie.setCookie(url, "name=value")`: a login script writes one
     * cookie directly. Stored for 30 days like a normal session cookie.
     */
    fun setCookie(url: String, cookie: String?) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val text = cookie?.trim().orEmpty()
        val index = text.indexOf('=')
        if (index <= 0) return
        val name = text.substring(0, index).trim()
        val value = text.substring(index + 1).trim()
        if (name.isEmpty()) return
        try {
            val built = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(httpUrl.host)
                .path("/")
                .expiresAt(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
                .build()
            jar.saveFromResponse(httpUrl, listOf(built))
        } catch (_: Throwable) {
        }
    }

    /** `cookie.getCookie(url)`: the `name=value; …` header for [url], or "". */
    fun cookieHeader(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return jar.loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * 清掉某个源所在主机的 Cookie（阅读编辑页菜单里的「清除 Cookie」）。
     *
     * 这个 jar 是按主机共享的（阅读是每源一份），所以清的是**该源域名**的
     * 会话：同一站点下的其它源也会一起掉线，这点和阅读略有差别，比清空全部
     * Cookie 温和得多。
     */
    fun clearHost(url: String): Int {
        val host = try {
            java.net.URI(url.trim()).host.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (host.isBlank()) return 0
        val removed = cookies.count { stored ->
            stored.domain.trimStart('.').equals(host, ignoreCase = true) ||
                host.endsWith("." + stored.domain.trimStart('.'), ignoreCase = true)
        }
        if (removed == 0) return 0
        cookies.removeAll { stored ->
            stored.domain.trimStart('.').equals(host, ignoreCase = true) ||
                host.endsWith("." + stored.domain.trimStart('.'), ignoreCase = true)
        }
        persist()
        return removed
    }

    /** Test/诊断用：当前存了多少条 Cookie。 */
    fun size(): Int = cookies.size

    private fun load() {
        val raw = prefs?.getString(KEY, null) ?: return
        try {
            for (line in raw.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split(SEP)
                if (parts.size < 6) continue
                cookies.add(
                    Stored(
                        name = URLDecoder.decode(parts[0], "UTF-8"),
                        value = URLDecoder.decode(parts[1], "UTF-8"),
                        domain = URLDecoder.decode(parts[2], "UTF-8"),
                        path = URLDecoder.decode(parts[3], "UTF-8"),
                        expiresAt = parts[4].toLongOrNull() ?: 0L,
                        secure = parts[5] == "1",
                    )
                )
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "cookie load failed: ${t.javaClass.simpleName}")
        }
    }

    private fun persist() {
        val prefs = prefs ?: return
        try {
            val text = cookies.joinToString("\n") { stored ->
                listOf(
                    encode(stored.name),
                    encode(stored.value),
                    encode(stored.domain),
                    encode(stored.path),
                    stored.expiresAt.toString(),
                    if (stored.secure) "1" else "0",
                ).joinToString(SEP)
            }
            prefs.edit().putString(KEY, text).apply()
        } catch (t: Throwable) {
            AppLog.w(TAG, "cookie save failed: ${t.javaClass.simpleName}")
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("|", "%7C")
}
