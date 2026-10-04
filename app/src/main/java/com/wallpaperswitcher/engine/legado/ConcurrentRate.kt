package com.wallpaperswitcher.engine.legado

import kotlinx.coroutines.delay

/**
 * 阅读 的 `concurrentRate`（源级访问限流）：
 *
 * - 纯数字：两次请求之间的最小间隔（毫秒），同一时刻只允许一个请求在跑；
 * - `次数/毫秒`：一段时间窗内最多允许这么多次请求。
 *
 * 语义与阅读 `ConcurrentRateLimiter` 一致：等够了再发，不报错；按源
 * （sourceId）隔离计数。`null` / `""` / `0` 表示不限流。
 */
internal object ConcurrentRate {

    /** 单请求模式记「上一个请求的开始时间 + 在跑数量」；频率模式记时间窗。 */
    internal class Window {
        var start: Long = 0L
        var inWindow: Int = 0
        var inFlight: Int = 0
    }

    /** [perWindow] 为 null 表示「单请求 + 最小间隔」模式。 */
    internal data class Rate(val perWindow: Int?, val windowMs: Long)

    internal fun parse(raw: String?): Rate? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "0") return null
        val slash = text.indexOf('/')
        return try {
            if (slash > 0) {
                val count = text.take(slash).trim().toInt()
                val window = text.substring(slash + 1).trim().toLong()
                if (count <= 0 || window <= 0) null else Rate(count, window)
            } else {
                val interval = text.toLong()
                if (interval <= 0) null else Rate(null, interval)
            }
        } catch (_: Exception) {
            // 认不出来的写法按「不限流」处理，不影响抓取。
            null
        }
    }

    /**
     * 阅读 `fetchStart` 的判定：返回 0 表示放行（并已占用一次），
     * 返回正数表示要等这么多毫秒再试。
     */
    internal fun plan(rate: Rate, window: Window, now: Long): Long = when (rate.perWindow) {
        null -> {
            if (window.inFlight > 0) {
                rate.windowMs
            } else {
                val nextTime = window.start + rate.windowMs
                if (now >= nextTime) {
                    window.start = now
                    window.inFlight = 1
                    0L
                } else {
                    nextTime - now
                }
            }
        }
        else -> {
            val nextTime = window.start + rate.windowMs
            if (now >= nextTime) {
                window.start = now
                window.inWindow = 1
                0L
            } else if (window.inWindow >= rate.perWindow) {
                nextTime - now
            } else {
                window.inWindow += 1
                0L
            }
        }
    }

    /** 释放一次占用（只有单请求模式需要）。 */
    internal fun release(rate: Rate, window: Window) {
        if (rate.perWindow == null && window.inFlight > 0) window.inFlight -= 1
    }

    private class Entry(val rate: Rate, val window: Window)

    private val lock = Any()
    private val rates = HashMap<Long, String>()
    private val entries = HashMap<Long, Entry>()

    /** 源规则解析时登记；`rate` 变化会重置该源的窗口。 */
    fun register(sourceId: Long, rate: String?) {
        if (sourceId <= 0L) return
        synchronized(lock) {
            if (rates[sourceId] == rate) return
            rates[sourceId] = rate.orEmpty()
            entries.remove(sourceId)
        }
    }

    private fun entryOf(sourceId: Long): Entry? {
        if (sourceId <= 0L) return null
        return synchronized(lock) {
            val raw = rates[sourceId] ?: return@synchronized null
            val rate = parse(raw) ?: return@synchronized null
            entries[sourceId]?.takeIf { it.rate == rate }
                ?: Entry(rate, Window()).also { entries[sourceId] = it }
        }
    }

    private fun nextWait(entry: Entry): Long = synchronized(lock) {
        plan(entry.rate, entry.window, System.currentTimeMillis())
    }

    private fun finish(entry: Entry) = synchronized(lock) {
        release(entry.rate, entry.window)
    }

    /** 限流后的请求入口（挂起版，用于 OkHttp 的协程调用）。 */
    suspend fun <T> withLimit(sourceId: Long, block: suspend () -> T): T {
        val entry = entryOf(sourceId) ?: return block()
        while (true) {
            val wait = nextWait(entry)
            if (wait <= 0L) break
            delay(wait)
        }
        return try {
            block()
        } finally {
            finish(entry)
        }
    }

    /** 限流后的请求入口（阻塞版，用于 JS 里的 `java.get/post/ajax`）。 */
    fun <T> withLimitBlocking(sourceId: Long, block: () -> T): T {
        val entry = entryOf(sourceId) ?: return block()
        while (true) {
            val wait = nextWait(entry)
            if (wait <= 0L) break
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return try {
            block()
        } finally {
            finish(entry)
        }
    }
}
