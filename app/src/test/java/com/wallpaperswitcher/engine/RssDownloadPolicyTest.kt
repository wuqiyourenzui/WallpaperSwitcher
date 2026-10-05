package com.wallpaperswitcher.engine

import com.wallpaperswitcher.engine.RssDownloadPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 订阅下载策略 decision table: 仅 Wi-Fi blocks metered networks, the daily cap
 * blocks once reached, and both defaults (unlimited / Wi-Fi allowed) stay open.
 */
class RssDownloadPolicyTest {

    private fun decide(
        wifiOnly: Boolean = false,
        metered: Boolean = false,
        usedBytes: Long = 0L,
        limitBytes: Long = 0L,
    ) = RssDownloadPolicy.decide(wifiOnly, metered, usedBytes, limitBytes)

    @Test
    fun `wifi only blocks a metered network`() {
        val decision = decide(wifiOnly = true, metered = true)
        assertEquals(false, decision.allowed)
        assertEquals(Reason.WIFI_ONLY, decision.reason)
    }

    @Test
    fun `wifi only allows an unmetered network`() {
        assertTrue(decide(wifiOnly = true, metered = false).allowed)
    }

    @Test
    fun `metered network is fine while wifi only is off`() {
        assertTrue(decide(wifiOnly = false, metered = true).allowed)
    }

    @Test
    fun `daily cap blocks once reached`() {
        val decision = decide(usedBytes = 100L, limitBytes = 100L)
        assertEquals(false, decision.allowed)
        assertEquals(Reason.DAILY_LIMIT, decision.reason)
    }

    @Test
    fun `daily cap allows below the limit`() {
        assertTrue(decide(usedBytes = 99L, limitBytes = 100L).allowed)
    }

    @Test
    fun `a zero cap means unlimited`() {
        assertTrue(decide(usedBytes = 10_000L, limitBytes = 0L).allowed)
    }

    @Test
    fun `wifi block wins over the cap so the message names the network`() {
        val decision = decide(
            wifiOnly = true,
            metered = true,
            usedBytes = 100L,
            limitBytes = 100L,
        )
        assertEquals(Reason.WIFI_ONLY, decision.reason)
    }
}
