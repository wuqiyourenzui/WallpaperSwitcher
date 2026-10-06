package com.wallpaperswitcher.wallpaper

import android.os.SystemClock
import android.view.MotionEvent
import com.wallpaperswitcher.engine.DoubleTapDetector
import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 壁纸窗口的触摸/双击处理，从 `LiveWallpaperService` 拆分出来（逻辑逐字搬移）。
 *
 * The engine's `onTouchEvent` forwards here and then calls `super`; the controller
 * only owns the pure double-tap state machine and the throttled touch logging.
 */
internal class TouchGestureController(
    private val tag: String,
    private val scope: CoroutineScope,
    density: Float,
    private val host: Host,
) {

    internal interface Host {
        /** 双击开关（读取失败时按开启处理，见 [onDoubleTap]）。 */
        suspend fun doubleTapEnabled(): Boolean

        /** 触发一次用户双击切换。 */
        fun requestSwitch()
    }

    // Pure double-tap state machine (see engine.DoubleTapDetector).
    // GestureDetector only reports a double tap on the second ACTION_DOWN
    // and silently fails when the launcher / system delivers an incomplete
    // event stream (Android 16/17 devices and some OEM launchers consume or
    // drop one of the two taps). Tracking both DOWN and UP pairs lets the
    // engine recognize a double tap from whatever subset of events actually
    // reaches the wallpaper window.
    private val detector = DoubleTapDetector(DOUBLE_TAP_TIMEOUT_MS, density * DOUBLE_TAP_SLOP_DP)
    private var lastTouchLogAt = 0L

    /**
     * Robust double-tap detection that fires from either the second
     * ACTION_DOWN (the classic case) or the second ACTION_UP (fallback for
     * launchers that swallow one of the DOWN events).
     *
     * Logging: a recognized double tap is always logged. Plain DOWN/UP is
     * throttled (see [TOUCH_LOG_INTERVAL_MS]) - it is still enough to prove
     * from a captured log whether the launcher delivers touches to the
     * wallpaper window at all, without writing two lines per tap (the
     * swipe detector that needed the full stream was removed).
     */
    fun handle(event: MotionEvent) {
        val action = event.actionMasked
        val x = event.x
        val y = event.y
        val now = SystemClock.uptimeMillis()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (detector.onDown(x, y, now)) {
                    AppLog.d(tag, "Touch DOWN ($x, $y): double-tap from DOWN")
                    onDoubleTap()
                }
                logTouchThrottled("Touch DOWN ($x, $y)")
            }
            MotionEvent.ACTION_UP -> {
                if (detector.onUp(x, y, now)) {
                    // The launcher consumed one of the DOWN events but
                    // still forwarded both UPs: count it as a double tap.
                    AppLog.d(tag, "Touch UP ($x, $y): double-tap from UP fallback")
                    onDoubleTap()
                } else {
                    logTouchThrottled("Touch UP ($x, $y)")
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                detector.onCancel()
                AppLog.d(tag, "Touch CANCEL")
            }
        }
    }

    /**
     * Throttled "the wallpaper window really receives touches" line. Kept so
     * a captured log can still answer "does this launcher forward touches?",
     * without one line per finger movement.
     */
    private fun logTouchThrottled(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTouchLogAt < TOUCH_LOG_INTERVAL_MS) return
        lastTouchLogAt = now
        AppLog.d(tag, message)
    }

    private fun onDoubleTap() {
        // A double tap is an explicit user action: switch even if reading
        // the setting fails.
        scope.launch {
            try {
                if (host.doubleTapEnabled()) {
                    host.requestSwitch()
                }
            } catch (_: Exception) {
                host.requestSwitch()
            }
        }
    }

    private companion object {
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val DOUBLE_TAP_SLOP_DP = 40f
        /**
         * Throttle for the plain "touch received" line (see [logTouchThrottled]):
         * enough to prove the stream exists without one line per tap.
         */
        private const val TOUCH_LOG_INTERVAL_MS = 30_000L
    }
}
