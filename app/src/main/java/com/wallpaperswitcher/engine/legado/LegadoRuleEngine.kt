package com.wallpaperswitcher.engine.legado

import com.jayway.jsonpath.JsonPath
import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.engine.OnlineSourceRules
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.mozilla.javascript.Scriptable

/**
 * 阅读 (Legado) 订阅源规则引擎的核心实现（P1：规则语法 + CSS/JSON/XPath）。
 *
 * Implemented from the rule semantics of the Legado-E project (GPL-3.0) without
 * copying its code: the project is no longer distributed officially and its
 * license would otherwise apply to this app. Supported here:
 *
 *  - rule chaining with `@`, respecting `[]`/`()` and quotes;
 *  - selector combination `&&` (concat), `||` (first non-empty), `%%` (interleave);
 *  - default (jsoup) selectors: `class.X`, `id.X`, `tag.X`, `text.X`,
 *    `children`, raw CSS, `@css:`, plus the index syntax `.N` / `!N` /
 *    `[n]` / `[start:end:step]` (negative indexes allowed);
 *  - final extraction: `text`, `textNodes`, `ownText`, `html`, `all`, or an
 *    attribute name (`href`, `src`, …);
 *  - JSONPath (`$.…`, `@json:`), XPath (`@xpath:`, a leading `/`), and the
 *    `##regex##replacement[##first]` post-replacement;
 *  - `{{key}}` substitution from the source `variable` map (JS inside `{{}}`
 *    and `@js:` rules need the Rhino stage, P2 - they log and yield empty).
 */
