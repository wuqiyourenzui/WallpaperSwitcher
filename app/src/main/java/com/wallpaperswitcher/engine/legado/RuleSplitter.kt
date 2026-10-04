package com.wallpaperswitcher.engine.legado

/**
 * Splits a 阅读 (Legado) rule by a separator without touching separators that
 * live inside `[]` / `()` / quotes (e.g. a CSS attribute selector or a
 * JSONPath filter must survive the split).
 *
 * The separator `@` is kept in front of a continuation segment that starts
 * with a mode marker (`@CSS:`, `@XPath:`, `@Json:`, `@js:`), because those
 * markers are part of the segment syntax.
 */
internal object RuleSplitter {

    private val MODE_MARKERS = listOf("CSS:", "XPATH:", "JSON:", "JS:")

    fun splitAt(rule: String): List<String> = split(rule, "@").first

    fun split(rule: String, separator: String): Pair<List<String>, String?> =
        splitAny(rule, listOf(separator))

    fun splitAny(rule: String, separators: List<String>): Pair<List<String>, String?> {
        val parts = ArrayList<String>()
        var used: String? = null
        var depth = 0
        var inSingle = false
        var inDouble = false
        var start = 0
        var index = 0
        while (index < rule.length) {
            val c = rule[index]
            if (c == '\\') {
                index += 2
                continue
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble
            }
            if (!inSingle && !inDouble) {
                when (c) {
                    '[', '(' -> depth++
                    ']', ')' -> if (depth > 0) depth--
                }
                if (depth == 0) {
                    val match = separators.firstOrNull { rule.startsWith(it, index) }
                    // `//div/@data-id` 里的 `@` 是 XPath 属性写法，不是链分隔符
                    //（阅读把它整条交给 XPath 分析器，`/@` 由分析器自行拆分）。
                    val xpathAttribute = match == "@" && index > 0 && rule[index - 1] == '/'
                    if (match != null && index > start && !xpathAttribute) {
                        // A mode marker right after @ belongs to the next segment.
                        val next = index + match.length
                        val keepSeparator = match == "@" && (
                            rule.startsWith("@", next) ||
                                MODE_MARKERS.any { rule.startsWith(it, next, ignoreCase = true) }
                            )
                        val cut = if (keepSeparator) index else next
                        parts.add(rule.substring(start, index))
                        if (used == null) used = match
                        index = cut
                        start = cut
                        continue
                    } else if (match != null && index == 0) {
                        // A leading marker (`@CSS:…`, `@js:…`) is not a separator.
                        index += match.length
                        continue
                    }
                }
            }
            index++
        }
        parts.add(rule.substring(start))
        return parts.filter { it.isNotEmpty() } to used
    }

    /** Replace every `{{…}}` block with [transform] (null keeps the original). */
    fun innerRule(rule: String, transform: (String) -> String?): String {
        if (!rule.contains("{{")) return rule
        val out = StringBuilder()
        var index = 0
        var changed = false
        while (index < rule.length) {
            val start = rule.indexOf("{{", index)
            if (start < 0) {
                out.append(rule, index, rule.length)
                break
            }
            val end = findBalanced(rule, start + 2)
            if (end < 0) {
                out.append(rule, index, rule.length)
                break
            }
            out.append(rule, index, start)
            val inner = rule.substring(start + 2, end)
            val value = transform(inner)
            if (value != null) {
                out.append(value)
                changed = true
            } else {
                out.append(rule, start, end + 2)
            }
            index = end + 2
        }
        return if (changed) out.toString() else rule
    }

    private fun findBalanced(text: String, from: Int): Int {
        var depth = 1
        var inSingle = false
        var inDouble = false
        var index = from
        while (index < text.length) {
            val c = text[index]
            if (c == '\\') {
                index += 2
                continue
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble
            }
            if (!inSingle && !inDouble) {
                when (c) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return -1
    }
}
