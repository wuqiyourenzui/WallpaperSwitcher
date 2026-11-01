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

    /**
     * Longest a single evaluation may run before it is aborted.
     *
     * 60s rather than 20s: a gallery rule legitimately walks the `_N.html` sibling
     * pages of an article, and with a source-level `concurrentRate` that walk can
     * take tens of seconds (「秀人集」的正文规则实测 ~10 秒/11 页，一个 24 页的
     * 图集就要 40 秒以上)。20 秒会把规则掐死在中途，表现是「只显示第一页的
     * 5 张图」。规则本身跑在后台线程上，这里放宽只影响「死死卡住的规则」多久
     * 被放弃，不影响界面响应。
     */
    var timeoutMs: Long = 60_000L

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
        // 每次求值都从**干净的中断状态**开始。watchdog 用 Thread.interrupt()
        // 打断跑飞的脚本；那次中断若落在阻塞调用里，标记会留在池化线程上，
        // 下一次复用这条线程的 JS 求值就会在 `java.ajax` 的第一个阻塞点被立刻
        // 取消（`StreamResetException: stream was reset: CANCEL`），和当前规则
        // 毫无关系。清掉它，让中断只在**本次**超时时生效。
        clearStrayInterrupt()
        val context = TimedContextFactory.enterContext()
        deadline.set(System.currentTimeMillis() + timeoutMs)
        context.setInstructionObserverThreshold(INSTRUCTION_CHECK)
        return context
    }

    /**
     * 把 [ms] 毫秒**还回**当前线程的 JS 超时预算。
     *
     * 这个超时是防"规则里死循环"的（Rhino 不看线程中断）。但 `java.ajax` 这种
     * **阻塞式网络调用**也被算进了同一份预算：源限流（`concurrentRate: 1/1200`）
     * 加站点慢响应，几个请求就能把 60 秒耗光，于是 watchdog 中断 JS 线程 ——
     * 正在阻塞的 OkHttp 调用被打断、HTTP/2 流被 CANCEL，日志里就是
     * `StreamResetException: stream was reset: CANCEL`，正文永远抓不到。
     *
     * 网络等待不是"规则跑飞"，所以调用方（[LegadoJsHelpers.ajax]）在每次阻塞
     * 调用后把实际耗时还回来，让预算只衡量**脚本自己的执行时间**。
     */
    fun refundTimeout(ms: Long) {
        if (ms <= 0L) return
        val current = deadline.get() ?: return
        deadline.set(current + ms)
    }

    /**
     * 清掉线程上遗留的中断标记。
     *
     * watchdog 用 `Thread.interrupt()` 打断跑飞的脚本；如果那次中断落在一个
     * 阻塞调用（`Thread.sleep` / OkHttp）里，标记会留在**池化线程**上，之后复用
     * 这条线程的抓取会被立刻中断 —— 表现为与本次规则无关的随机 CANCEL。
     */
    fun clearStrayInterrupt() {
        try {
            Thread.interrupted()
        } catch (_: Throwable) {
        }
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
