package com.wallpaperswitcher.util

/**
 * Single hex-colour parser for the whole app.
 *
 * The floating-button colour setting and the theme colour each used to carry
 * their own copy of "strip #, require 6 digits, parse"; keeping one parser means
 * a fix (or a new accepted format) cannot land in only one of them.
 *
 * @return an opaque ARGB int, or null when [hex] is not "#RRGGBB".
 */
fun parseHexColorInt(hex: String?): Int? {
    if (hex.isNullOrBlank()) return null
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    return try {
        android.graphics.Color.parseColor("#$clean")
    } catch (_: Exception) {
        null
    }
}
