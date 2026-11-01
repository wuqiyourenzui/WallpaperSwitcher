package com.wallpaperswitcher.engine.legado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `java.ajax` 的域名级熔断：连续失败到阈值才冷却，一次成功就清零。 */
class AjaxCircuitTest {

    private val host = "3w.8012359.xyz"

    @Before
    fun setUp() {
        AjaxCircuit.reset()
    }

    @Test
    fun `stays closed below the threshold`() {
        val now = 1_000_000L
        for (i in 1 until AjaxCircuit.THRESHOLD) {
            assertNull(AjaxCircuit.onFailure(host, now))
            assertFalse(AjaxCircuit.isOpen(host, now))
        }
    }

    @Test
    fun `opens after consecutive failures and blocks during cooldown`() {
        val now = 1_000_000L
        var opened: Int? = null
        repeat(AjaxCircuit.THRESHOLD) { opened = AjaxCircuit.onFailure(host, now) ?: opened }
        assertEquals(AjaxCircuit.THRESHOLD, opened)
        assertTrue(AjaxCircuit.isOpen(host, now))
        assertTrue(AjaxCircuit.isOpen(host, now + AjaxCircuit.COOLDOWN_MS - 1))
        // 冷却结束自动放行，不需要外部清理。
        assertFalse(AjaxCircuit.isOpen(host, now + AjaxCircuit.COOLDOWN_MS))
    }

    @Test
    fun `one success clears the failure count`() {
        val now = 1_000_000L
        repeat(AjaxCircuit.THRESHOLD - 1) { AjaxCircuit.onFailure(host, now) }
        AjaxCircuit.onSuccess(host)
        // 清零后要重新累积，不能因为历史上的失败就熔断。
        for (i in 1 until AjaxCircuit.THRESHOLD) {
            assertNull(AjaxCircuit.onFailure(host, now))
        }
        assertFalse(AjaxCircuit.isOpen(host, now))
    }

    @Test
    fun `hosts are counted independently`() {
        val now = 1_000_000L
        repeat(AjaxCircuit.THRESHOLD) { AjaxCircuit.onFailure(host, now) }
        assertTrue(AjaxCircuit.isOpen(host, now))
        assertFalse(AjaxCircuit.isOpen("other.example", now))
        assertFalse(AjaxCircuit.isOpen("", now))
        assertNull(AjaxCircuit.onFailure("", now))
    }

    /**
     * 只有传输层失败才计数：图集规则会故意往后多探几页，站点对超出末尾的
     * `_N.html` 回 502 —— 那是规则的一部分，计入熔断会把正常图集掐掉
     * （实测表现就是「只显示 5 张图」）。
     */
    @Test
    fun `http status codes are not transport failures`() {
        assertFalse(
            AjaxCircuit.isTransportFailure(
                com.wallpaperswitcher.engine.FetchException("server", 502)
            )
        )
        assertFalse(
            AjaxCircuit.isTransportFailure(
                com.wallpaperswitcher.engine.FetchException("parse", 200)
            )
        )
        // 连不上 / 超时：httpStatus 为 0，要计数。
        assertTrue(
            AjaxCircuit.isTransportFailure(
                com.wallpaperswitcher.engine.FetchException("network")
            )
        )
        assertTrue(AjaxCircuit.isTransportFailure(java.net.SocketTimeoutException("timeout")))
        assertTrue(AjaxCircuit.isTransportFailure(java.net.ConnectException("refused")))
    }

    /**
     * 同一个地址问过太多次就放弃 —— 规则可能对取不到的地址死循环重试
     * （「秀人集」的 ruleNextPage → `hot_1.html` 每 0.5 秒一次，把首屏拖到 20 秒+）。
     */
    @Test
    fun `the same url stops being requested after the limit`() {
        val url = "http://3w.8012359.xyz/hot_1.html"
        assertFalse(AjaxCircuit.isUrlExhausted(url))
        repeat(AjaxCircuit.SAME_URL_LIMIT) {
            assertFalse(AjaxCircuit.isUrlExhausted(url))
            AjaxCircuit.onUrlAttempt(url)
        }
        assertTrue(AjaxCircuit.isUrlExhausted(url))

        // 别的地址不受影响：图集规则要对不同的 `_N.html` 逐个探测。
        assertFalse(AjaxCircuit.isUrlExhausted("http://3w.8012359.xyz/hot_2.html"))

        // 真的取到东西后，这个地址重新可用。
        AjaxCircuit.onUrlSuccess(url)
        assertFalse(AjaxCircuit.isUrlExhausted(url))
    }
}
