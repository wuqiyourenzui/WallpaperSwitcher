package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlin.math.hypot

/**
 * Small draggable floating button that switches the wallpaper on double-tap.
 *
 * The live wallpaper engine normally receives home-screen touches itself, but
 * some Android 16/17 launchers no longer forward them to the wallpaper window,
 * which makes engine-side double-tap detection impossible. This button is a
 * launcher-independent fallback: it is a real window above every app, so the
 * double-tap always reaches it. Drag the button to move it anywhere.
 */
class FloatingSwitchButton(private val context: Context) {

    companion object {
        private const val TAG = "FloatingSwitchButton"
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        // Debounce after a detected double-tap so a sloppy triple tap does not
        // enqueue two back-to-back switches.
        private const val POST_SWITCH_DEBOUNCE_MS = 400L
        // Touch target (48dp = Android's minimum comfortable target), while the
        // visible circle is smaller and sits inside it.
        private const val BUTTON_SIZE_DP = 48
        private const val VISUAL_SIZE_DP = 40
        private const val DRAG_SLOP_PX = 8f
        // Fully transparent at rest so the button never obstructs the
        // wallpaper. A soft circle + haptic appears only while the finger is
        // down, so the invisible hotspot still gives feedback when hit.
        private const val CIRCLE_COLOR = 0x001E88E5.toInt()
        private const val TEXT_COLOR = 0x00FFFFFF.toInt()
        private const val FEEDBACK_CIRCLE_ALPHA = 96
        private const val FEEDBACK_TEXT_COLOR = 0xE6FFFFFF.toInt()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var button: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var circleView: TextView? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastSwitchAt = 0L
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    val isShowing: Boolean get() = button != null

    fun show() {
        if (button != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted, button hidden")
            return
        }
        val density = context.resources.displayMetrics.density
        val sizePx = (BUTTON_SIZE_DP * density).toInt()
        val visualPx = (VISUAL_SIZE_DP * density).toInt()
        val marginPx = ((BUTTON_SIZE_DP - VISUAL_SIZE_DP) / 2 * density).toInt()
        val circle = TextView(context).apply {
            text = "双"
            textSize = 12f
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(CIRCLE_COLOR)
            }
        }
        circleView = circle
        val view = FrameLayout(context).apply {
            addView(
                circle,
                FrameLayout.LayoutParams(visualPx, visualPx).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
            )
        }
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - sizePx - (24 * density).toInt()
            y = (220 * density).toInt()
        }
        view.setOnTouchListener { v, event -> handleTouch(v, event) }
        try {
            windowManager.addView(view, lp)
            button = view
            layoutParams = lp
            Log.d(TAG, "Floating button shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating button", e)
        }
    }

    fun dismiss() {
        val v = button ?: return
        button = null
        layoutParams = null
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {}
        Log.d(TAG, "Floating button hidden")
    }

    private fun handleTouch(v: View, event: MotionEvent): Boolean {
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = x
                lastRawY = y
                dragging = false
                showTouchFeedback()
                try {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                } catch (_: Exception) {}
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastRawX
                val dy = y - lastRawY
                if (!dragging && hypot(dx, dy) > DRAG_SLOP_PX) dragging = true
                if (dragging) {
                    val lp = layoutParams ?: return true
                    lp.x += dx.toInt()
                    lp.y += dy.toInt()
                    lastRawX = x
                    lastRawY = y
                    try {
                        windowManager.updateViewLayout(v, lp)
                    } catch (_: Exception) {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                hideTouchFeedback()
                if (!dragging) {
                    val now = SystemClock.uptimeMillis()
                    val slop = 40f * context.resources.displayMetrics.density
                    if (lastTapTime != 0L &&
                        now - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS &&
                        hypot(x - lastTapX, y - lastTapY) <= slop
                    ) {
                        lastTapTime = 0L
                        if (now - lastSwitchAt >= POST_SWITCH_DEBOUNCE_MS) {
                            lastSwitchAt = now
                            performSwitch(v)
                        } else {
                            Log.d(TAG, "Double-tap ignored (debounce)")
                        }
                    } else {
                        lastTapTime = now
                        lastTapX = x
                        lastTapY = y
                    }
                }
                return true
            }
        }
        return true
    }

    private fun showTouchFeedback() {
        try {
            circleView?.background?.alpha = FEEDBACK_CIRCLE_ALPHA
            circleView?.setTextColor(FEEDBACK_TEXT_COLOR)
        } catch (_: Exception) {}
    }

    private fun hideTouchFeedback() {
        try {
            circleView?.background?.alpha = 0
            circleView?.setTextColor(TEXT_COLOR)
        } catch (_: Exception) {}
    }

    private fun performSwitch(v: View) {
        Log.d(TAG, "Double-tap on floating button -> switch")
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {}
        WallpaperSwitchService.switchNow(context)
    }
}
