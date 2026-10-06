package com.wallpaperswitcher.wallpaper

import android.os.SystemClock
import com.wallpaperswitcher.util.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 切换请求队列：单消费者串行执行、自动请求合并、用户点击的小队列，从
 * `LiveWallpaperService` 拆分出来（逻辑逐字搬移）。真正的切换执行（选片、
 * 分组解析、预取）仍由引擎通过 [Host] 回调提供，因此这里只拥有并发敏感的
 * 队列状态。
 */
internal class SwitchQueue(
    private val scope: CoroutineScope,
    private val tag: String,
    private val host: Host,
) {

    internal interface Host {
        fun isUserTapSource(source: String): Boolean
        fun isSwitchInProgress(): Boolean
        fun switchStartedAt(): Long
        fun markSwitchStarted()
        fun markSwitchDone()
        fun lastSwitchCompletedAt(): Long
        fun markSwitchCompleted()
        suspend fun resolveUserTapGroup(req: SwitchRequest): Long
        suspend fun execute(req: SwitchRequest, effectiveGroupId: Long)
        suspend fun afterSwitch(
            req: SwitchRequest,
            sincePreviousSwitchMs: Long,
            effectiveGroupId: Long,
        )
    }

    private val switchChannel = Channel<SwitchRequest>(8)
    private val consumerStarted = AtomicBoolean(false)
    private val pendingAutoSwitch = AtomicBoolean(false)
    private val pendingUserSwitches = AtomicInteger(0)

    fun request(source: String, targetId: Long? = null, groupId: Long = 0L) {
        ensureConsumer()
        // If the current switch looks stuck (>30s), do NOT spawn a second
        // consumer: two consumers would pull from the channel concurrently
        // and run executeSwitch twice in parallel, which is exactly the
        // concurrent startVideo/stopVideoAndRender race that crashes the
        // GL renderer. Every blocking step inside a switch is time-bounded
        // (bitmap 15s, video open 15s), so the original consumer always
        // finishes eventually and processes this queued request.
        if (host.isSwitchInProgress() && host.switchStartedAt() != 0L &&
            SystemClock.elapsedRealtime() - host.switchStartedAt() > 30_000L
        ) {
            AppLog.w(tag, "Switch appears stuck >30s; queued requests will run when it finishes")
        }
        AppLog.d(tag, "Switch requested: $source target=$targetId group=$groupId")
        val scopedGroupId = if (targetId != null) 0L else groupId.coerceAtLeast(0L)
        if (targetId != null) {
            // Target switches (manual selection) always queue.
            scope.launch {
                switchChannel.send(SwitchRequest(source, targetId))
            }
            return
        }
        // A per-group tick must never be folded into (or fold) another
        // switch: it carries the group it belongs to, and a coalesced
        // request would lose that scope.
        if (scopedGroupId > 0L) {
            scope.launch {
                switchChannel.send(SwitchRequest(source, null, scopedGroupId))
            }
            return
        }
        // 用户手动点击：每一次点击都要真的换一张。以前所有非定时请求共用
        // 一个"已排队"标志，连点时的第三下起会被静默合并掉——用户看到的
        // 就是"点了没反应"。这里给手动点击一个很小的队列（含正在执行的那
        // 一次最多 MAX_PENDING_USER_SWITCHES 次），配合"手动切换后总是预
        // 解码下一张"，每一跳都只需一次纹理上传（约 20ms）。
        if (host.isUserTapSource(source)) {
            if (pendingUserSwitches.incrementAndGet() > MAX_PENDING_USER_SWITCHES) {
                pendingUserSwitches.decrementAndGet()
                AppLog.d(
                    tag,
                    "User tap folded: $pendingUserSwitches already queued ($source)"
                )
                return
            }
            AppLog.d(tag, "User tap queued ($source), in flight=$pendingUserSwitches")
            scope.launch {
                switchChannel.send(SwitchRequest(source, null))
            }
            return
        }
        // Coalesce non-target switches: while one is already queued (or
        // being processed), fold new triggers into it. Rapid double-taps
        // then cause ONE switch instead of N queued requests that each run
        // their own screen-size decode — which made rapid tapping stutter
        // (every queued switch blocked on a fresh decode and the queue
        // drained slowly). The consumer resets the flag when it pulls a
        // request, so a trigger during a switch still queues the next one.
        if (!pendingAutoSwitch.compareAndSet(false, true)) {
            AppLog.d(tag, "Switch coalesced into pending request ($source)")
            return
        }
        // Timer ticks are repetitive: when the queue is full (the engine is
        // still draining a backlog), drop the tick instead of accumulating
        // suspended senders — the next tick arrives within one interval
        // anyway. User triggers (double-tap / unlock / recovery) use the
        // guaranteed send below.
        if (source == LiveWallpaperService.SOURCE_TIMER) {
            if (switchChannel.trySend(SwitchRequest(source, null)).isSuccess) return
            AppLog.d(tag, "Switch queue full, dropping $source tick")
            pendingAutoSwitch.set(false)
            return
        }
        // Guaranteed enqueue: send suspends until the queue has space, so a
        // trigger can never be silently dropped when the queue is full.
        scope.launch {
            switchChannel.send(SwitchRequest(source, null))
        }
    }

    fun ensureConsumer() {
        if (consumerStarted.compareAndSet(false, true)) {
            scope.launch {
                try {
                    consumeSwitches()
                } finally {
                    // Allow the consumer to be restarted if it ever dies.
                    consumerStarted.set(false)
                    // Any pending auto-request marker is stale now; a fresh
                    // trigger must be allowed to queue.
                    pendingAutoSwitch.set(false)
                    // Same for the user-tap budget: a consumer that died
                    // mid-request must not leave taps permanently folded.
                    pendingUserSwitches.set(0)
                }
            }
        }
    }

    /** Engine teardown: drop the consumer/queued markers (the scope is cancelled next). */
    fun reset() {
        consumerStarted.set(false)
        pendingAutoSwitch.set(false)
        pendingUserSwitches.set(0)
    }

    private suspend fun consumeSwitches() {
        for (req in switchChannel) {
            // This auto request was pulled from the queue: allow a new
            // non-target switch to queue while this one executes (the
            // rapid-tap coalescing window).
            if (req.targetId == null) pendingAutoSwitch.set(false)
            host.markSwitchStarted()
            try {
                AppLog.d(tag, "Switch start: ${req.source}")
                // Gap to the PREVIOUS switch: the prefetch rule uses it to
                // tell "user is tapping rapidly" from "a one-off switch".
                // 0 = no previous switch in this engine yet (a fresh engine
                // used to report the whole elapsedRealtime uptime here, e.g.
                // "Not a rapid switch (271624535ms)").
                val sincePreviousSwitchMs = if (host.lastSwitchCompletedAt() == 0L) {
                    0L
                } else {
                    SystemClock.elapsedRealtime() - host.lastSwitchCompletedAt()
                }
                // 悬浮按钮 / 双击 / 立即切换 reach the engine directly without
                // a group scope (the timer always carries one). Resolve the
                // group the screen rhythm would switch next, so a group's
                // 仅图片 / 仅视频 filter and its own cursor apply to the tap
                // too - otherwise the tap fell back to the screen-wide pool
                // and could show a video for an 仅图片 group.
                val effectiveGroupId = host.resolveUserTapGroup(req)
                host.execute(req, effectiveGroupId)
                host.markSwitchCompleted()
                // The prefetch follows the same scope as the switch that just
                // ran: group ticks cache the group's next image, screen-wide
                // switches cache the screen's next one (the cache records
                // which group it belongs to).
                host.afterSwitch(req, sincePreviousSwitchMs, effectiveGroupId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Never let one bad switch kill the queue consumer.
                AppLog.e(tag, "Switch failed: ${req.source}", t)
            } finally {
                host.markSwitchDone()
                AppLog.d(tag, "Switch done: ${req.source}")
                // Release the tap slot only for requests that claimed one
                // (see the isUserTapSource branch in request()).
                if (req.targetId == null && host.isUserTapSource(req.source)) {
                    pendingUserSwitches.decrementAndGet()
                }
            }
        }
    }

    private companion object {
        /**
         * How many USER taps may be in flight at once (the one being executed
         * plus the queued ones). Every tap must be felt; the cap only exists
         * so a burst cannot build a long tail of switches after the user has
         * stopped tapping.
         */
        private const val MAX_PENDING_USER_SWITCHES = 3
    }
}