internal class LegadoRuleEngine(
    private val baseUrl: String = "",
    private val variables: Map<String, String> = emptyMap(),
    private val sourceId: Long = 0L,
    /** 阅读 `jsLib`: a JS library shared by every script of the source. */
    private val jsLib: String? = null,
) {

    data class RuleError(val rule: String, val reason: String)

    private val errors = ArrayList<RuleError>()
    /** The page content the current rule chain started from (for @webjs:). */
    private var rootContent: Any? = null
    /** Last parsed Jsoup document, re-used across a rule chain (阅读 parses once). */
    private var cachedHtml: String? = null
    private var cachedDocument: org.jsoup.nodes.Document? = null
    /** One reusable JS scope for this engine (i.e. for this list item). */
    private val jsScopes = HashMap<String, org.mozilla.javascript.Scriptable>()

    private fun documentOf(html: String): org.jsoup.nodes.Document {
        val cached = cachedDocument
        val key = cachedHtml
        if (cached != null && key != null && (key === html || key == html)) return cached
        val document = Jsoup.parse(html, baseUrl)
        cachedHtml = html
        cachedDocument = document
        return document
    }

    fun takeErrors(): List<RuleError> = errors.toList()

    /** Test/diagnostic view of the parsed segments. */
    internal fun debugSegments(rule: String): List<String> =
        splitSegments(rule).map { "${it.mode}:${it.rule}" }

    fun getElements(rule: String, content: Any?): List<Any> {
        if (rule.isBlank() || content == null) return emptyList()
        rootContent = content
        val effective = rule.trim().let { if (it.startsWith("@@")) it.substring(2) else it }
        if (effective.isBlank()) return emptyList()
        var current: Any? = content
        for (segment in splitSegments(effective)) {
            current = evaluate(segment, current) ?: return emptyList()
        }
        return when (current) {
            is Elements -> current.toList()
            is Element -> listOf(current)
            is List<*> -> current.filterNotNull()
            null -> emptyList()
            else -> listOf(current)
        }
    }

    /**
     * Text value of [rule]; the first result by default, every result joined by
     * newlines when [joinAll] is set (阅读's `getString` behaviour, which the
     * article-content rules rely on).
     */
    fun getString(
        rule: String?,
        content: Any?,
        isUrl: Boolean = false,
        joinAll: Boolean = false,
    ): String {
        if (rule.isNullOrBlank() || content == null) return ""
        rootContent = content
        // 阅读 的 `@@` 转义：剥掉前缀后按普通规则解析。订阅源里常见的
        // `{{@@tag.img@html}}`（好壁纸的正文规则）就靠这一条，否则会把
        // 规则原文当成正文显示出来。
        val effective = rule.trim().let { if (it.startsWith("@@")) it.substring(2) else it }
        if (effective.isBlank()) return ""
        // 阅读 的 `{{}}` 里除了变量和 JS，还能写规则（h视频的
        // `/api/videoplay/{{$.id}}?uuid=1`、`{{$.coverbase64.url}}` 都是这种）。
        // 变量在 parseSegment 里已按变量表替换，这里把剩下的表达式按「规则优先、
        // JS 兜底」在当前内容上求值，再把结果交给正常的规则链。
        val resolved = resolveBracedExpressions(effective, content)
        // `{{…}}` 只是取值占位（模板里没有选择器/JS 之类的规则骨架）时，替换完
        // 就是字面值：阅读对 `{{$.playtimes}}`、`📆{{$.updated_at## .*}}` 都是
        // 直接取值，不再把结果当选择器解析 —— 否则纯数字/日期会被当成 CSS 选择器
        // 而返回整个条目对象。
        if (effective.contains("{{") && !templateHasRuleMarkers(effective)) {
            val value = OnlineSourceRules.decodeHtmlEntities(resolved.trim())
            return if (isUrl) OnlineSourceRules.resolveUrl(baseUrl, value) ?: value else value
        }
        // 替换后就是一条地址（`/api/videoplay/1?uuid=1`、`/c/1.jpg`）时按字面值返回：
        // 阅读里这类规则就是 URL 本身，不是 XPath/选择器 —— 否则会被当选择器解析成空。
        if (looksLikeUrlLiteral(resolved)) {
            val value = OnlineSourceRules.decodeHtmlEntities(resolved.trim())
            return if (isUrl) OnlineSourceRules.resolveUrl(baseUrl, value) ?: value else value
        }
        val segments = splitSegments(resolved)
        if (segments.isEmpty()) return ""
        if (segments.size == 1 && segments.first().literal) {
            val value = OnlineSourceRules.decodeHtmlEntities(segments.first().rule)
            return if (isUrl) OnlineSourceRules.resolveUrl(baseUrl, value) ?: value else value
        }
        var current: Any? = content
        var lastMode = segments.last().mode
        for ((index, segment) in segments.withIndex()) {
            if (index == segments.lastIndex && segment.mode == Mode.CSS) {
                // The final jsoup segment is the value extraction (`text` /
                // `html` / an attribute name), exactly like 阅读's getResultLast.
                break
            }
            val nextIsSelector = segments.getOrNull(index + 1)?.mode == Mode.CSS
            if (segment.mode == Mode.CSS && !nextIsSelector) {
                // 阅读 把「JS / JSON / XPath 之前的最后一段」当作取值而不是选择器
                // （AnalyzeByJSoup.getResultList：只有最后一段走 getResultLast）。
                // `tag.a@href@js:result+'…'` 因此拿到的是属性值，而不是把 `href`
                // 当成 CSS 标签选择器。只有形如 `class.x` / `div.x` 的规则才继续
                // 当选择器链处理。
                val takesValue = isExtraction(segment.rule) || isPlainAttribute(segment.rule)
                if (!takesValue) {
                    // 选择器后面接 JS / JSON / XPath：先把选择器真正应用出来，把
                    // 元素交给下一段（阅读就是这样把 result 传给 JS 的）。之前
                    // 这里把选择器文本当成「取值说明」，`class.item@js:result`
                    // 拿到的是空字符串。
                    current = evaluate(segment, current) ?: return ""
                    continue
                }
                // The jsoup chain ends here: extract its text/attribute before
                // handing the value to a JS/JSON/XPath segment.
                val elements = when (current) {
                    is Elements -> current
                    is Element -> Elements(current)
                    is String -> Elements(documentOf(current).allElements)
                    else -> null
                }
                current = if (elements != null) {
                    val values = extract(elements, segment.rule.ifBlank { "text" })
                    if (joinAll) values.joinToString("\n") else values.firstOrNull().orEmpty()
                } else {
                    current
                }
            } else {
                current = evaluate(segment, current) ?: return ""
            }
        }
        val extraction = if (segments.last().mode == Mode.CSS) segments.last().rule else null
        val rawValue = when {
            current is Element || current is Elements -> {
                val elements = if (current is Elements) current else Elements(current as Element)
                val values = extract(elements, extraction?.ifBlank { "text" } ?: "text")
                if (joinAll) values.joinToString("\n") else values.firstOrNull().orEmpty()
            }
            current is List<*> -> when (val first = current.firstOrNull()) {
                is Element -> first.text()
                null -> ""
                else -> first.toString()
            }
            current is String && extraction != null -> {
                if (extraction.isBlank() || extraction == "text" ||
                    extraction == "ownText" || extraction == "textNodes"
                ) {
                    current
                } else {
                    val elements = Elements(documentOf(current).allElements)
                    val values = extract(elements, extraction)
                    if (joinAll) values.joinToString("\n") else values.firstOrNull().orEmpty()
                }
            }
            current == null -> ""
            else -> current.toString()
        }
        val replaced = segments.last().replace?.let { spec ->
            if (spec.first) {
                spec.pattern.replaceFirst(rawValue, spec.replacement)
            } else {
                spec.pattern.replace(rawValue, spec.replacement)
            }
        } ?: rawValue
        val text = OnlineSourceRules.decodeHtmlEntities(replaced)
        if (!isUrl) return text
        if (text.isBlank()) return baseUrl
        // 阅读 URLs may carry per-request options after a `,{...}` suffix
        // (`@js: result + ',{"headers":…}'`): only the URL part is resolved.
        val comma = text.indexOf(",{")
        if (comma >= 0) {
            val base = text.substring(0, comma)
            val options = text.substring(comma)
            return (OnlineSourceRules.resolveUrl(baseUrl, base) ?: base) + options
        }
        return OnlineSourceRules.resolveUrl(baseUrl, text) ?: text
    }

    // --- Rule splitting -----------------------------------------------------------

    private data class ReplaceSpec(val pattern: Regex, val replacement: String, val first: Boolean)

    private data class Segment(
        val mode: Mode,
        val rule: String,
        val replace: ReplaceSpec? = null,
        val literal: Boolean = false,
    )

    private enum class Mode { CSS, JSON, XPATH, JS, WEBJS }

    private fun splitSegments(rule: String): List<Segment> {
        val text = rule.trim()
        val out = ArrayList<Segment>()
        var index = 0
        var normal = StringBuilder()
        fun flushNormal() {
            val chunk = normal.toString().trim()
            normal = StringBuilder()
            if (chunk.isEmpty()) return
            for (part in RuleSplitter.splitAt(chunk)) {
                val piece = part.trim()
                if (piece.isNotEmpty()) out.add(parseSegment(piece))
            }
        }
        while (index < text.length) {
            val jsAt = text.indexOf("@js:", index, ignoreCase = true)
            val jsTag = text.indexOf("<js>", index, ignoreCase = true)
            val webJs = text.indexOf("@webjs:", index, ignoreCase = true)
            val start = listOf(jsAt, jsTag, webJs).filter { it >= 0 }.minOrNull() ?: -1
            if (start < 0) {
                normal.append(text, index, text.length)
                break
            }
            normal.append(text, index, start)
            if (start == webJs) {
                // @webjs: also consumes the rest of the rule.
                flushNormal()
                out.add(Segment(Mode.WEBJS, text.substring(webJs + 7).trim()))
                index = text.length
            } else if (start == jsAt) {
                // @js: consumes the rest of the rule (阅读's JS_PATTERN).
                flushNormal()
                out.add(Segment(Mode.JS, text.substring(jsAt + 4).trim()))
                index = text.length
            } else {
                val end = text.indexOf("</js>", jsTag + 4, ignoreCase = true)
                if (end < 0) {
                    normal.append(text, start, text.length)
                    break
                }
                flushNormal()
                out.add(Segment(Mode.JS, text.substring(jsTag + 4, end).trim()))
                index = end + 5
            }
        }
        flushNormal()
        return out
    }

    private fun parseSegment(raw: String): Segment {
        var rule = raw
        var mode = Mode.CSS
        var literal = false
        // 阅读 允许规则尾部带请求选项（`…@href,{"headers":…}`）：它们不属于
        // 选择器/取值说明，先剥掉（JS 规则里可能出现 `,{`，所以跳过 JS 规则）。
        if (!rule.startsWith("@js:", true) && !rule.startsWith("<js>", true)) {
            val optionsAt = rule.indexOf(",{")
            if (optionsAt > 0) rule = rule.substring(0, optionsAt)
        }
        val hadVariable = rule.contains("{{")
        when {
            rule.startsWith("@@") -> {
                literal = true
                rule = rule.substring(2)
            }
            rule.startsWith("@CSS:", true) -> {
                mode = Mode.CSS
                rule = rule.substring(5)
            }
            rule.startsWith("@XPath:", true) -> {
                mode = Mode.XPATH
                rule = rule.substring(7)
            }
            rule.startsWith("@Json:", true) -> {
                mode = Mode.JSON
                rule = rule.substring(6)
            }
            rule.startsWith("$.") || rule.startsWith("$[") -> mode = Mode.JSON
            rule.startsWith("/") -> mode = Mode.XPATH
            rule.contains("<js>") || rule.startsWith("@js:") -> mode = Mode.JS
        }
        // {{...}} substitution (variables and {{page}}).
        rule = substituteVariables(rule)
        if (!literal && rule.contains("<js>")) mode = Mode.JS
        // A segment that was only a {{variable}} / a literal URL template is a
        // value, not a selector.
        if (!literal && hadVariable && rule.isNotBlank() &&
            !rule.contains("@") &&
            !rule.startsWith("class.") && !rule.startsWith("id.") &&
            !rule.startsWith("tag.") && !rule.startsWith("text.") &&
            !rule.startsWith("children")
        ) {
            literal = true
        }
        var replace: ReplaceSpec? = null
        if (rule.contains("##")) {
            val parts = rule.split("##")
            if (parts.size >= 2 && parts[0].isNotBlank()) {
                rule = parts[0]
                val pattern = parts.getOrNull(1).orEmpty()
                val replacement = parts.getOrNull(2).orEmpty()
                val first = parts.getOrNull(3).equals("first", ignoreCase = true)
                replace = try {
                    ReplaceSpec(Regex(pattern), replacement, first)
                } catch (_: Exception) {
                    errors.add(RuleError(raw, "bad regex"))
                    null
                }
            }
        }
        return Segment(mode, rule.trim(), replace, literal)
    }

    private fun substituteVariables(rule: String): String {
        if (!rule.contains("{{")) return rule
        val out = StringBuilder()
        var index = 0
        while (index < rule.length) {
            val start = rule.indexOf("{{", index)
            if (start < 0) {
                out.append(rule, index, rule.length)
                break
            }
            val end = rule.indexOf("}}", start + 2)
            if (end < 0) {
                out.append(rule, index, rule.length)
                break
            }
            out.append(rule, index, start)
            val key = rule.substring(start + 2, end).trim()
            val value = variables[key]
            if (value != null) {
                out.append(value)
            } else {
                // JS / unsupported expression: keep the literal, the caller
                // reports the JS rules separately (P2 adds Rhino).
                errors.add(RuleError(rule, "unsupported {{$key}}"))
                out.append(rule, start, end + 2)
            }
            index = end + 2
        }
        return out.toString()
    }

    /**
     * 把 `{{…}}` 里的表达式求值：变量 →（像规则时）规则 → JS →（兜底）规则。
     * 全部取不到时保留原文，交给上层报错。
     */
    private fun resolveBracedExpressions(rule: String, content: Any?): String {
        if (!rule.contains("{{")) return rule
        return RuleSplitter.innerRule(rule) { expression ->
            val key = expression.trim()
            if (key.isEmpty()) return@innerRule null
            variables[key]?.let { return@innerRule it }
            if (key == "page") return@innerRule variables["page"] ?: "1"

            fun asRule(): String = try {
                getString(key, content)
            } catch (_: Exception) {
                ""
            }

            fun asJs(): String? = try {
                LegadoJs.eval(key, variables, baseUrl, sourceId, jsLib = jsLib)
            } catch (_: Exception) {
                null
            }

            if (looksLikeRuleExpression(key)) {
                asRule().takeIf { it.isNotBlank() }?.let { return@innerRule it }
                asJs()?.takeIf { it.isNotBlank() }?.let { return@innerRule it }
            } else {
                asJs()?.takeIf { it.isNotBlank() }?.let { return@innerRule it }
                asRule().takeIf { it.isNotBlank() }?.let { return@innerRule it }
            }
            null
        }
    }

    /** `$.id` / `$[0]` / `/xpath` / `class.x@text` / `a@href` 这类都按规则求值。 */
    private fun looksLikeRuleExpression(expression: String): Boolean {
        val e = expression.trim()
        return e.startsWith("$") || e.startsWith("/") || e.startsWith("class.") ||
            e.startsWith("id.") || e.startsWith("tag.") || e.startsWith("text.") ||
            e.contains("@")
    }

    /**
     * `/path?query` 或 `/path/file.ext` 这类写法是「字面地址」，不是 XPath：
     * XPath 里不会出现查询串，也不会以 `1.jpg` 这样的文件段结尾。
     */
    private fun looksLikeUrlLiteral(rule: String): Boolean {
        val r = rule.trim()
        if (r.isEmpty()) return false
        if (r.startsWith("http://", ignoreCase = true) ||
            r.startsWith("https://", ignoreCase = true)
        ) {
            return true
        }
        if (!r.startsWith("/")) return false
        if (r.contains('?')) return true
        return r.substringAfterLast('/').contains('.')
    }

    /** 去掉 `{{…}}` 之后还剩不剩规则骨架（选择器 / `@` / `##` / JS…）。 */
    private fun templateHasRuleMarkers(rule: String): Boolean {
        // 用 RuleSplitter 的括号匹配去掉 {{…}}，不依赖正则引擎：Android 的 ICU
        // 对未转义的 `}` 会直接抛 PatternSyntaxException（桌面 JVM 容忍，所以
        // 单元测试发现不了 —— 真机上整次刷新因此变成「未知错误」）。
        val skeleton = RuleSplitter.innerRule(rule) { "" }.trim()
        if (skeleton.isEmpty()) return false
        if (skeleton.contains("@") || skeleton.contains("##") ||
            skeleton.contains("&&") || skeleton.contains("||") ||
            skeleton.contains("%%") || skeleton.contains("<js>")
        ) {
            return true
        }
        return skeleton.startsWith("$") || skeleton.startsWith("class.") ||
            skeleton.startsWith("id.") || skeleton.startsWith("tag.") ||
            skeleton.startsWith("text.") || skeleton.startsWith("children")
    }

    // --- Evaluation ---------------------------------------------------------------

    private fun evaluate(segment: Segment, content: Any?): Any? {
        if (content == null) return null
        if (segment.literal) return segment.rule
        val result: Any? = when (segment.mode) {
            Mode.JS -> evalJs(segment.rule, content)
            Mode.WEBJS -> runWebJs(segment.rule, content)
            Mode.JSON -> analyzeJson(content, segment.rule)
            Mode.XPATH -> analyzeXPath(content, segment.rule)
            Mode.CSS -> analyzeCss(content, segment.rule)
        }
        if (result == null) return null
        val replace = segment.replace ?: return result
        val text = result.toString()
        val replaced = if (replace.first) {
            replace.pattern.replaceFirst(text, replace.replacement)
        } else {
            replace.pattern.replace(text, replace.replacement)
        }
        return replaced
    }

    /**
     * 阅读's `@webjs:`: run the script inside a hidden WebView loaded with the
     * page the rule chain started from; `result` is the current chain value.
     */
    private fun runWebJs(script: String, current: Any?): Any? {
        val html = when (val root = rootContent) {
            is String -> root
            is Element -> root.outerHtml()
            is Elements -> root.outerHtml()
            else -> root?.toString().orEmpty()
        }
        if (html.isBlank()) return null
        val value = WebJsRunner.run(html, baseUrl, script, jsonOf(current))
        if (value == null) {
            errors.add(RuleError(script.take(60), "webjs produced no value"))
        }
        return value
    }

    private fun jsonOf(value: Any?): String = when (value) {
        null -> "null"
        is String -> Json.encode(value)
        is Element -> Json.encode(value.outerHtml())
        is Elements -> Json.encode(value.map { it.outerHtml() })
        is List<*> -> Json.encode(value.map { it?.toString().orEmpty() })
        else -> Json.encode(value.toString())
    }

    /**
     * 阅读's `@js:` / `<js>` rules: the script sees `result` (the current
     * value), `baseUrl`, `cookie` and a `java` helper object; its completion
     * value (or the mutated `result`) becomes the new value.
     */
    private fun evalJs(script: String, content: Any?): Any? {
        if (script.isBlank()) return content
        val initial = when (content) {
            null -> ""
            is String -> content
            else -> content.toString()
        }
        val currentContent = content
        // Delegates to the shared engine so the script is compiled once and the
        // source's jsLib scope is reused (阅读's scriptCache / SharedJsScope).
        val result = LegadoJs.run(
            script = script,
            bindings = mapOf("result" to initial),
            baseUrl = baseUrl,
            sourceId = sourceId,
            textProvider = { rule ->
                LegadoRuleEngine(baseUrl, variables, sourceId, jsLib)
                    .getString(rule, currentContent)
            },
            elementsProvider = { rule ->
                LegadoRuleEngine(baseUrl, variables, sourceId, jsLib)
                    .getElements(rule, currentContent)
            },
            jsLib = jsLib,
            scopeCache = jsScopes,
        )
        if (result.error != null) {
            errors.add(RuleError(script.take(80), "js failed: ${result.error}"))
            return null
        }
        if (result.value == null) {
            errors.add(RuleError(script.take(60), "js produced no value"))
        }
        return result.value
    }

    private fun analyzeCss(content: Any?, rule: String): Any? {
        if (rule.isBlank()) return content
        if (content is Elements) {
            // 阅读 applies the next selector to EVERY element of the current
            // result set (a nav bar also has class "clearfix", so stopping at
            // the first one would drop the real article list).
            val out = Elements()
            for (element in content) {
                out.addAll(JsoupAnalyzer.select(element, rule))
            }
            return out
        }
        val root: Element = when (content) {
            is Element -> content
            is String -> documentOf(content)
            else -> documentOf(content.toString())
        }
        if (isExtraction(rule)) return root
        return JsoupAnalyzer.select(root, rule)
    }

    private fun analyzeJson(content: Any?, rule: String): Any? {
        val text = when (content) {
            is String -> content
            // A JSON item selected by a previous JSONPath step: re-encode it so a
            // nested JSONPath (`$.title` inside `{{…}}`, `$.rescont.data[*]` list
            // items) works - `toString()` produced Kotlin's `{id=1}` shape, which
            // is not JSON and made every nested path fail (h视频's whole source
            // is built on those).
            is Map<*, *>, is List<*> -> Json.encode(content)
            else -> content.toString()
        }
        val json = try {
            Json.parse(text)
        } catch (_: Exception) {
            return null
        }
        val path = if (rule.startsWith("$")) rule else "$.$rule"
        return try {
            JsonPath.read<Any?>(Json.encode(json), path)
        } catch (t: Throwable) {
            errors.add(RuleError(rule, "json path failed: ${t.javaClass.simpleName}"))
            null
        }
    }

    private fun analyzeXPath(content: Any?, rule: String): Any? {
        val html = when (content) {
            is String -> content
            is Element -> content.outerHtml()
            is Elements -> content.outerHtml()
            else -> content.toString()
        }
        // 阅读（新版 AnalyzeByXPath）用 jsoup 的 selectXpath，并单独拆 `…/@attr`：
        // 属性节点 jsoup 选不到，所以先选元素再取属性。
        val attribute = splitXPathAttribute(rule)
        if (attribute != null) {
            val (path, attr) = attribute
            return selectXPath(html, path.ifEmpty { "." }, Element::class.java)
                ?.mapNotNull { element -> element.attr(attr).takeIf(String::isNotBlank) }
        }
        val nodes = try {
            val document = cn.wanghaomiao.xpath.model.JXDocument(documentOf(html))
            document.selN(rule).mapNotNull { node ->
                when (node) {
                    is Element -> node
                    else -> textOfXPathNode(node)
                }
            }
        } catch (t: Throwable) {
            errors.add(RuleError(rule, "xpath failed: ${t.javaClass.simpleName}"))
            null
        }
        if (!nodes.isNullOrEmpty()) return nodes
        // JXDocument 的语法不支持绝对路径（`/html/…` 直接抛错）；阅读新版的
        // jsoup selectXpath 支持，用它兜底 —— 包括 `/html/…/text()` 这类写法。
        return selectXPath(html, rule, org.jsoup.nodes.Node::class.java)
            ?.mapNotNull { node ->
                when (node) {
                    is Element -> node
                    else -> textOfXPathNode(node)
                }
            }
    }

    private fun <T : org.jsoup.nodes.Node> selectXPath(
        html: String,
        path: String,
        type: Class<T>,
    ): List<T>? = try {
        documentOf(html).selectXpath(path, type)
    } catch (_: Throwable) {
        null
    }

    /** `//div/@data-id` → `//div` + `data-id`（阅读新版 AnalyzeByXPath 的做法）。 */
    private fun splitXPathAttribute(rule: String): Pair<String, String>? {
        val at = rule.indexOf("/@")
        if (at < 0) return null
        val attr = rule.substring(at + 2)
        if (attr.isBlank() || attr.contains('/')) return null
        return rule.substring(0, at).trim() to attr.trim()
    }

    /** JX_TEXT/JX_NODE toString() is markup; keep the inner text. */
    private fun textOfXPathNode(node: Any?): String? {
        val raw = node?.toString() ?: return null
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("<JX_")) {
            val start = raw.indexOf('>')
            val end = raw.lastIndexOf('<')
            if (start in 0 until end) return raw.substring(start + 1, end).trim()
        }
        return raw
    }

    // --- Final value extraction ---------------------------------------------------

    private fun isExtraction(rule: String): Boolean =
        rule == "text" || rule == "textNodes" || rule == "ownText" ||
            rule == "html" || rule == "all"

    /** 纯属性名（`href`、`data-src`…）——阅读把它当取值，不是 CSS 选择器。 */
    private fun isPlainAttribute(rule: String): Boolean =
        PLAIN_ATTRIBUTE.matches(rule)

    private val PLAIN_ATTRIBUTE = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")

    private fun extract(elements: Elements, rule: String): List<String> {
        val out = ArrayList<String>()
        when (rule) {
            "text" -> elements.forEach { el ->
                el.text().takeIf { it.isNotEmpty() }?.let(out::add)
            }
            "textNodes" -> elements.forEach { el ->
                val text = el.textNodes().mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
                if (text.isNotEmpty()) out.add(text.joinToString("\n"))
            }
            "ownText" -> elements.forEach { el ->
                el.ownText().takeIf { it.isNotEmpty() }?.let(out::add)
            }
            "html" -> {
                val copy = elements.clone()
                copy.select("script").remove()
                copy.select("style").remove()
                out.add(copy.outerHtml())
            }
            "all" -> out.add(elements.outerHtml())
            else -> elements.forEach { el ->
                var value = el.attr(rule)
                // Lazy-loading sites keep the real URL in data-original /
                // data-lazy-src / … while `src` holds a placeholder; a rule
                // asking for an image source falls back to those.
                val placeholder = value.isBlank() || value.startsWith("data:", ignoreCase = true)
                if (placeholder && rule.lowercase() in IMAGE_SOURCE_ATTRS) {
                    value = IMAGE_FALLBACK_ATTRS.firstNotNullOfOrNull { attr ->
                        el.attr(attr).takeIf {
                            it.isNotBlank() && !it.startsWith("data:", ignoreCase = true)
                        }
                    }.orEmpty()
                }
                if (value.isNotBlank() && value !in out) out.add(value)
            }
        }
        return out
    }

    private val IMAGE_SOURCE_ATTRS = setOf("src", "data-src")
    private val IMAGE_FALLBACK_ATTRS = listOf(
        "data-src", "data-original", "data-lazy-src", "data-echo", "data-url", "src"
    )
}
