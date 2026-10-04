package com.wallpaperswitcher.engine.legado

import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读's runtime variables per source (`source.getVariable()` /
 * `source.setVariable(v)` and the keyed `source.put/get`).
 *
 * Legado keeps them in an in-memory cache for the process lifetime; this does
 * the same, keyed by the local source id.
 */
internal object RssSourceVariables {

    private val values = ConcurrentHashMap<Long, String>()
    private val maps = ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>()

    fun get(sourceId: Long): String = values[sourceId].orEmpty()

    fun set(sourceId: Long, value: String?) {
        if (sourceId <= 0L) return
        if (value == null) values.remove(sourceId) else values[sourceId] = value
    }

    fun put(sourceId: Long, key: String, value: String): String {
        if (sourceId > 0L && key.isNotEmpty()) {
            maps.getOrPut(sourceId) { ConcurrentHashMap() }[key] = value
        }
        return value
    }

    fun get(sourceId: Long, key: String): String =
        maps[sourceId]?.get(key).orEmpty()
}
