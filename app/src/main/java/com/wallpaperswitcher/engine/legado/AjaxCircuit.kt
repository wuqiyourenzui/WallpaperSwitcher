package com.wallpaperswitcher.engine.legado

/**
 * `java.ajax` 的域名级熔断 —— **只针对传输层失败**。
 *
 * 有些源的 JS 规则会在取不到地址时死循环重试（外层 30 次翻页 + 内层 2 次重试），
 * 域名真连不上时一次刷新就能打几百个请求、单次列表刷新从 0.5 秒拖到 100 秒，
 * 界面一直转圈。
 *
 * 注意**不能**把 HTTP 状态码算进来：图集规则会故意往后多探几页，站点对
 * 「超出图集末尾」的 `_N.html` 一律回 502（实测「秀人集」的图集就是这样结束的），
 * 把 502 计入熔断会把正常图集一起掐掉，表现成「只显示 5 张图」。
 * 因此只有连接失败 / 超时 / 重置这类**传输层**问题才计数。
 */
internal object AjaxCircuit {

    /** 连续失败到这个次数就冷却。 */
    const val THRESHOLD = 8

    /** 冷却时长。 */
    const val COOLDOWN_MS = 60_000L

    /**
     * 同一个地址最多真发几次请求（含重试）。超出后直接返回空。
     *
     * 有的规则会在 JS 里死循环重试同一个取不到的地址：实测「秀人集」的
     * `ruleNextPage` 把列表地址拼成 `hot_1.html`（站点回 502），一次刷新从头
     * 到尾每 0.5 秒打一次、界面一直转圈 —— 而站点回 502 属于「HTTP 状态码」，
     * 不计入域名熔断（图集规则要靠 502 判断「翻到头了」），所以必须按地址封顶。
     */
    const val SAME_URL_LIMIT = 3

    /** 这次异常算不算「传输层失败」（HTTP 状态码不算）。 */
    fun isTransportFailure(t: Throwable): Boolean {
        if (t is com.wallpaperswitcher.engine.FetchException) {
            return t.httpStatus <= 0
        }
        return t is java.io.IOException
    }

    private class State {
        var failures = 0
        var openUntil = 0L
    }

    /** 每个地址的尝试次数（成功即清零）。 */
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /**
     * 该地址是否已经问过太多次。返回 true 时调用方不要再发请求（直接当取不到）。
     */
    fun isUrlExhausted(url: String): Boolean {
        val key = key(url) ?: return false
        val count = attempts[key]?.get() ?: return false
        return count >= SAME_URL_LIMIT
    }

    /** 记一次尝试（无论成败）。 */
    fun onUrlAttempt(url: String) {
        val key = key(url) ?: return
        attempts.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        if (attempts.size > MAX_TRACKED_URLS) attempts.clear()
    }

    /** 这个地址取到了东西，忘掉它的失败次数。 */
    fun onUrlSuccess(url: String) {
        val key = key(url) ?: return
        attempts.remove(key)
    }

    private fun key(url: String): String? =
        url.trim().takeIf { it.isNotEmpty() }

    private const val MAX_TRACKED_URLS = 2048

    private val states = java.util.concurrent.ConcurrentHashMap<String, State>()

    /** 冷却中（含刚熔断）时返回 true，调用方直接放弃这次 java.ajax。 */
    fun isOpen(host: String, now: Long = System.currentTimeMillis()): Boolean {
        if (host.isEmpty()) return false
        val state = states[host] ?: return false
        return state.openUntil > now
    }

    /** 记录一次失败；返回本次触发的失败次数（没触发熔断则为 null）。 */
    fun onFailure(host: String, now: Long = System.currentTimeMillis()): Int? {
        if (host.isEmpty()) return null
        val state = states.computeIfAbsent(host) { State() }
        synchronized(state) {
            if (state.openUntil > now) return null
            state.failures++
            if (state.failures < THRESHOLD) return null
            state.openUntil = now + COOLDOWN_MS
            state.failures = 0
            return THRESHOLD
        }
    }

    /** 一次成功就清零：这个域名现在能用。 */
    fun onSuccess(host: String) {
        if (host.isEmpty()) return
        states[host]?.let { state ->
            synchronized(state) {
                state.failures = 0
                state.openUntil = 0L
            }
        }
    }

    /** 测试用：清空所有状态。 */
    fun reset() {
        states.clear()
        attempts.clear()
    }
}
