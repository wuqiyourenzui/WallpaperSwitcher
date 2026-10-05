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
        """-\d{2,5}x\d{2,5}(?=\.[A-Za-z0-9]{2,5}$)""",
        RegexOption.IGNORE_CASE,
    )

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
}
