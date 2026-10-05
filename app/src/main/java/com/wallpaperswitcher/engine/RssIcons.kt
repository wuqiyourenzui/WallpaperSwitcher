package com.wallpaperswitcher.engine

import java.net.URI

// 订阅源缩略图（网格视图）：站点图标。
//
// 订阅源按设计不在本地缓存文章（进源实时加载、退出即清空），所以列表里没有
// "最新一篇文章的图"可用；站点图标是唯一不额外抓取任何东西就能显示的图像，
// 直接取源站根目录的 /favicon.ico（保持与源同协议——http 源去要 https 的图标
// 多半会失败），由 Coil 负责缓存。
object RssIcons {

    /**
     * 站点图标地址；无法从 [sourceUrl] 解析出 http(s) 主机时返回 null
     * （界面用占位图标），例如 `legado://` 分享链接或手填的畸形地址。
     *
     * 端口只在非默认端口时保留（`http://a.example:8080/feed`）。
     */
    fun iconUrl(sourceUrl: String): String? {
        val uri = try {
            URI(sourceUrl.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = scheme == "http" && uri.port == 80 || scheme == "https" && uri.port == 443
        val port = if (uri.port > 0 && !defaultPort) ":${uri.port}" else ""
        return "$scheme://$host$port/favicon.ico"
    }
}
