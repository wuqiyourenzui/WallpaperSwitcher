package com.wallpaperswitcher.util

/**
 * Short, share-safe forms of media URIs and folder paths for log lines.
 *
 * Why this exists: the runtime log is written to a file and can be exported and
 * shared from Settings. Full content URIs are both huge and personal - a SAF
 * path with Chinese folder names is ~400 characters once percent-encoded, and
 * the tablet log spent 40KB on 240 such lines while exposing the user's folder
 * names ("雨波HaneAme - 剑星…"). A log line only needs enough text to tell two
 * media apart.
 *
 * Rules:
 *  - a `content://` / `file://` / other scheme keeps its scheme + authority, then
 *    "…" and the last path segment (`content://media/…/1230`);
 *  - a plain path keeps only its last segment (`/storage/…/a.jpg` -> `a.jpg`);
 *  - the result is capped at [MAX] characters;
 *  - empty/null becomes "-".
 *
 * Pure, so it is unit-tested.
 */
object LogText {

    /** Hard cap of a shortened value, in characters. */
    const val MAX = 64

    /** Short form of [value] (a URI or a file path) for a log line. */
    fun short(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return "-"
        val schemeEnd = raw.indexOf("://")
        val tail = raw.substringAfterLast('/').ifEmpty { raw }
        val head = if (schemeEnd >= 0) {
            val pathStart = raw.indexOf('/', schemeEnd + 3)
            if (pathStart > 0) raw.substring(0, pathStart) else raw.substring(0, schemeEnd + 3)
        } else {
            ""
        }
        val short = if (head.isEmpty()) tail else "$head/…/$tail"
        return if (short.length <= MAX) short else short.take(MAX - 1) + "…"
    }

    /**
     * Short form of a folder path: keeps the LAST TWO segments, because folder
     * names are what distinguishes them and a single segment is often a generic
     * "Pictures" or a date.
     */
    fun folder(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return "-"
        val segments = raw.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val tail = segments.takeLast(2).joinToString("/")
        val short = if (tail.isEmpty()) raw else "…/$tail"
        return if (short.length <= MAX) short else short.take(MAX - 1) + "…"
    }
}
