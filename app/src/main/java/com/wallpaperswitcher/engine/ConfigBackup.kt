package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.util.AppLog

/**
 * 配置导出 / 导入: the wallpaper configuration (groups + global wallpaper
 * settings) as a small, self-contained JSON document.
 *
 * What is exported: the groups (name, 应用位置, enabled flag, own interval /
 * mode / 时间规则) and the wallpaper-relevant global settings. What is NOT
 * exported: the media itself. The media rows reference either MediaStore ids or
 * SAF folder grants that only exist on the phone they were imported on, so
 * copying the files is out of scope - the group cards simply start empty and
 * the user re-imports the folders.
 *
 * The JSON reader/writer is written here instead of using `org.json` on
 * purpose: `org.json` is an Android platform class (a stub in unit tests), and
 * the codec - escaping, malformed input, round-trip - is exactly the part that
 * must be tested. Kept dependency-free and pure.
 */
object ConfigBackup {

    const val FORMAT = "wallpaper-switcher-config"
    /** 3 = 分组星期/主题/筛选/排序 + 超分算法等壁纸设置也进配置。 */
    const val VERSION = 3
    private const val TAG = "ConfigBackup"
    /** Suggested SAF file name (without extension). */
    const val FILE_NAME = "wallpaper-switcher-config"

    /**
     * Global settings that describe the wallpaper experience. Deliberately NOT
     * included: theme/language (device preferences), log settings, floating
     * button appearance (device-specific), and anything holding a URI that is
     * only valid on the exporting phone.
     */
    val EXPORTED_SETTINGS: List<String> = listOf(
        SettingsKeys.GLOBAL_INTERVAL_MS,
        SettingsKeys.GLOBAL_SWITCH_MODE,
        SettingsKeys.GLOBAL_SCALE_MODE,
        SettingsKeys.CLARITY_MODE,
        SettingsKeys.ROTATE_MISMATCH_ENABLED,
        SettingsKeys.ROTATE_MISMATCH_CW,
        SettingsKeys.SWITCH_FADE_ENABLED,
        SettingsKeys.SWITCH_TRANSITION,
        SettingsKeys.VIDEO_SOUND_ENABLED,
        SettingsKeys.UNLOCK_SWITCH_ENABLED,
        SettingsKeys.LOCK_TIMER_ENABLED,
        SettingsKeys.LOCK_INTERVAL_MS,
        SettingsKeys.ENHANCE_ALGO,
        SettingsKeys.KEN_BURNS_ENABLED,
        SettingsKeys.VIDEO_PLAY_TO_END,
        SettingsKeys.FAVORITE_BOOST,
        SettingsKeys.RECENT_NO_REPEAT,
        SettingsKeys.RSS_WIFI_ONLY,
        SettingsKeys.RSS_DAILY_LIMIT_MB,
        SettingsKeys.RSS_ORPHAN_TTL_DAYS
    )

    /** One group as stored in the file. */
    data class GroupConfig(
        val name: String,
        val target: String = "BOTH",
        val isEnabled: Boolean = true,
        val intervalMs: Long = 0L,
        val switchMode: String = "",
        val activeFromMinute: Int = -1,
        val activeToMinute: Int = -1,
        val activeDays: Int = 0,
        val activeThemeMode: String = "",
        val filterMode: String = "",
        val sortOrder: String = ""
    )

    data class Config(
        val groups: List<GroupConfig>,
        val settings: Map<String, String> = emptyMap(),
        /** Imported 阅读 subscriptions (name/url/type/enabled + original JSON). */
        val sources: List<SourceConfig> = emptyList()
    )

    /**
     * One subscription source as stored in the file. [rawJson] is the original
     * 阅读 object (rules / header / sortUrl / …) carried as a JSON *string*, so
     * a round trip restores the source exactly.
     */
    data class SourceConfig(
        val name: String,
        val url: String,
        val type: Int = 0,
        val enabled: Boolean = true,
        val rawJson: String = ""
    )

    /** How many entries an import created. */
    data class ApplyResult(val groups: Int, val sources: Int)

    // --- Pure codec -----------------------------------------------------------------

