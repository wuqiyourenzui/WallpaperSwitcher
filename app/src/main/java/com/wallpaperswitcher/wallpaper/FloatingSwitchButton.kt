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
        // enqueue two back-to-back switches, while still allowing a deliberate
        // second burst within a quick succession.
        private const val POST_SWITCH_DEBOUNCE_MS = 250L
        // Touch target (48dp = Android's minimum comfortable target), while the
        // visible circle is smaller and sits inside it.
        private const val BUTTON_SIZE_DP = 48
        private const val VISUAL_SIZE_DP = 40
        private const val DRAG_SLOP_PX = 8f
        private const val EDGE_MARGIN_DP = 24
        private const val BOTTOM_MARGIN_DP = 120
        private const val PREFS_NAME = "floating_button"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"
        // 90% transparent at rest (10% opacity): a faint hint of the hotspot
        // without obstructing the wallpaper. A soft circle + haptic appears
        // while the finger is down so the hit area is clearly confirmed.
        private const val CIRCLE_COLOR = 0x1A1E88E5.toInt()
        private const val TEXT_COLOR = 0x1AFFFFFF.toInt()
        private const val FEEDBACK_CIRCLE_ALPHA = 96
        private const val FEEDBACK_TEXT_COLOR = 0xE6FFFFFF.toInt()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
            val displayMetrics = context.resources.displayMetrics
            val maxX = (displayMetrics.widthPixels - sizePx).coerceAtLeast(0)
            val maxY = (displayMetrics.heightPixels - sizePx).coerceAtLeast(0)
            val savedX = prefs.getInt(KEY_POS_X, -1)
            val savedY = prefs.getInt(KEY_POS_Y, -1)
            if (savedX >= 0 && savedY >= 0) {
                // Restore the position the user dragged the button to, so
                // returning to the desktop never resets it.
                x = savedX.coerceIn(0, maxX)
                y = savedY.coerceIn(0, maxY)
            } else {
                // Default: bottom-right corner, above the dock/nav area.
                x = displayMetrics.widthPixels - sizePx - (EDGE_MARGIN_DP * density).toInt()
                y = displayMetrics.heightPixels - sizePx - (BOTTOM_MARGIN_DP * density).toInt()
            }
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
        layoutParams?.let { persistPosition(it.x, it.y) }
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
                // Fire on the second DOWN (not the second UP) so the switch
                // feels instant and "follows the finger".
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
                }
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
                layoutParams?.let { persistPosition(it.x, it.y) }
                if (!dragging) {
                    val now = SystemClock.uptimeMillis()
                    // Record the tap so the next DOWN can be recognized as the
                    // second tap of a double-tap.
                    lastTapTime = now
                    lastTapX = x
                    lastTapY = y
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hideTouchFeedback()
                lastTapTime = 0L
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
        if (LiveWallpaperService.engineRunning) {
            // Direct engine trigger: no broadcast round-trip, snappier feel.
            LiveWallpaperService.requestSwitchFromOutside("double-tap")
        } else {
            WallpaperSwitchService.switchNow(context)
        }
    }

    private fun persistPosition(x: Int, y: Int) {
        try {
            prefs.edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
        } catch (_: Exception) {}
    }
}
