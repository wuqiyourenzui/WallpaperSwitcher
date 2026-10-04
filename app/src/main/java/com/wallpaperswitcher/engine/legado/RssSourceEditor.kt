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
     * Fields the editor renders with their own control (name / url / type /
     * enabled / cookie jar / source group). Everything else that a source JSON
     * carries is listed in the "其余字段" section so no information is hidden.
     */
    val BASIC_FIELDS = listOf(
        "sourceName", "sourceUrl", "sourceGroup",
        "type", "enabled", "enabledCookieJar",
    )

    /** Keys of [rawJson] that no labelled editor section covers, in JSON order. */
    fun remainingKeys(rawJson: String): List<String> {
        val known = (BASIC_FIELDS + RULE_FIELDS + LOGIN_FIELDS + OTHER_FIELDS).toHashSet()
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
