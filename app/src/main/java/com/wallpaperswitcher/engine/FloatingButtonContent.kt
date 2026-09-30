package com.wallpaperswitcher.engine

/**
 * What the floating switch button draws: a custom picture, or a short text
 * label. A picture REPLACES the label - the user asked for exactly that
 * ("自定义图片不显示文字"), so the two are mutually exclusive rather than
 * composited.
 */
sealed interface FloatingButtonContent {
    /** Custom picture (a persisted content URI). The label is not drawn. */
    data class Image(val uri: String) : FloatingButtonContent

    /** Text label; [value] is never blank (the default fills in). */
    data class Text(val value: String) : FloatingButtonContent
}

/**
 * Pure decision for the floating button's content, kept Android-free so the
 * rules are unit-tested instead of living in the view code.
 *
 * Rules:
 * - A usable (non-blank) picture URI always wins; the label is dropped.
 * - A blank/absent label falls back to [DEFAULT_TEXT], so clearing the field in
 *   Settings can never leave an empty circle.
 * - The label is trimmed and shortened to [MAX_TEXT_LENGTH] CODE POINTS (not
 *   UTF-16 units: cutting an emoji in half would render as a replacement box).
 */
object FloatingButtonContentPolicy {

    const val DEFAULT_TEXT = "切"
    const val MAX_TEXT_LENGTH = 4

    /** Above this length the label is drawn smaller so it still fits the circle. */
    private const val SHORT_TEXT_MAX = 2
    private const val SHORT_TEXT_SIZE_SP = 12f
    private const val LONG_TEXT_SIZE_SP = 9f

    fun resolve(text: String?, imageUri: String?): FloatingButtonContent {
        val uri = imageUri?.trim().orEmpty()
        if (uri.isNotEmpty()) return FloatingButtonContent.Image(uri)
        return FloatingButtonContent.Text(clampText(text))
    }

    /**
     * The label to draw for [text]: trimmed, trimmed-to-length, and never blank
     * (see [DEFAULT_TEXT]).
     */
    fun clampText(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_TEXT
        return clampCodePoints(trimmed, MAX_TEXT_LENGTH)
    }

    /** Label size in sp: long labels shrink so they stay inside the circle. */
    fun textSizeSp(value: String): Float =
        if (codePointCount(value) <= SHORT_TEXT_MAX) SHORT_TEXT_SIZE_SP else LONG_TEXT_SIZE_SP

    /**
     * Cap the text WHILE the user types: same length limit as [clampText], but an
     * empty input stays empty instead of turning into [DEFAULT_TEXT] (the field
     * must let the user clear it; the fallback applies when the value is used).
     */
    fun capText(text: String): String = clampCodePoints(text, MAX_TEXT_LENGTH)

    private fun codePointCount(value: String): Int = value.codePointCount(0, value.length)

    /** Shorten to at most [max] code points without splitting a surrogate pair. */
    private fun clampCodePoints(value: String, max: Int): String {
        val count = codePointCount(value)
        if (count <= max) return value
        val end = value.offsetByCodePoints(0, max)
        return value.substring(0, end)
    }
}
