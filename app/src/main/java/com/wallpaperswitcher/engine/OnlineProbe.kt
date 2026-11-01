package com.wallpaperswitcher.engine

import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 「测试连接」：保存来源之前先确认地址/账号能用。
 *
 * 只认 WebDAV：`PROPFIND Depth:1` 返回 207 才算通，并数出目录里能当壁纸的条目数。
 *
 * 返回**稳定结果码**（`ok` / `auth` / `http:404` / `https_required` / `not_webdav` /
 * `bad_url` / `network`）+ 条目数，UI 负责本地化；日志里不落地址与口令。
 */
internal object OnlineProbe {

    private const val TAG = "OnlineProbe"
    private const val PROBE_TIMEOUT_MS = 8_000L

    data class Result(
        val ok: Boolean,
        /** 稳定结果码，见类注释。 */
        val code: String,
        /** 目录里能当壁纸的条目数（ok 时才有意义）。 */
        val entries: Int = 0,
    )

    suspend fun testWebDav(
        baseUrl: String,
        path: String,
        username: String,
        password: String,
    ): Result = withContext(Dispatchers.IO) {
        val url = collectionUrl(baseUrl, path)
        when (OnlineSourceRules.endpointPolicy(url)) {
            OnlineSourceRules.EndpointPolicy.NEEDS_HTTPS ->
                return@withContext Result(false, "https_required")
            OnlineSourceRules.EndpointPolicy.INVALID -> return@withContext Result(false, "bad_url")
            OnlineSourceRules.EndpointPolicy.OK -> Unit
        }
        val auth = OnlineSourceRules.basicAuthHeader(username, password)
        val request = try {
            Request.Builder().url(url)
                .header("User-Agent", WebDavProps.USER_AGENT)
                .apply { if (auth != null) header("Authorization", auth) }
                .header("Depth", "1")
                .method(
                    "PROPFIND",
                    WebDavProps.PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
                )
                .build()
        } catch (t: Throwable) {
            AppLog.w(TAG, "probe bad url: ${t.javaClass.simpleName}")
            return@withContext Result(false, "bad_url")
        }
        execute(request) { response ->
            when {
                response.code == 401 || response.code == 403 -> Result(false, "auth")
                !response.isSuccessful -> Result(false, "http:${response.code}")
                else -> {
                    val xml = response.peekBody(4L * 1024 * 1024).string()
                    val entries = try {
                        OnlineSourceRules.parseWebDavListingStrict(xml)
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "probe parse failed: ${t.javaClass.simpleName}")
                        return@execute Result(false, "not_webdav")
                    }
                    val media = entries.count { entry ->
                        !OnlineSourceRules.looksLikeWebDavDirectory(entry.href) &&
                            OnlineSourceRules.mediaExtension(entry.href, null, true) != null
                    }
                    Result(true, "ok", media)
                }
            }
        } ?: Result(false, "network")
    }

    private fun collectionUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val sub = path.trim().trim('/')
        return if (sub.isEmpty()) "$base/" else "$base/$sub/"
    }

    private fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T? =
        try {
            client.newCall(request).execute().use(block)
        } catch (t: Throwable) {
            AppLog.w(TAG, "probe failed: ${t.javaClass.simpleName}")
            null
        }

    /**
     * 专用短超时 client：复用 [OnlineFetcher] 的连接池，但三个超时都压到 8s，
     * 免得「测试连接」在网络不通时卡到 90s（用户会以为 App 崩了）。
     */
    private val client by lazy {
        OnlineFetcher.httpClient().newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

}