    fun encode(config: Config): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": \"").append(escape(FORMAT)).append("\",\n")
        sb.append("  \"version\": ").append(VERSION).append(",\n")
        sb.append("  \"settings\": {")
        if (config.settings.isEmpty()) {
            sb.append("},\n")
        } else {
            sb.append('\n')
            config.settings.entries.forEachIndexed { index, (key, value) ->
                sb.append("    \"").append(escape(key)).append("\": \"")
                    .append(escape(value)).append('"')
                sb.append(if (index == config.settings.size - 1) "\n" else ",\n")
            }
            sb.append("  },\n")
        }
        sb.append("  \"groups\": [")
        if (config.groups.isEmpty()) {
            sb.append("],\n")
        } else {
            sb.append('\n')
            config.groups.forEachIndexed { index, group ->
                sb.append("    {\n")
                sb.append("      \"name\": \"").append(escape(group.name)).append("\",\n")
                sb.append("      \"target\": \"").append(escape(group.target)).append("\",\n")
                sb.append("      \"enabled\": ").append(group.isEnabled).append(",\n")
                sb.append("      \"intervalMs\": ").append(group.intervalMs).append(",\n")
                sb.append("      \"switchMode\": \"").append(escape(group.switchMode)).append("\",\n")
                sb.append("      \"activeFromMinute\": ").append(group.activeFromMinute).append(",\n")
                sb.append("      \"activeToMinute\": ").append(group.activeToMinute).append(",\n")
                sb.append("      \"activeDays\": ").append(group.activeDays).append(",\n")
                sb.append("      \"activeThemeMode\": \"")
                    .append(escape(group.activeThemeMode)).append("\",\n")
                sb.append("      \"filterMode\": \"")
                    .append(escape(group.filterMode)).append("\",\n")
                sb.append("      \"sortOrder\": \"")
                    .append(escape(group.sortOrder)).append("\"\n")
                sb.append("    }")
                sb.append(if (index == config.groups.size - 1) "\n" else ",\n")
            }
            sb.append("  ],\n")
        }
        sb.append("  \"sources\": [")
        if (config.sources.isEmpty()) {
            sb.append("]\n")
        } else {
            sb.append('\n')
            config.sources.forEachIndexed { index, source ->
                sb.append("    {\n")
                sb.append("      \"name\": \"").append(escape(source.name)).append("\",\n")
                sb.append("      \"url\": \"").append(escape(source.url)).append("\",\n")
                sb.append("      \"type\": ").append(source.type).append(",\n")
                sb.append("      \"enabled\": ").append(source.enabled).append(",\n")
                sb.append("      \"rawJson\": \"")
                    .append(escape(source.rawJson)).append("\"\n")
                sb.append("    }")
                sb.append(if (index == config.sources.size - 1) "\n" else ",\n")
            }
            sb.append("  ]\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    /** Decode a file written by [encode]; null when it is not our config. */
    fun decode(text: String): Config? {
        val root = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        if (root["format"] != FORMAT) return null
        val version = (root["version"] as? Long)?.toInt() ?: return null
        if (version > VERSION) return null // a newer app wrote it; do not guess
        val settings = (root["settings"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                val value = v as? String ?: return@mapNotNull null
                key to value
            }
            ?.toMap()
            .orEmpty()
        val groups = (root["groups"] as? List<*>)
            ?.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val name = map["name"] as? String ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null
                GroupConfig(
                    name = name.take(120),
                    target = (map["target"] as? String).orEmpty().ifEmpty { "BOTH" },
                    isEnabled = map["enabled"] as? Boolean ?: true,
                    intervalMs = ((map["intervalMs"] as? Long) ?: 0L).coerceAtLeast(0L),
                    switchMode = (map["switchMode"] as? String).orEmpty(),
                    activeFromMinute = ((map["activeFromMinute"] as? Long) ?: -1L).toInt()
                        .coerceIn(-1, 1439),
                    activeToMinute = ((map["activeToMinute"] as? Long) ?: -1L).toInt()
                        .coerceIn(-1, 1439),
                    activeDays = ((map["activeDays"] as? Long) ?: 0L).toInt()
                        .coerceIn(0, 127),
                    activeThemeMode = (map["activeThemeMode"] as? String).orEmpty(),
                    filterMode = (map["filterMode"] as? String).orEmpty(),
                    sortOrder = (map["sortOrder"] as? String).orEmpty()
                )
            }
            .orEmpty()
        val sources = (root["sources"] as? List<*>)
            ?.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val url = (map["url"] as? String)?.trim().orEmpty()
                if (url.isEmpty()) return@mapNotNull null
                SourceConfig(
                    name = (map["name"] as? String).orEmpty().take(120),
                    url = url,
                    type = ((map["type"] as? Long) ?: 0L).toInt(),
                    enabled = map["enabled"] as? Boolean ?: true,
                    rawJson = (map["rawJson"] as? String).orEmpty(),
                )
            }
            .orEmpty()
        return Config(groups = groups, settings = settings, sources = sources)
    }

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    // --- Database side --------------------------------------------------------------

    /** Snapshot the current configuration. */
    suspend fun read(db: AppDatabase): Config {
        // Every group, including disabled ones: they must survive a round trip.
        val allGroups = db.wallpaperGroupDao().getAllGroupsOnce()
        val settings = LinkedHashMap<String, String>()
        for (key in EXPORTED_SETTINGS) {
            val value = try {
                db.settingsDao().getValue(key)
            } catch (_: Exception) {
                null
            }
            if (value != null) settings[key] = value
        }
        return Config(
            groups = allGroups.map {
                GroupConfig(
                    name = it.name,
                    target = it.target,
                    isEnabled = it.isEnabled,
                    intervalMs = it.intervalMs,
                    switchMode = it.switchMode,
                    activeFromMinute = it.activeFromMinute,
                    activeToMinute = it.activeToMinute,
                    activeDays = it.activeDays,
                    activeThemeMode = it.activeThemeMode,
                    filterMode = it.filterMode,
                    sortOrder = it.sortOrder
                )
            },
            settings = settings,
            sources = try {
                db.rssSourceDao().getAll().map {
                    SourceConfig(
                        name = it.name,
                        url = it.url,
                        type = it.type,
                        enabled = it.enabled,
                        rawJson = it.rawJson,
                    )
                }
            } catch (_: Exception) {
                emptyList()
            },
        )
    }

    /**
     * Apply [config]: the settings are written, each exported group becomes a
     * NEW group (media are not part of the file, so merging into an existing
     * group is not possible), and every exported subscription is added or - when
     * a source with the same URL is already there - updated in place
     * (see [RssSourceImport]). Returns how many were written.
     */
    suspend fun apply(db: AppDatabase, config: Config): ApplyResult {
        var failures = 0
        for ((key, value) in config.settings) {
            if (key !in EXPORTED_SETTINGS) continue
            try {
                db.settingsDao().setSetting(com.wallpaperswitcher.data.AppSettings(key, value))
            } catch (_: Exception) {
                failures++
            }
        }
        var created = 0
        for (group in config.groups) {
            try {
                db.wallpaperGroupDao().insert(
                    WallpaperGroup(
                        name = group.name,
                        target = group.target,
                        isEnabled = group.isEnabled,
                        intervalMs = group.intervalMs,
                        switchMode = group.switchMode,
                        activeFromMinute = group.activeFromMinute,
                        activeToMinute = group.activeToMinute,
                        activeDays = group.activeDays,
                        activeThemeMode = group.activeThemeMode,
                        filterMode = group.filterMode,
                        sortOrder = group.sortOrder
                    )
                )
                created++
            } catch (_: Exception) {
                failures++
            }
        }
        var sources = 0
        try {
            val decision = RssSourceImport.decide(
                existing = db.rssSourceDao().getAll(),
                incoming = config.sources.map { source ->
                    val url = source.url.trim()
                    com.wallpaperswitcher.data.RssSource(
                        name = source.name.trim().ifBlank { url },
                        url = url,
                        type = source.type,
                        enabled = source.enabled,
                        rawJson = source.rawJson,
                    )
                },
            )
            for (source in decision.updated) db.rssSourceDao().update(source)
            for (source in decision.inserted) db.rssSourceDao().insert(source)
            sources = decision.inserted.size + decision.updated.size
        } catch (_: Exception) {
            failures++
        }
        if (failures > 0) {
            AppLog.w(TAG, "config import: $failures write(s) failed")
        }
        return ApplyResult(groups = created, sources = sources)
    }
}

