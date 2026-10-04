package com.wallpaperswitcher.engine.legado

/**
 * 阅读's `splitSourceRule`: a rule field may list several alternatives separated
 * by top-level commas (`h2@text,title@text`); the first alternative that yields
 * a value wins.
 *
 * Commas inside `[]` / `()` / `{}` / quotes are never separators, a `<js>…</js>`
 * block is opaque, and a `@js:` segment runs to the end of the rule (that is how
 * 阅读 treats it), so JS code may contain commas freely.
 */
internal object RuleAlternatives {

    fun split(rule: String?): List<String> {
        if (rule.isNullOrBlank()) return emptyList()
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var square = 0
        var round = 0
        var curly = 0
        var inSingle = false
        var inDouble = false
        var index = 0
        while (index < rule.length) {
            val c = rule[index]
            if (c == '\\') {
                current.append(c)
                if (index + 1 < rule.length) current.append(rule[index + 1])
                index += 2
                continue
            }
            if (!inDouble && c == '\'') inSingle = !inSingle
            if (!inSingle && c == '"') inDouble = !inDouble
            if (!inSingle && !inDouble) {
                // `<js>…</js>`: copy the whole block untouched.
                if (c == '<' && rule.startsWith("<js>", index, ignoreCase = true)) {
                    val end = rule.indexOf("</js>", index + 4, ignoreCase = true)
                    val stop = if (end >= 0) end + 5 else rule.length
                    current.append(rule, index, stop)
                    index = stop
                    continue
                }
                // `@js:` consumes the remainder of the rule.
                if (c == '@' && rule.startsWith("@js:", index, ignoreCase = true)) {
                    current.append(rule, index, rule.length)
                    index = rule.length
                    continue
                }
                when (c) {
                    '[' -> square++
                    ']' -> if (square > 0) square--
                    '(' -> round++
                    ')' -> if (round > 0) round--
                    '{' -> curly++
                    '}' -> if (curly > 0) curly--
                    // 阅读 的 `||`：依次尝试，取第一个非空结果。之前这里只拆 `,`，
                    // 于是 `h2 a@text||h3 a@text` 会被整段交给规则引擎 —— 引擎按
                    // `@` 拆成 ["h2 a", "text||h3 a", "text"]，中间那段 CSS 选不到
                    // 任何元素，取值直接变空。推次元这类源的标题/链接规则全是
                    // `||`，结果就是列表抓到 10 条、却一条都存不下来。
                    '|' -> if (square == 0 && round == 0 && curly == 0 &&
                        index + 1 < rule.length && rule[index + 1] == '|'
                    ) {
                        parts.add(current.toString())
                        current.setLength(0)
                        index += 2
                        continue
                    }
                    ',' -> if (square == 0 && round == 0 && curly == 0) {
                        parts.add(current.toString())
                        current.setLength(0)
                        index++
                        continue
                    }
                }
            }
            current.append(c)
            index++
        }
        parts.add(current.toString())
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
