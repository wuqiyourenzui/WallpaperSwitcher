package com.wallpaperswitcher.wallpaper

import android.media.MediaFormat

/**
 * Vendor codecs may omit optional keys; a missing or malformed value is
 * treated as 0, which every caller already interprets as "not declared"
 * (the container's declared value stays as the fallback).
 */
internal fun MediaFormat.getIntegerSafe(key: String): Int =
    try { getInteger(key) } catch (_: Exception) { 0 }