/**
 * Minimal JSON reader for the config file (objects, arrays, strings, numbers,
 * booleans, null). Numbers are returned as Long when integral, Double
 * otherwise; the config schema only uses integral numbers and strings.
 */
internal object Json {

    /** Serialize a parsed value back to JSON (used by the Legado import). */
    fun encode(value: Any?): String = buildString { write(value, this) }

    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append('"').append(escape(value)).append('"')
            is Boolean -> sb.append(value)
            is Int -> sb.append(value)
            is Long -> sb.append(value)
            is Double -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(escape(key.toString())).append("\":")
                    write(item, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(item, sb)
                }
                sb.append(']')
            }
            else -> sb.append('"').append(escape(value.toString())).append('"')
        }
    }

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("trailing content at ${parser.position}")
        return value
    }

    private class Parser(private val text: String) {
        private var index = 0

        val position: Int get() = index
        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("unexpected end of input")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw IllegalArgumentException("unexpected '$c' at $index")
                }
            }
        }

        private fun expect(word: String) {
            if (!text.startsWith(word, index)) {
                throw IllegalArgumentException("expected $word at $index")
            }
            index += word.length
        }

        private fun parseObject(): Map<String, Any?> {
            index++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[index] == '}') {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (atEnd() || text[index] != ':') {
                    throw IllegalArgumentException("expected ':' at $index")
                }
                index++
                map[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("unterminated object")
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return map }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index++ // [
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[index] == ']') {
                index++
                return items
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("unterminated array")
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return items }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $index")
                }
            }
        }

        private fun parseString(): String {
            if (atEnd() || text[index] != '"') {
                throw IllegalArgumentException("expected string at $index")
            }
            index++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                when (val c = text[index]) {
                    '"' -> { index++; return sb.toString() }
                    '\\' -> {
                        index++
                        if (atEnd()) throw IllegalArgumentException("unterminated escape")
                        when (val esc = text[index]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 >= text.length) {
                                    throw IllegalArgumentException("bad \\u escape")
                                }
                                val hex = text.substring(index + 1, index + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad \\u escape")
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$esc")
                        }
                        index++
                    }
                    else -> {
                        sb.append(c)
                        index++
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var integral = true
            if (index < text.length && text[index] == '.') {
                integral = false
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                integral = false
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return if (integral) {
                raw.toLongOrNull() ?: raw.toDouble()
            } else {
                raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $raw")
            }
        }
    }
}
