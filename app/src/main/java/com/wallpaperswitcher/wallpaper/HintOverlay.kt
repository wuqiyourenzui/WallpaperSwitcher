package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.wallpaperswitcher.util.AppLog

/**
 * Floating instruction bubble for the "now tap 设为壁纸 in the system dialog"
 * hints.
 *
 * A Toast only lives ~2s (LENGTH_SHORT) / ~3.5s (LENGTH_LONG), and Android 12+
 * drops toasts posted while the app is in the background - which is exactly
 * when these hints matter, because the system live-wallpaper picker covers the
 * app right after the user taps a media item. The bubble is therefore drawn as
 * an overlay window (the same permission the floating switch button already
 * uses), non-focusable and non-touchable so it never blocks the picker, and it
 * removes itself after [DEFAULT_DURATION_MS].
 *
 * When the overlay permission is missing [show] returns false and the caller
 * falls back to [showLongToast], which re-shows a normal toast so the text also
 * stays readable well beyond the platform's maximum.
 */
object HintOverlay {
    private const val TAG = "HintOverlay"

    /**
     * Default on-screen time. Long enough to read the instruction and find the
     * button in the system dialog, short enough not to linger: the hint is also
     * removed as soon as the user leaves the dialog (see the callers).
     */
    const val DEFAULT_DURATION_MS = 5_000L

    /** The fallback toast is re-shown this often until the total time is up. */
    private const val TOAST_REPEAT_STEP_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private var windowManager: WindowManager? = null
    private var removeRunnable: Runnable? = null
    // The repeating fallback toast (see showLongToast), so it can be stopped
    // when the hint is no longer relevant (dismiss()).
    private var toastRunnable: Runnable? = null

    private fun px(dp: Float, density: Float): Int = (dp * density).toInt()

    /**
     * The hints emphasise the option the user has to pick in the system dialog
     * (e.g. 请选择“主屏幕和锁定屏幕”). Everything inside the quotes is rendered
     * bold, so the caller only has to send plain text - the same string is then
     * also usable for the Toast fallback.
     */
    private fun emphasize(text: String): CharSequence {
        val spannable = android.text.SpannableString(text)
        val bold = android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
        for (quote in listOf('\u201C' to '\u201D', '\u300C' to '\u300D')) {
            var start = text.indexOf(quote.first)
            while (start >= 0) {
                val end = text.indexOf(quote.second, start + 1)
                if (end < 0) break
                spannable.setSpan(
                    bold, start, end + 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                start = text.indexOf(quote.first, end + 1)
            }
        }
        return spannable
    }

    /**
     * Show [text] as a floating bubble for [durationMs].
     *
     * @return false when the overlay permission is missing (or the window could
     *   not be added), so the caller can fall back to [showLongToast].
     */
    fun show(context: Context, text: String, durationMs: Long = DEFAULT_DURATION_MS): Boolean {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            AppLog.d(TAG, "Overlay permission not granted; hint falls back to a long toast")
            return false
        }
        dismiss()
        return try {
            val metrics = app.resources.displayMetrics
            val density = metrics.density
            val view = TextView(app).apply {
                this.text = emphasize(text)
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(
                    px(18f, density), px(12f, density),
                    px(18f, density), px(12f, density)
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18f * density
                    setColor(0xE6212121.toInt())
                }
                maxWidth = (metrics.widthPixels * 0.85f).toInt()
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Neither focusable nor touchable: the user keeps interacting
                // with the system dialog underneath.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (metrics.heightPixels * 0.14f).toInt()
            }
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(view, params)
            bubble = view
            windowManager = wm
            val remove = Runnable { dismiss() }
            removeRunnable = remove
            mainHandler.postDelayed(remove, durationMs)
            AppLog.d(TAG, "Hint shown for ${durationMs}ms: $text")
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "Failed to show hint overlay", t)
            false
        }
    }

    /** Remove the current bubble immediately (also used when replacing it). */
    fun dismiss() {
        removeRunnable?.let { mainHandler.removeCallbacks(it) }
        removeRunnable = null
        // The fallback toast must disappear with the bubble: the hint is
        // dismissed as soon as the app comes back to the foreground (the user
        // left the system picker), and a toast that kept re-showing itself for
        // several seconds after that looked like a stuck notification.
        cancelLongToast()
        val view = bubble ?: return
        bubble = null
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // Removing an already-detached view throws; log it so a stuck
            // overlay window is at least visible in the runtime report.
            AppLog.w(TAG, "removeView failed for the hint bubble", e)
        }
        windowManager = null
    }

    /** Stop a fallback toast that is still being repeated ([showLongToast]). */
    private fun cancelLongToast() {
        toastRunnable?.let { mainHandler.removeCallbacks(it) }
        toastRunnable = null
    }

    /**
     * Fallback used when the overlay permission is not granted: keep a normal
     * toast on screen for [totalMs] by re-showing it (the platform caps a single
     * toast at ~3.5s, so one show() would be too short for the instruction).
     */
    fun showLongToast(
        context: Context,
        text: String,
        totalMs: Long = DEFAULT_DURATION_MS
    ) {
        val app = context.applicationContext
        val toast = Toast.makeText(app, emphasize(text), Toast.LENGTH_LONG)
        var shownMs = 0L
        val repeat = object : Runnable {
            override fun run() {
                try {
                    toast.show()
                } catch (_: Exception) {
                    if (toastRunnable === this) toastRunnable = null
                    return
                }
                shownMs += TOAST_REPEAT_STEP_MS
                if (shownMs < totalMs) {
                    mainHandler.postDelayed(this, TOAST_REPEAT_STEP_MS)
                } else if (toastRunnable === this) {
                    // Finished on its own: nothing left to cancel.
                    toastRunnable = null
                }
            }
        }
        // A hint replaces the previous one: stop its toast chain first.
        cancelLongToast()
        toastRunnable = repeat
        repeat.run()
    }
}
