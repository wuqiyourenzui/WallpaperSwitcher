package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.engine.Json

/**
 * 阅读 的链接请求选项：规则可以写成
 * `https://site/a,{"headers":{"Referer":"https://site/list"},"body":"p=1","method":"POST"}`。
 * 阅读在抓取前把选项并进请求（`AnalyzeUrl.analyzeUrl` → `UrlOptionSerializer`），
 * 所以同一串地址既能带自定义请求头，也能改 method / body。
 *
 * 这里只保留本应用用得上的三项：请求头、method、body（`js` / `webJs` /
 * `retry` / `charset` 等阅读字段用不到，解析后忽略）。
 */
internal data class UrlOption(
    val headers: Map<String, String> = emptyMap(),
    val method: String? = null,
    val body: String? = null,
) {
    val isEmpty: Boolean get() = headers.isEmpty() && method == null && body == null
}

internal object UrlOptions {

    /** 阅读 `AppPattern.urlParamPattern`：逗号后紧跟 `{` 才是选项。 */
    private val SEPARATOR = Regex("""\s*,\s*(?=\{)""")

    fun split(raw: String): Pair<String, UrlOption> {
        val match = SEPARATOR.find(raw) ?: return raw to UrlOption()
        val url = raw.substring(0, match.range.first)
        if (url.isBlank()) return raw to UrlOption()
        return url to parse(raw.substring(match.range.last + 1))
    }

    fun strip(raw: String): String = split(raw).first

    private fun parse(json: String): UrlOption {
        val map = parseLoose(json) as? Map<*, *> ?: return UrlOption()
        val headers = LinkedHashMap<String, String>()
        (map["headers"] as? Map<*, *>)?.forEach { (key, value) ->
            if (key is String && value != null) headers[key] = value.toString()
        }
        return UrlOption(
            headers = headers,
            method = (map["method"] as? String)?.takeIf { it.isNotBlank() },
            body = when (val body = map["body"]) {
                null -> null
                is String -> body.takeIf { it.isNotBlank() }
                else -> Json.encode(body)
            },
        )
    }

    /**
     * 严格 JSON 解析失败时按阅读的「宽松解析」兜底：源里常见的
     * `,{headers:{'X':'1'}}`（单引号、不带引号的键）也要能用。
     */
    private fun parseLoose(json: String): Any? {
        try {
            return Json.parse(json)
        } catch (_: Exception) {
        }
        return try {
            Json.parse(normalize(json))
        } catch (_: Exception) {
            null
        }
    }

    /** `'x'` → `"x"`，`{key:` / `,key:` → `{"key":`（只动引号外的字符）。 */
    private fun normalize(json: String): String {
        val out = StringBuilder(json.length + 16)
        var index = 0
        var inDouble = false
        while (index < json.length) {
            val c = json[index]
            when {
                c == '\\' && inDouble -> {
                    out.append(c)
                    if (index + 1 < json.length) out.append(json[index + 1])
                    index += 2
                    continue
                }
                c == '"' -> {
                    inDouble = !inDouble
                    out.append(c)
                }
                c == '\'' && !inDouble -> {
                    out.append('"')
                    index++
                    while (index < json.length) {
                        val inner = json[index]
                        if (inner == '\\' && index + 1 < json.length) {
                            out.append(inner).append(json[index + 1])
                            index += 2
                            continue
                        }
                        if (inner == '\'' || inner == '"') {
                            if (inner == '"') out.append('\\')
                            out.append('"')
                            index++
                            break
                        }
                        out.append(inner)
                        index++
                    }
                    continue
                }
                !inDouble && (c.isLetter() || c == '_') -> {
                    // 不带引号的键：`{headers:` / `,Referer:`
                    val previous = out.lastOrNull { !it.isWhitespace() }
                    var end = index
                    while (end < json.length && (json[end].isLetterOrDigit() || json[end] == '_')) end++
                    val next = json.getOrNull(end)
                    if ((previous == '{' || previous == ',') && next == ':') {
                        out.append('"').append(json, index, end).append('"')
                        index = end
                        continue
                    }
                    out.append(c)
                }
                else -> out.append(c)
            }
            index++
        }
        return out.toString()
    }
}
