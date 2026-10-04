package com.wallpaperswitcher.engine.legado

import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * The default (jsoup) selector of the 阅读 rule syntax:
 *
 *  - `class.X`, `id.X`, `tag.X`, `text.X` (own text contains), `children`,
 *    otherwise a raw CSS selector;
 *  - the selector-combination operators `&&` (concat), `||` (first non-empty)
 *    and `%%` (interleave);
 *  - index filters: `tag.div.-1` (pick), `tag.div!0` (exclude) and the bracket
 *    form `tag.div[0, 2:-1, -1:0]` with negative indexes and an optional step.
 */
internal object JsoupAnalyzer {

    fun select(root: Element, rule: String): Elements {
        val (parts, operator) = RuleSplitter.splitAny(rule, listOf("&&", "||", "%%"))
        val selected = parts.map { selectSingle(root, it) }
        if (selected.isEmpty()) return Elements()
        return when (operator) {
            "%%" -> {
                val out = Elements()
                val max = selected.maxOf { it.size }
                for (i in 0 until max) {
                    for (list in selected) {
                        if (i < list.size) out.add(list[i])
                    }
                }
                out
            }
            "&&" -> {
                val out = Elements()
                selected.forEach(out::addAll)
                out
            }
            else -> {
                if (operator == "||") {
                    selected.firstOrNull { it.isNotEmpty() } ?: Elements()
                } else {
                    selected.first()
                }
            }
        }
    }

    private fun selectSingle(root: Element, rawRule: String): Elements {
        val rule = rawRule.trim()
        if (rule.isEmpty()) return Elements()
        val bracket = rule.lastIndexOf('[')
        if (rule.endsWith("]") && bracket >= 0) {
            val before = rule.substring(0, bracket)
            val indexes = rule.substring(bracket + 1, rule.length - 1)
            // `h2[class="item-heading"]` is a CSS attribute selector, not an
            // index list: only treat the brackets as indexes when they really
            // contain numbers / ranges / `!`.
            if (INDEX_CONTENT.matches(indexes)) {
                return applyIndexes(selectBefore(root, before), parseIndexes(indexes))
            }
        }
        // Simple trailing index: `.-1`, `.0:10:2`, `!3`
        val simple = SIMPLE_INDEX.find(rule)
        if (simple != null && simple.range.first > 0) {
            val before = rule.substring(0, simple.range.first)
            val token = simple.value
            val exclude = token.startsWith("!")
            val body = token.trimStart('.', '!')
            val indexes = parseSimpleIndexes(body, exclude)
            return applyIndexes(selectBefore(root, before), indexes)
        }
        return selectBefore(root, rule)
    }

    /** Bracket content that is an index list (`0`, `-1`, `0:2`, `!1,3`). */
    private val INDEX_CONTENT = Regex("""^!?[-\d:,\s]+$""")

    private fun selectBefore(root: Element, beforeRule: String): Elements {
        val rule = beforeRule.trim()
        if (rule.isEmpty() || rule == "children") return root.children()
        // 阅读 splits on '.' and uses the FIRST argument only:
        // `class.post.grid` means class "post".
        val parts = rule.split(".")
        val head = parts.firstOrNull().orEmpty()
        val arg = parts.getOrNull(1).orEmpty()
        return when (head) {
            "class" -> if (arg.isBlank()) Elements() else root.getElementsByClass(arg)
            "tag" -> if (arg.isBlank()) Elements() else root.getElementsByTag(arg)
            "id" -> if (arg.isBlank()) Elements()
            else Elements(root.allElements.toList().filter { it.id() == arg })
            "text" -> if (arg.isBlank()) Elements()
            else Elements(root.allElements.toList().filter { it.ownText().contains(arg) })
            else -> try {
                root.select(rule)
            } catch (_: Exception) {
                Elements(root.allElements.toList().filter { it.text().contains(rule) })
            }
        }
    }

    private data class IndexSpec(val pick: Boolean, val values: List<Int>, val ranges: List<Triple<Int?, Int?, Int>>)

    private fun parseSimpleIndexes(body: String, exclude: Boolean): IndexSpec {
        val values = ArrayList<Int>()
        val ranges = ArrayList<Triple<Int?, Int?, Int>>()
        val pieces = body.split(':')
        if (pieces.size == 1) {
            pieces.first().trim().toIntOrNull()?.let(values::add)
        } else {
            val start = pieces.getOrNull(0)?.trim()?.toIntOrNull()
            val end = pieces.getOrNull(1)?.trim()?.toIntOrNull()
            val step = pieces.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
            ranges.add(Triple(start, end, if (step == 0) 1 else step))
        }
        return IndexSpec(pick = !exclude, values = values, ranges = ranges)
    }

    private fun parseIndexes(raw: String): IndexSpec {
        val values = ArrayList<Int>()
        val ranges = ArrayList<Triple<Int?, Int?, Int>>()
        val exclude = raw.trim().startsWith("!")
        val body = raw.trim().trimStart('!')
        for (partRaw in body.split(',')) {
            val part = partRaw.trim()
            if (part.isEmpty()) continue
            if (part.contains(':')) {
                val pieces = part.split(':')
                val start = pieces.getOrNull(0)?.trim()?.toIntOrNull()
                val end = pieces.getOrNull(1)?.trim()?.toIntOrNull()
                val step = pieces.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
                ranges.add(Triple(start, end, if (step == 0) 1 else step))
            } else {
                part.toIntOrNull()?.let(values::add)
            }
        }
        return IndexSpec(pick = !exclude, values = values, ranges = ranges)
    }

    private fun applyIndexes(elements: Elements, spec: IndexSpec): Elements {
        val size = elements.size
        if (size == 0) return elements
        val indexes = LinkedHashSet<Int>()
        fun normalize(value: Int): Int = if (value < 0) value + size else value
        for (value in spec.values) {
            val index = normalize(value)
            if (index in 0 until size) indexes.add(index)
        }
        for ((startRaw, endRaw, stepRaw) in spec.ranges) {
            var start = normalize(startRaw ?: 0)
            var end = normalize(endRaw ?: (size - 1))
            start = start.coerceIn(0, size - 1)
            end = end.coerceIn(0, size - 1)
            if (start == end) {
                indexes.add(start)
                continue
            }
            val step = if (stepRaw > 0) stepRaw else if (-stepRaw < size) stepRaw + size else 1
            if (start < end) {
                var i = start
                while (i <= end) {
                    indexes.add(i)
                    i += step
                }
            } else {
                var i = start
                while (i >= end) {
                    indexes.add(i)
                    i -= step
                }
            }
        }
        val out = Elements()
        if (spec.pick) {
            for (index in indexes) out.add(elements[index])
        } else {
            for (index in 0 until size) {
                if (index !in indexes) out.add(elements[index])
            }
        }
        return out
    }

    private val SIMPLE_INDEX = Regex("""[.!](-?\d+(?::-?\d+){0,2})$""")
}
