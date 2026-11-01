package com.wallpaperswitcher.engine

/**
 * WebDAV 相关的共享常量。
 *
 * 之前 `PROPFIND` 请求体和 `User-Agent` 在 [OnlineBrowser]、[OnlineProbe]、[OnlineFetcher]
 * 里各存一份，UA 一共四处、版本号还漂成了 1.1 / 1.2 —— 改一处忘三处。
 * 现在只在这里定义，其它地方引用。
 */
internal object WebDavProps {

    /** `PROPFIND Depth:1` 请求体：只要体积、修改时间、类型，够算大小与判断目录。 */
    const val PROPFIND_BODY: String =
        """<?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:"><D:prop>
          <D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
        </D:prop></D:propfind>"""

    /** 部分网盘/网关按 UA 决定返回什么，保持统一的 Mozilla 前缀更稳。 */
    const val USER_AGENT: String = "Mozilla/5.0 (Linux; Android) WallpaperSwitcher/1.2"
}
