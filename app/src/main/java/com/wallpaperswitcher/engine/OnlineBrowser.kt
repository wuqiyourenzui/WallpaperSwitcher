package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.util.AppLog
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 网盘「浏览」：列一层目录，给选择器用（不是同步用的清单）。
 *
 * 与 `OnlineFetcher.listWebDav` 的区别：
 *  - 这里**要目录**（文件夹要能点进去），同步那边只要媒体文件；
 *  - 不做 maxPerRun 截断、不排序成"最新在前"，保持服务端顺序（用户是按位置找的）。
 *
 * 直链策略：WebDAV 的地址可以直接由 href 拼（同一服务器）。
 */
object OnlineBrowser {

    data class Entry(
        /** 服务端原样的名字（percent-encoded）。用于拼 URL/路径。 */
        val name: String,
        /** 给人看的名字（href 已解码）。UI 一律显示这个。 */
        val displayName: String = name,
        /** 相对来源根的路径（目录以 `/` 结尾），用于下钻与去重键。 */
        val path: String,
        val isDir: Boolean,
        val size: Long = 0L,
        val modifiedMs: Long = 0L,
        /** [MediaTypes.IMAGE] / [MediaTypes.GIF] / [MediaTypes.VIDEO]，非媒体为空串。 */
        val mediaType: String = "",
        /** 绝对地址（列表阶段就能给出，缩略图/下载/观看都用它）。 */
        val url: String? = null,
    ) {
        val isMedia: Boolean get() = mediaType.isNotEmpty()
    }

    private const val TAG = "OnlineBrowser"

    /**
     * 单次列目录的条目上限。
     *
     * WebDAV 的 `PROPFIND Depth:1` 没有分页协议，服务端会把整层一次返回；这里只是防止
     * 一个目录里上万项把内存和 UI 拖死。超限时 UI 会提示"只显示前 N 项"。
     */
    private const val MAX_ENTRIES = 2000

    /** 列一层目录。失败抛 [OnlineFetcher.FetchException]（reason: auth / parse / network …）。 */
    suspend fun list(
        source: OnlineSource,
        password: String,
        path: String,
    ): List<Entry> = withContext(Dispatchers.IO) {
        listWebDav(source, password, path)
    }

    /** 递归收集一个目录下的媒体（「选文件夹下载」用）。有层数/条数/目录数上限。 */
    suspend fun listMediaRecursive(
        source: OnlineSource,
        password: String,
        rootPath: String,
        maxItems: Int = 200,
        maxDepth: Int = 4,
        maxDirs: Int = 60,
    ): List<Entry> = walk(source, password, rootPath, maxItems, maxDepth, maxDirs, needle = null)

