package com.wallpaperswitcher.engine.legado

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A JS rule that never returns must be ABORTED, not allowed to own the calling
 * thread: Rhino's interpreter ignores thread interrupts, so before the
 * instruction-observer deadline a single `while(true){}` in an imported source
 * froze the whole refresh (and the live-wallpaper process with it).
 */
class LegadoJsTimeoutTest {

    private val originalTimeout = LegadoJsRuntime.timeoutMs

    @After
    fun restoreTimeout() {
        LegadoJsRuntime.timeoutMs = originalTimeout
    }

    @Test
    fun aRunawayRuleIsAbortedAndReportsATimeout() {
        LegadoJsRuntime.timeoutMs = 300L
        // Guarded: if the abort ever regresses, this test fails instead of
        // hanging the whole suite (the runaway thread is a daemon).
        var value: String? = null
        var error: String? = null
        var elapsed = -1L
        val worker = Thread {
            val startedAt = System.currentTimeMillis()
            val result = LegadoJs.run("while(true){}", emptyMap(), "https://example.invalid")
            elapsed = System.currentTimeMillis() - startedAt
            value = result.value
            error = result.error
        }
        worker.isDaemon = true
        worker.start()
        worker.join(15_000L)

        assertFalse("the rule never returned - the abort regressed", worker.isAlive)
        assertNull("a timed-out rule must not produce a value", value)
        assertNotNull("the reason must be reported, not swallowed", error)
        assertTrue(
            "the abort must mention the timeout, was: $error",
            error!!.contains("timed out")
        )
        // Generous bound: the point is that it returns at all, and roughly on the
        // deadline instead of never.
        assertTrue("took ${elapsed}ms, expected the 300ms deadline", elapsed < 10_000L)
    }

    @Test
    fun aNormalRuleStillEvaluates() {
        val result = LegadoJs.run("1 + 1", emptyMap(), "https://example.invalid")
        assertNull(result.error)
        assertTrue("expected 2, was ${result.value}", result.value == "2")
    }

    @Test
    fun theDeadlineIsPerEvaluationNotPerThread() {
        LegadoJsRuntime.timeoutMs = 500L
        // Two sequential evaluations on the same test thread: the second one must
        // get its own fresh deadline (a thread-local that was never re-armed would
        // make every later rule fail instantly).
        assertNull(LegadoJs.run("var a = 0; for (var i = 0; i < 1000; i++) a += i; a", emptyMap(), "x").error)
        assertNull(LegadoJs.run("2 * 3", emptyMap(), "x").error)
    }
}
