package com.wallpaperswitcher.engine

import java.security.SecureRandom
import java.security.cert.X509Certificate
import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * HTTP client for 阅读 (Legado) subscription traffic, mirroring legado-E's
 * `HttpHelper`:
 *
 *  - **keep-alive / connection reuse**: our old client disabled the connection
 *    pool (`ConnectionPool(0, 1ms)`), so every request paid a fresh TCP + TLS
 *    handshake. That made list/article/gallery fetches slow and flaky;
 *    Legado uses the default pool and so do we.
 *  - **COMPATIBLE_TLS + trust-all socket factory**: a large share of the
 *    subscription sites ship expired / self-signed certificates. Legado
 *    accepts them (`SSLHelper.unsafeSSLSocketFactory`), which is why the same
 *    sources work there and showed "网络不可用" here.
 *  - a **browser-like User-Agent** (a source's own `header` still wins) and
 *    Legado's timeouts.
 *
 * This client is used only for user-configured subscription sources; the
 * online wallpaper sources keep the strict HTTPS policy.
 */
object RssHttp {

    /** Default UA when the source does not set one (Legado uses a browser UA). */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val baseClient: OkHttpClient by lazy {
        val socketFactory = SSLContext.getInstance("TLS").run {
            init(null, arrayOf(trustAll), SecureRandom())
            socketFactory
        }
        OkHttpClient.Builder()
            // A real connection pool (keep-alive + HTTP/2 reuse) - the old
            // client disabled it, so every request paid a new TCP+TLS
            // handshake and list/article/gallery fetches were slow.
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // CLEARTEXT must stay in the list: several sources are plain
            // `http://` image sites, and an explicit connectionSpecs without it
            // makes OkHttp reject them ("CLEARTEXT communication not enabled").
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT,
                )
            )
            .sslSocketFactory(socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .cookieJar(RssCookieStore.jar)
            .apply {
                cacheDir?.let { dir ->
                    try {
                        cache(Cache(dir, 20L * 1024 * 1024))
                    } catch (_: Throwable) {
                    }
                }
            }
            .build()
    }

    /** User-configured proxy for subscription traffic (`host:port`, …). */
    @Volatile
    var proxySpec: String? = null

    @Volatile private var cacheDir: File? = null
    @Volatile private var appCacheDir: File? = null

    /**
     * Disk cache for subscription GETs (list/article pages). It is only used as
     * a **fallback when the network fails**, so freshness is unaffected; a page
     * visited once still opens when the site is slow or offline.
     */
    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "rss_http")
        appCacheDir = context.cacheDir
    }

    /** App cache dir for engine-side caches (JS 源的 jsLib 下载缓存). */
    val engineCacheDir: File? get() = appCacheDir

    val client: OkHttpClient get() = client(proxySpec)

    /** Same client without the session jar (`enabledCookieJar = false`). */
    val clientNoCookies: OkHttpClient
        get() = clientNoCookies(proxySpec)

    private val noCookieDirect: OkHttpClient by lazy {
        baseClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    }

    private val proxyClients = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * 阅读's per-source proxy (`getProxyClient`): several image sites are not
     * reachable from a plain mobile network, and Legado lets them go through a
     * user-configured proxy. `host:port` ("socks5://host:port" / "http://host:port"
     * are accepted too); blank returns the direct client.
     */
    fun client(proxySpec: String?): OkHttpClient {
        val spec = proxySpec?.trim().orEmpty()
        if (spec.isEmpty()) return baseClient
        return proxyClients.getOrPut(spec) {
            // Keep the cache tiny: a proxy client holds its own pool.
            if (proxyClients.size > 2) proxyClients.clear()
            val parsed = parseProxy(spec) ?: return@getOrPut baseClient
            baseClient.newBuilder().proxy(parsed).build()
        }
    }

    fun clientNoCookies(proxySpec: String?): OkHttpClient {
        val spec = proxySpec?.trim().orEmpty()
        if (spec.isEmpty()) return noCookieDirect
        val parsed = parseProxy(spec) ?: return noCookieDirect
        return proxyClients.getOrPut("nocookie|$spec") {
            baseClient.newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                .proxy(parsed)
                .build()
        }
    }

    private fun parseProxy(spec: String): Proxy? {
        var text = spec
        var type = Proxy.Type.HTTP
        when {
            text.startsWith("socks5://", ignoreCase = true) -> {
                type = Proxy.Type.SOCKS
                text = text.substring(9)
            }
            text.startsWith("socks://", ignoreCase = true) -> {
                type = Proxy.Type.SOCKS
                text = text.substring(8)
            }
            text.startsWith("http://", ignoreCase = true) -> text = text.substring(7)
            text.startsWith("https://", ignoreCase = true) -> text = text.substring(8)
        }
        text = text.trimEnd('/')
        val host = text.substringBefore(':').trim()
        val port = text.substringAfter(':', "").trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return try {
            Proxy(type, InetSocketAddress(host, port))
        } catch (_: Exception) {
            null
        }
    }
}