    /**
     * 在 [rootPath] 及其子目录里按文件名搜索。
     *
     * 与 [listMediaRecursive] 共用同一段 BFS（[walk]），只差"是否要匹配 needle"——
     * 以前这两段是逐字重复的两份循环。
     */
    suspend fun searchMedia(
        source: OnlineSource,
        password: String,
        rootPath: String,
        query: String,
        maxItems: Int = 120,
        maxDepth: Int = 5,
        maxDirs: Int = 80,
    ): List<Entry> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return walk(source, password, rootPath, maxItems, maxDepth, maxDirs, needle)
    }

    /** 去掉查询串、percent-decode、去尾部斜杠后的路径，用于"这是不是我自己"的比对。 */
    private fun normalizePath(url: String): String {
        val raw = url.substringBefore('?').substringBefore('#')
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            raw
        }
        return decoded.trimEnd('/')
    }

    /** BFS 收集媒体；[needle] 为 null 表示不按名字过滤。 */
    private suspend fun walk(
        source: OnlineSource,
        password: String,
        rootPath: String,
        maxItems: Int,
        maxDepth: Int,
        maxDirs: Int,
        needle: String?,
    ): List<Entry> = withContext(Dispatchers.IO) {
        val collected = ArrayList<Entry>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootPath to 0)
        var visitedDirs = 0
        while (queue.isNotEmpty() && collected.size < maxItems && visitedDirs < maxDirs) {
            val (dir, depth) = queue.removeFirst()
            visitedDirs++
            val entries = try {
                list(source, password, dir)
            } catch (t: Throwable) {
                AppLog.w(TAG, "walk list failed at depth $depth: ${t.javaClass.simpleName}")
                continue
            }
            for (entry in entries) {
                if (entry.isDir) {
                    if (depth + 1 <= maxDepth) queue.add(entry.path to (depth + 1))
                } else if (entry.isMedia &&
                    (needle == null || entry.displayName.contains(needle, ignoreCase = true))
                ) {
                    collected.add(entry)
                    if (collected.size >= maxItems) break
                }
            }
        }
        collected
    }
    /**
     * 在线观看用的地址 + 需要的认证头。
     *
     * 只算不下载：WebDAV 用 href 解析出的绝对地址 + Basic 头。
     */
    suspend fun viewTarget(
        source: OnlineSource,
        password: String,
        entry: Entry,
    ): Pair<String, Map<String, String>>? = withContext(Dispatchers.IO) {
        val url = entry.url ?: return@withContext null
        val auth = OnlineSourceRules.basicAuthHeader(source.username, password)
        val headers = if (auth != null) mapOf("Authorization" to auth) else emptyMap()
        url to headers
    }

    // --- WebDAV ---------------------------------------------------------------

    private fun listWebDav(
        source: OnlineSource,
        password: String,
        path: String,
    ): List<Entry> {
        val collection = collectionUrl(source, path)
        when (OnlineSourceRules.endpointPolicy(collection)) {
            OnlineSourceRules.EndpointPolicy.NEEDS_HTTPS ->
                throw OnlineFetcher.FetchException("https_required")
            OnlineSourceRules.EndpointPolicy.INVALID ->
                throw OnlineFetcher.FetchException("bad_url")
            OnlineSourceRules.EndpointPolicy.OK -> Unit
        }
        val auth = OnlineSourceRules.basicAuthHeader(source.username, password)
        val request = Request.Builder().url(collection)
            .header("User-Agent", WebDavProps.USER_AGENT)
            .apply { if (auth != null) header("Authorization", auth) }
            .header("Depth", "1")
            .method(
                "PROPFIND",
                WebDavProps.PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
            )
            .build()
        val entries = try {
            OnlineFetcher.httpClient().newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw OnlineFetcher.FetchException("auth")
                }
                if (!response.isSuccessful) {
                    throw OnlineFetcher.FetchException("http:${response.code}")
                }
                val xml = response.peekBody(16L * 1024 * 1024).string()
                OnlineSourceRules.parseWebDavListingStrict(xml)
            }
        } catch (t: OnlineFetcher.FetchException) {
            throw t
        } catch (t: Throwable) {
            // 分类：网络问题不要伪装成"解析失败"（用户会以为是地址格式错）。
            AppLog.w(TAG, "WEBDAV browse failed: ${t.javaClass.simpleName}")
            throw OnlineFetcher.FetchException(
                when (t) {
                    is java.net.SocketTimeoutException -> "timeout"
                    is java.io.IOException -> "network"
                    else -> "parse"
                }
            )
        }
        val base = URI(collection)
        val selfPath = normalizePath(collection)
        val out = ArrayList<Entry>(entries.size)
        for (entry in entries) {
            if (out.size >= MAX_ENTRIES) break
            if (OnlineSourceRules.looksLikeWebDavDirectory(entry.href)) {
                // 目录项：PROPFIND Depth:1 会把**当前集合自己**也返回（根目录时它的名字
                // 是空的，子目录时它的名字跟当前目录同名）。以前只过滤了空名字，所以
                // 点进 2026年 会看到一个也叫 2026年 的"子目录"，点它就成了
                // /2026年/2026年 → 404。这里按路径比对，一律排除自己。
                val resolved = resolveHref(base, entry.href)
                if (normalizePath(resolved) == selfPath) continue
                val dirName = resolved.trimEnd('/').substringAfterLast('/', "")
                if (dirName.isEmpty()) continue
                val safe = OnlineSourceRules.safeRemoteName(dirName) ?: continue
                // href 是 percent-encoded：展示要解码，但 path 保留原样（要拼回 URL）。
                val shown = decodePercent(safe) ?: continue
                out.add(
                    Entry(
                        name = safe,
                        displayName = shown,
                        path = joinPath(path, safe) + "/",
                        isDir = true,
                        modifiedMs = entry.lastModifiedMs,
                    )
                )
            } else {
                val fileName = OnlineSourceRules.safeRemoteName(entry.href) ?: continue
                val shown = decodePercent(fileName) ?: fileName
                val media = mediaTypeOf(shown)
                if (media.isEmpty()) continue
                out.add(
                    Entry(
                        name = fileName,
                        displayName = shown,
                        path = joinPath(path, fileName),
                        isDir = false,
                        size = entry.sizeBytes,
                        modifiedMs = entry.lastModifiedMs,
                        mediaType = media,
                        url = resolveHref(base, entry.href),
                    )
                )
            }
        }
        return out.sortedBy { if (it.isDir) 0 else 1 }
    }

    // --- 共用 -----------------------------------------------------------------

    /**
     * WebDAV 的 href 是 percent-encoded（`%E5%9B%BE%E7%89%87`）。
     *
     * 返回解码后的名字；**解码后含 `..` / `\` / `/` / 控制字符时返回 null**（这类名字既
     * 不能展示也不能下钻：服务端会把 `%2e%2e` 还原成路径穿越）。
     */
    private fun decodePercent(raw: String): String? {
        if (!raw.contains('%')) return raw
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            return null
        }
        if (decoded.contains("..") || decoded.contains('\\') || decoded.contains('/')) return null
        if (decoded.any { it.code < 0x20 }) return null
        return decoded
    }

    private fun mediaTypeOf(name: String): String {
        val ext = OnlineSourceRules.mediaExtension(name, null, includeVideos = true) ?: return ""
        if (OnlineSourceRules.isVideoExtension(ext)) return MediaTypes.VIDEO
        return if (ext == "gif") MediaTypes.GIF else MediaTypes.IMAGE
    }

    private fun joinPath(dir: String, name: String): String {
        val d = dir.trim().trim('/')
        return if (d.isEmpty()) "/$name" else "/$d/$name"
    }

    private fun collectionUrl(source: OnlineSource, path: String): String {
        val base = source.webdavUrl.trim().trimEnd('/')
        val sub = path.trim().trim('/').ifEmpty { source.webdavPath.trim().trim('/') }
        return if (sub.isEmpty()) "$base/" else "$base/$sub/"
    }

    private fun resolveHref(base: URI, href: String): String = try {
        if (href.startsWith("http://") || href.startsWith("https://")) href
        else base.resolve(href).toString()
    } catch (_: Throwable) {
        href
    }

}
