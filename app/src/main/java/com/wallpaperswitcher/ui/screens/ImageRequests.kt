package com.wallpaperswitcher.ui.screens

import coil.request.ImageRequest

/**
 * Coil request for article/thumbnail images: 阅读 (GlideHeaders) hands the
 * source's own headers to every image request, so sites that check Referer/UA
 * serve the image instead of stalling or 403-ing.
 *
 * Shared by the subscription screens. Kept out of the (removed) 美人图 picker so
 * the RSS article list keeps working without the online-source UI.
 */
internal fun imageRequest(
    context: android.content.Context,
    url: String,
    referer: String?,
    headers: Map<String, String> = emptyMap(),
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .apply {
        var hasReferer = false
        for ((name, value) in headers) {
            if (name.equals("Referer", ignoreCase = true)) {
                hasReferer = true
                addHeader("Referer", value)
                continue
            }
            try {
                addHeader(name, value)
            } catch (_: Exception) {
            }
        }
        if (!hasReferer && referer != null) addHeader("Referer", referer)
    }
    // Article thumbs are decode/draw heavy; the global loader keeps software
    // bitmaps for compatibility, but these never need read-back.
    .allowHardware(true)
    .crossfade(false)
    .build()
