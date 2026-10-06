package com.wallpaperswitcher.engine.legado

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory

/**
 * The Rhino runtime every 阅读-style JS rule runs in.
 *
 * Rhino's interpreter never checks thread interrupts, so a single bad rule
 * (`while(true){}`, a retry loop that never exits) owned its calling thread
 * forever: the refresh that ran it never returned, and cancelling the coroutine
 * changed nothing. Rhino exposes an instruction observer for exactly this case,
 * so every evaluation is now given a deadline and aborted when it passes.
 *
 * All evaluation goes through a Context produced by [TimedContextFactory], which
 * is installed as the GLOBAL factory so that a bare `Context.enter()` (still
 * used by the source-script path) is timed as well.
 */
internal object LegadoJsRuntime {

    /** Longest a single evaluation may run before it is aborted. */
    var timeoutMs: Long = 20_000L

    /** Instructions between two deadline checks (interpreted mode). */
    private const val INSTRUCTION_CHECK = 10_000

    /** Aborted evaluation; the message survives [LegadoJs.cleanError]. */
    class JsTimeoutError :
        Error("js evaluation timed out (${LegadoJsRuntime.timeoutMs}ms)")

    /**
     * Aborts an evaluation that runs past its deadline.
     *
     * The deadline travels in a thread-local of THIS class rather than in a
     * `Context.putThreadLocal`: a Context thread-local is only visible on the
     * thread that put it, and `exec`/`evaluateString` can run on another one.
     */
    private object TimedContextFactory : ContextFactory() {
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val at = deadline.get()
            if (at != null && System.currentTimeMillis() > at) throw JsTimeoutError()
        }
    }

    /** Wall-clock deadline of the evaluation on THIS thread. */
    private val deadline = ThreadLocal<Long>()

    init {
        // Idempotent-ish: ContextFactory.initGlobal throws when a global factory
        // was already installed, which is exactly the "someone ran first" case we
        // want to ignore rather than crash the process at startup.
        try {
            ContextFactory.initGlobal(TimedContextFactory)
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * A Rhino [Context] with the deadline armed for the calling thread. The
     * caller MUST pair it with `Context.exit()` in a finally block, exactly like
     * `Context.enter()`.
     *
     * The context is created through [TimedContextFactory] directly rather than
     * through `Context.enter()`, so the observer is active even when another
     * factory is already installed as the global one.
     */
    fun enter(): Context {
        val context = TimedContextFactory.enterContext()
        deadline.set(System.currentTimeMillis() + timeoutMs)
        context.setInstructionObserverThreshold(INSTRUCTION_CHECK)
        return context
    }

    /** True when [t] is (or wraps) a [JsTimeoutError]. */
    fun isTimeout(t: Throwable): Boolean {
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is JsTimeoutError) return true
            cause = cause.cause
            depth++
        }
        return false
    }
}
