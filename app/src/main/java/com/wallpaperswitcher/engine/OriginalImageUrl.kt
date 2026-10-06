package com.wallpaperswitcher.engine

/**
 * 原图优先: subscription pages hand us whatever URL their `<img>` carried, and
 * gallery sites very often put a RESIZED copy there (WordPress `-300x200`
 * suffixes, CDN `?w=300&quality=70` params). Downloading that copy as a
 * wallpaper means the library only ever holds the thumbnail-resolution source.
 *
 * [upgrade] turns those URLs back into the original-image URL. It is a pure
 * string transformation; the importer still falls back to the given URL when
 * the upgraded one fails to download, so a wrong guess can never lose an image.
 */
object OriginalImageUrl {

    /**
     * WordPress / WooCommerce style size suffix right before the extension:
     * `photo-300x200.jpg` -> `photo.jpg`. Only 2-5 digit sizes are touched so a
     * legitimate name like `shot-4x4.png` keeps its suffix.
     */
    private val SIZE_SUFFIX = Regex(
        """[-_]\d{2,5}x\d{2,5}(?:-c)?(?=\.[A-Za-z0-9]{2,5}$)""",
        RegexOption.IGNORE_CASE,
    )

    /** One `url [descriptor]` entry of a `srcset`. */
    private val SRCSET_SEPARATOR = Regex("\\s+")

    /**
     * 描述符缺失时的权重：按 srcset 规范等于 `1x`。它高于 `300w` 这类小图
     * 变体、低于 `2x` / `1200w`，所以"只写 URL 的那一项"通常就是原图。
     */
    private const val BARE_ENTRY_WEIGHT = 1000.0

    /**
     * Query parameters that only describe a resized/derived variant. Dropping
     * them (in a URL with no unrelated params left) asks the origin for the
     * stored original. Params not in this list are preserved, and a query that
     * still has unknown params keeps them.
     */
    private val RESIZE_PARAMS = setOf(
        "w", "h", "width", "height",
        "resize", "fit", "crop", "size", "thumb", "thumbnail",
        "quality", "q", "format", "auto", "dpr",
        "x-oss-process", "image_process", "imageview", "imagemogr2",
    )

    /** The original-image URL for [url], or [url] itself when nothing matches. */
    fun upgrade(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return url
        val hashIndex = trimmed.indexOf('#')
        val withoutFragment = if (hashIndex >= 0) trimmed.substring(0, hashIndex) else trimmed
        val queryIndex = withoutFragment.indexOf('?')
        val path = if (queryIndex >= 0) {
            withoutFragment.substring(0, queryIndex)
        } else {
            withoutFragment
        }
        val query = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1) else null

        val upgradedPath = SIZE_SUFFIX.replace(path, "")

        if (query == null || query.isEmpty()) {
            return upgradedPath + if (hashIndex >= 0) trimmed.substring(hashIndex) else ""
        }
        val kept = query.split('&')
            .filter { part ->
                val name = part.substringBefore('=').lowercase()
                name.isNotEmpty() && name !in RESIZE_PARAMS
            }
        val rebuilt = if (kept.isEmpty()) upgradedPath else "$upgradedPath?" + kept.joinToString("&")
        return rebuilt + if (hashIndex >= 0) trimmed.substring(hashIndex) else ""
    }

    /**
     * `srcset` / `data-srcset` 里**最大的一张**，解析不出来时返回 null。
     *
     * 为什么不取第一项：srcset 按约定从小到大排列，而浏览器交给 `currentSrc`
     * 的是"为当前屏幕/DPR 选中的展示尺寸"——`<picture>` 里还经常是 WebP 这种
     * 转码变体。订阅源"下到分组"的必须是原图，所以这里按尺寸描述符排序：
     * `1200w` > `2x`(=2000) > 无描述符(=1000) > `480w`；同样大小时取后一项。
     */
    fun largestFromSrcset(srcset: String?): String? {
        val raw = srcset?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // 整条属性就是一个 data: URI 时它的 payload 里也有逗号，不能当 srcset 切。
        if (raw.startsWith("data:", ignoreCase = true)) return null
        var best: String? = null
        var bestWeight = Double.NEGATIVE_INFINITY
        for (entry in raw.split(',')) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(SRCSET_SEPARATOR)
            val url = parts.firstOrNull().orEmpty()
            if (url.isEmpty() || url.startsWith("data:", ignoreCase = true)) continue
            val descriptor = parts.getOrNull(1)?.lowercase().orEmpty()
            val weight = when {
                descriptor.endsWith("w") ->
                    descriptor.dropLast(1).toDoubleOrNull() ?: BARE_ENTRY_WEIGHT
                descriptor.endsWith("x") ->
                    (descriptor.dropLast(1).toDoubleOrNull() ?: 1.0) * BARE_ENTRY_WEIGHT
                else -> BARE_ENTRY_WEIGHT
            }
            // `>=`: srcset 约定从小到大，同样大小时取后出现的一项。
            if (weight >= bestWeight) {
                bestWeight = weight
                best = url
            }
        }
        return best
    }
}
