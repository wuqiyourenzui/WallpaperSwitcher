package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.engine.Json

/**
 * Editing of an imported 阅读 (Legado) subscription source.
 *
 * The app stores the original Legado JSON in `RssSource.rawJson` and only reads
 * a subset of it, so editing must merge changes back into that object: unknown
 * fields the source carries stay untouched, edited fields are replaced, blank
 * values are removed (Legado treats absent and empty the same way).
 */
object RssSourceEditor {

    /** Editable JSON fields, in the order the editor shows them. */
    val RULE_FIELDS = listOf(
        "sortUrl",
        "ruleArticles",
        "ruleNextPage",
        "ruleTitle",
        "ruleLink",
        "ruleImage",
        "ruleDescription",
        "rulePubDate",
        "ruleContent",
    )

    val LOGIN_FIELDS = listOf("loginUrl", "loginUi", "loginCheckJs")

    val OTHER_FIELDS = listOf("jsLib", "variable", "header")

    /**
     * 阅读 的编辑页把字段分四个页签（`RssSourceEditActivity.upSourceView`）：
     * 基础 / 起始 / 列表 / WEB_VIEW。这里照抄同一分组，让同一份源在两个应用里
     * 的编辑位置一致。
     */
    /** 基础页签：源身份 + 登录 + 请求 / 变量 / jsLib。 */
    val TAB_BASE_FIELDS = listOf(
        "sourceName", "sourceUrl", "sourceIcon", "sourceGroup", "sourceComment",
        "searchUrl", "sortUrl",
        "loginUrl", "loginUi", "loginCheckJs",
        "coverDecodeJs", "header",
        "variableComment", "concurrentRate", "jsLib",
    )

    /** 起始页签：网页型源的起始页（`startHtml` / `startStyle` / `startJs`）。 */
    val TAB_START_FIELDS = listOf("startHtml", "startStyle", "startJs", "preloadJs")

    /** 列表页签：列表与逐项规则。 */
    val TAB_LIST_FIELDS = listOf(
        "ruleArticles", "ruleNextPage", "ruleTitle", "rulePubDate",
        "ruleDescription", "ruleImage", "ruleLink",
    )

    /** WEB_VIEW 页签：正文页渲染 / 拦截。 */
    val TAB_WEB_FIELDS = listOf(
        "ruleContent", "style", "injectJs",
        "contentWhitelist", "contentBlacklist", "shouldOverrideUrlLoading",
    )

    /** WEB_VIEW 页签里的开关（阅读的 `EditEntity.ViewType.checkBox`）。 */
    val WEB_BOOL_FIELDS = listOf("enableJs", "loadWithBaseUrl", "showWebLog", "cacheFirst")

    /** 所有带标签的字段（"其余字段" 兜底区要排除它们）。 */
    private val LABELLED_FIELDS = (
        TAB_BASE_FIELDS + TAB_START_FIELDS + TAB_LIST_FIELDS + TAB_WEB_FIELDS + WEB_BOOL_FIELDS
        ).toHashSet()

    /**
     * Fields the editor renders with their own control (name / url / type /
     * enabled / cookie jar / source group). Everything else that a source JSON
     * carries is listed in the "其余字段" section so no information is hidden.
     */
    val BASIC_FIELDS = listOf(
        "sourceName", "sourceUrl", "sourceGroup",
        "type", "enabled", "enabledCookieJar", "articleStyle",
    )

    /** Keys of [rawJson] that no labelled editor section covers, in JSON order. */
    fun remainingKeys(rawJson: String): List<String> {
        val known = (BASIC_FIELDS.toHashSet() + LABELLED_FIELDS)
        return try {
            val map = LegadoRss.sourceFields(rawJson) ?: return emptyList()
            map.keys.mapNotNull { it as? String }.filterNot { it in known }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Merge [changes] (field name → new text value) into [originalRawJson].
     * A blank value removes the field; `null` values are ignored.
     * A malformed original JSON starts a fresh object.
     */
    fun applyChanges(originalRawJson: String, changes: Map<String, String?>): String {
        val map = LinkedHashMap<String, Any?>()
        try {
            LegadoRss.sourceFields(originalRawJson)?.forEach { (key, value) ->
                val name = key as? String ?: return@forEach
                map[name] = value
            }
        } catch (_: Exception) {
            // A source without a usable JSON object is rebuilt from the fields.
        }
        for ((key, value) in changes) {
            if (value == null) continue
            val text = value.trim()
            if (text.isEmpty()) {
                map.remove(key)
            } else {
                // Keep the JSON's own type: a boolean/number field edited in the
                // form must not come back as the string "true" / "1".
                map[key] = coerceToOriginalType(map[key], text)
            }
        }
        return Json.encode(map)
    }

    /** Text typed into a form field, stored back as the type it had before. */
    private fun coerceToOriginalType(previous: Any?, text: String): Any = when (previous) {
        is Boolean -> text.equals("true", ignoreCase = true)
        is Long, is Int -> text.toLongOrNull() ?: text
        is Double, is Float -> text.toDoubleOrNull() ?: text
        else -> {
            // JSON has no type info for a missing key: guess the obvious cases.
            when {
                text.equals("true", ignoreCase = true) -> true
                text.equals("false", ignoreCase = true) -> false
                else -> text
            }
        }
    }

    /**
     * Merge numeric/boolean fields (type, enabled, enabledCookieJar…) with the
     * same "unknown keys survive" rule.
     */
    fun applyTypedChanges(originalRawJson: String, changes: Map<String, Any?>): String {
        val map = LinkedHashMap<String, Any?>()
        try {
            (Json.parse(originalRawJson) as? Map<*, *>)?.forEach { (key, value) ->
                val name = key as? String ?: return@forEach
                map[name] = value
            }
        } catch (_: Exception) {
        }
        for ((key, value) in changes) {
            if (value == null) map.remove(key) else map[key] = value
        }
        return Json.encode(map)
    }

    /** A convenience reader for the editor screen. */
    fun fieldValue(rawJson: String, key: String): String {
        return try {
            // 数组形式（`[{…}]`，阅读导出/分享常见）取第一条。
            val map = when (val root = Json.parse(rawJson)) {
                is Map<*, *> -> root
                is List<*> -> root.firstOrNull() as? Map<*, *> ?: return ""
                else -> return ""
            }
            when (val value = map[key]) {
                null -> ""
                is String -> value
                else -> value.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }
}
