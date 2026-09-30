package com.wallpaperswitcher.wallpaper

import com.wallpaperswitcher.engine.FloatingButtonContent
import com.wallpaperswitcher.engine.FloatingButtonContentPolicy
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlin.math.hypot

/**
 * Small draggable floating button that switches the wallpaper on a single tap.
 *
 * The live wallpaper engine normally receives home-screen touches itself, but
 * some Android 16/17 launchers no longer forward them to the wallpaper window,
 * which makes engine-side touch switching unreliable. This button is a
 * launcher-independent fallback: it is a real window above every app, so a tap
 * always reaches it. Drag the button to move it anywhere (a drag never fires a
 * switch).
 *
 * ## One window per process, attached once
 *
 * [obtain] returns the process-wide instance and the overlay window is created on
 * the first [showOnDesktop] and then **kept attached** for the rest of the
 * process: [hideOverlay] only makes the view invisible + not touchable. The old
 * per-engine `addView`/`removeView` cycling was wrong on two counts (both
 * measured on the Xiaomi phone, 09-30):
 *
 * 1. `removeView` reported no error yet the window stayed alive - `dumpsys window`
 *    showed THREE of our `ty=APPLICATION_OVERLAY` windows (all `isVisible=true`)
 *    while the log had matching `shown`/`hidden` pairs, so invisible duplicates
 *    accumulated (and only the instance the engine last held received the live
 *    text/picture updates).
 * 2. Every add/remove is a window-stack change, which made the ROM report the
 *    wallpaper as covered for 250-400ms and then visible again; the engine
 *    paused/resumed on each blip (~5 per hour, see 4.9.43). With a stable window
 *    that churn cannot happen at all.
 *
 * Appearance (base color + rest-state opacity), the text label and an optional
 * custom picture are configured from Settings and can all be updated live via
 * [setAppearance] / [setContent]. A picture REPLACES the label (see
 * [FloatingButtonContentPolicy]).
 */
class FloatingSwitchButton private constructor(context: Context) {

    companion object {
        private const val TAG = "FloatingSwitchButton"
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
        // Default appearance (matches the original hard-coded look): blue base,
        // 10% opacity at rest = a faint hint of the hotspot without
        // obstructing the wallpaper.
        const val DEFAULT_COLOR = 0xFF1E88E5.toInt()
        const val DEFAULT_OPACITY = 10
        // While the finger is down the circle brightens to ~38% opacity and the
        // text to ~90% so the hit area is clearly confirmed.
        private const val FEEDBACK_CIRCLE_ALPHA = 96
        private const val FEEDBACK_TEXT_ALPHA = 0xE6
        private const val FEEDBACK_IMAGE_ALPHA = 1f

        /** The one button of this process (see the class comment). */
        @Volatile
        private var shared: FloatingSwitchButton? = null

        /**
         * The process-wide button. Engines come and go (the wallpaper is
         * re-applied, the picker's preview engine starts, ...); the overlay window
         * must not be re-created for each of them.
         */
        fun obtain(context: Context): FloatingSwitchButton =
            shared ?: synchronized(this) {
                shared ?: FloatingSwitchButton(context.applicationContext).also { shared = it }
            }

        /** Parse a "#RRGGBB" string into an opaque ARGB int. */
        fun parseColor(hex: String?): Int =
            com.wallpaperswitcher.util.parseHexColorInt(hex) ?: DEFAULT_COLOR
    }

    private val appContext: Context = context
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var button: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var circleView: FrameLayout? = null
    private var labelView: TextView? = null
    private var imageView: ImageView? = null
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    /** URI currently loaded into [imageView] (or being loaded). */
    private var activeImageUri: String? = null
    /** URI whose decode failed: draw the label instead of an empty circle. */
    private var failedImageUri: String? = null
    /** Last content written to the log, so a slider drag cannot spam it. */
    private var loggedContent: FloatingButtonContent? = null

    private var colorArgb: Int = DEFAULT_COLOR
    private var opacityPercent: Int = DEFAULT_OPACITY
    private var textValue: String? = FloatingButtonContentPolicy.DEFAULT_TEXT
    private var imageUri: String? = null

    // Rest-state colors, derived from the current appearance. The drawable's fill
    // color carries the alpha (never Drawable.setAlpha, which would multiply
    // instead of replace it).
    private var restCircleColor: Int = buildCircleColor(colorArgb, opacityPercent)
    private var restTextColor: Int = buildTextColor(opacityPercent)

    private fun alphaFor(opacityPercent: Int): Int =
        (255 * opacityPercent.coerceIn(0, 100) / 100).coerceIn(0, 255)

    private fun buildCircleColor(colorArgb: Int, opacityPercent: Int): Int =
        (alphaFor(opacityPercent) shl 24) or (colorArgb and 0x00FFFFFF)

    private fun buildTextColor(opacityPercent: Int): Int =
        (alphaFor(opacityPercent) shl 24) or 0x00FFFFFF

    /** Rest/full opacity for a custom picture, mirroring the label's alpha. */
    private fun imageAlpha(opacityPercent: Int): Float =
        opacityPercent.coerceIn(1, 100) / 100f

    /** True while the overlay window is attached and shown. */
    val isShown: Boolean
        get() = button != null && button?.visibility == View.VISIBLE

    /**
     * Show the button on the desktop: attach the window on first use, then only
     * make it visible + touchable.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showOnDesktop() {
        val existing = button
        if (existing != null) {
            // The ROM may have dropped the window while the view was hidden (MIUI
            // does): re-attach the SAME view instead of building a new one, so the
            // process can never end up with two windows.
            if (!existing.isAttachedToWindow) {
                try {
                    windowManager.addView(existing, layoutParams)
                    AppLog.d(
                        TAG,
                        "Floating button re-attached (id=${System.identityHashCode(this)})"
                    )
                } catch (e: Exception) {
                    AppLog.w(TAG, "Re-attaching the floating button failed", e)
                }
            }
            if (existing.visibility != View.VISIBLE) {
                existing.visibility = View.VISIBLE
                setTouchable(true)
                AppLog.d(TAG, "Floating button shown (id=${System.identityHashCode(this)})")
            }
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            AppLog.w(TAG, "Overlay permission not granted, button hidden")
            return
        }
        val density = appContext.resources.displayMetrics.density
        val sizePx = (BUTTON_SIZE_DP * density).toInt()
        val visualPx = (VISUAL_SIZE_DP * density).toInt()
        val marginPx = ((BUTTON_SIZE_DP - VISUAL_SIZE_DP) / 2 * density).toInt()
        val initialLabel = FloatingButtonContentPolicy.clampText(textValue)
        val label = TextView(appContext).apply {
            text = initialLabel
            textSize = FloatingButtonContentPolicy.textSizeSp(initialLabel)
            setTextColor(restTextColor)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val picture = ImageView(appContext).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        // The circle is the oval background; clipToOutline (backed by the oval
        // GradientDrawable) crops the picture into the same shape, so no
        // pre-rounded bitmap is needed and live updates stay cheap.
        val circle = FrameLayout(appContext).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(restCircleColor)
            }
            clipToOutline = true
            addView(
                picture,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        labelView = label
        imageView = picture
        circleView = circle
        applyContent()
        val view = FrameLayout(appContext).apply {
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
            val displayMetrics = appContext.resources.displayMetrics
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
                x = (displayMetrics.widthPixels - sizePx - (EDGE_MARGIN_DP * density).toInt())
                    .coerceIn(0, maxX)
                y = (displayMetrics.heightPixels - sizePx - (BOTTOM_MARGIN_DP * density).toInt())
                    .coerceIn(0, maxY)
            }
        }
        view.setOnTouchListener { v, event -> handleTouch(v, event) }
        try {
            windowManager.addView(view, lp)
            button = view
            layoutParams = lp
            AppLog.d(TAG, "Floating button shown (id=${System.identityHashCode(this)})")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to show floating button", e)
        }
    }

    /**
     * Hide the button while keeping the window attached (no `removeView`, see the
     * class comment): the view goes invisible and stops accepting touches.
     */
    fun hideOverlay() {
        val v = button ?: return
        if (v.visibility == View.GONE) return
        layoutParams?.let { persistPosition(it.x, it.y) }
        v.visibility = View.GONE
        setTouchable(false)
        AppLog.d(TAG, "Floating button hidden (id=${System.identityHashCode(this)})")
    }

    /**
     * Really remove the window. Only for "the user turned the feature off" and
     * for a final teardown - a normal hide must use [hideOverlay].
     *
     * The view reference is kept when the ROM did not actually detach it (MIUI
     * has been seen to report no error while the window stayed alive), so a later
     * [showOnDesktop] reuses that same window instead of adding a second one.
     */
    fun removeOverlay() {
        val v = button ?: return
        layoutParams?.let { persistPosition(it.x, it.y) }
        try {
            windowManager.removeView(v)
        } catch (e: Exception) {
            AppLog.w(TAG, "removeView failed for the floating button", e)
        }
        if (v.isAttachedToWindow) {
            // The window survived the removal: keep the reference and let the next
            // show reuse it - dropping it here would make the next show add a
            // duplicate.
            v.visibility = View.GONE
            setTouchable(false)
            AppLog.w(TAG, "Floating button window survived removeView; keeping it for reuse")
            return
        }
        button = null
        layoutParams = null
        circleView = null
        labelView = null
        imageView = null
        activeImageUri = null
        AppLog.d(TAG, "Floating button removed (id=${System.identityHashCode(this)})")
    }

    private fun setTouchable(touchable: Boolean) {
        val lp = layoutParams ?: return
        val v = button ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val newFlags = if (touchable) lp.flags and notTouchable.inv() else lp.flags or notTouchable
        if (newFlags == lp.flags) return
        lp.flags = newFlags
        try {
            windowManager.updateViewLayout(v, lp)
        } catch (_: Exception) {}
    }

    /**
     * Live-update the button appearance (called from the engine when the user
     * changes the settings). Cheap: only recolors the existing views, never
     * rebuilds the window, so a slider drag updates the desktop in real time.
     */
    fun setAppearance(colorArgb: Int, opacityPercent: Int) {
        this.colorArgb = colorArgb
        this.opacityPercent = opacityPercent.coerceIn(0, 100)
        restCircleColor = buildCircleColor(this.colorArgb, this.opacityPercent)
        restTextColor = buildTextColor(this.opacityPercent)
        val circle = circleView ?: return
        try {
            (circle.background as? android.graphics.drawable.GradientDrawable)?.setColor(restCircleColor)
            labelView?.setTextColor(restTextColor)
            imageView?.alpha = imageAlpha(this.opacityPercent)
        } catch (_: Exception) {}
    }

    /**
     * Live-update the button content: a custom picture (which replaces the
     * label) or the text label. No window rebuild, so the desktop updates as
     * soon as the user picks a picture or types a label.
     */
    fun setContent(text: String?, imageUri: String?) {
        val normalizedImage = imageUri?.trim().orEmpty()
        if (textValue == text && this.imageUri == normalizedImage) return
        textValue = text
        this.imageUri = normalizedImage
        if (button == null) return
        // A different picture (or none) invalidates the previous decode result.
        if (activeImageUri != normalizedImage) {
            activeImageUri = null
            failedImageUri = null
        }
        applyContent()
    }

    /** Draw whatever the current text/picture settings resolve to. */
    private fun applyContent() {
        val label = labelView ?: return
        val picture = imageView ?: return
        var content = FloatingButtonContentPolicy.resolve(textValue, imageUri)
        if (content is FloatingButtonContent.Image && content.uri == failedImageUri) {
            // The pick could not be decoded (deleted file, revoked permission):
            // fall back to the label so the button is never an empty circle.
            content = FloatingButtonContentPolicy.resolve(textValue, null)
        }
        if (content != loggedContent) {
            loggedContent = content
            AppLog.d(
                TAG,
                when (content) {
                    is FloatingButtonContent.Image ->
                        "Floating button content: image ${LogText.short(content.uri)}"
                    is FloatingButtonContent.Text ->
                        "Floating button content: text '${content.value}'"
                }
            )
        }
        when (content) {
            is FloatingButtonContent.Image -> {
                // A picture REPLACES the label (user requirement).
                label.visibility = View.GONE
                label.text = ""
                if (activeImageUri != content.uri) {
                    activeImageUri = content.uri
                    loadImage(content.uri)
                }
            }
            is FloatingButtonContent.Text -> {
                activeImageUri = null
                failedImageUri = null
                picture.setImageDrawable(null)
                picture.visibility = View.GONE
                label.visibility = View.VISIBLE
                label.text = content.value
                label.textSize = FloatingButtonContentPolicy.textSizeSp(content.value)
                label.setTextColor(restTextColor)
            }
        }
    }

    /**
     * Decode the picked picture on a helper thread (a modern camera photo is
     * 12-50MP; decoding it at full size just for a 40dp circle would be a
     * multi-megabyte bitmap) and install it on the main thread.
     */
    private fun loadImage(uriStr: String) {
        val targetPx = (VISUAL_SIZE_DP * appContext.resources.displayMetrics.density)
            .toInt()
            .coerceAtLeast(1)
        val requested = uriStr
        Thread({
            val bitmap = decodeSampled(uriStr, targetPx)
            mainHandler.post {
                if (activeImageUri != requested) {
                    // A newer pick (or a text label) won while this decoded.
                    bitmap?.recycle()
                    return@post
                }
                val picture = imageView
                if (picture == null) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    AppLog.w(
                        TAG,
                        "Floating button image undecodable, falling back to text: " +
                            LogText.short(requested)
                    )
                    failedImageUri = requested
                    activeImageUri = null
                    applyContent()
                    return@post
                }
                picture.setImageBitmap(bitmap)
                picture.alpha = imageAlpha(opacityPercent)
                picture.visibility = View.VISIBLE
            }
        }, "FloatingButtonImage").apply { isDaemon = true }.start()
    }

    /** Bounds + sample-size decode, so a huge photo costs a small bitmap. */
    private fun decodeSampled(uriStr: String, targetPx: Int): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx &&
                bounds.outHeight / (sample * 2) >= targetPx
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "Floating button image load failed: ${LogText.short(uriStr)} ${t.message}")
            null
        }
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
                    // Keep the button fully on screen while dragging: an
                    // unclamped drag could push it off-screen with no way to
                    // grab it again.
                    val dm = appContext.resources.displayMetrics
                    val maxX = (dm.widthPixels - lp.width).coerceAtLeast(0)
                    val maxY = (dm.heightPixels - lp.height).coerceAtLeast(0)
                    lp.x = (lp.x + dx).toInt().coerceIn(0, maxX)
                    lp.y = (lp.y + dy).toInt().coerceIn(0, maxY)
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
                    // Every tap switches. Rate limiting lives in the engine, which
                    // coalesces triggers that arrive while a switch is running into
                    // ONE pending switch (see requestSwitch) - so a burst cannot
                    // pile up decodes, and no tap is silently dropped. The old
                    // 250ms window here swallowed 105 of 295 taps in the tablet log.
                    performSwitch(v)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hideTouchFeedback()
                return true
            }
        }
        return true
    }

    private fun showTouchFeedback() {
        try {
            val feedbackAlpha = maxOf(FEEDBACK_CIRCLE_ALPHA, alphaFor(opacityPercent))
            (circleView?.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor((feedbackAlpha shl 24) or (colorArgb and 0x00FFFFFF))
            labelView?.setTextColor((FEEDBACK_TEXT_ALPHA shl 24) or 0x00FFFFFF)
            imageView?.alpha = FEEDBACK_IMAGE_ALPHA
        } catch (_: Exception) {}
    }

    private fun hideTouchFeedback() {
        try {
            (circleView?.background as? android.graphics.drawable.GradientDrawable)?.setColor(restCircleColor)
            labelView?.setTextColor(restTextColor)
            imageView?.alpha = imageAlpha(opacityPercent)
        } catch (_: Exception) {}
    }

    private fun performSwitch(v: View) {
        AppLog.d(TAG, "Floating button tap -> switch")
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {}
        if (LiveWallpaperService.engineRunning) {
            // Direct engine trigger: no broadcast round-trip, snappier feel.
            val dispatched = LiveWallpaperService.requestSwitchFromOutside(
                LiveWallpaperService.SOURCE_FLOATING
            )
            if (!dispatched) {
                // Engine instance vanished mid-gesture (e.g. wallpaper being
                // re-applied): fall back to the broadcast path.
                WallpaperSwitchService.switchNow(appContext)
            }
        } else {
            WallpaperSwitchService.switchNow(appContext)
        }
    }

    private fun persistPosition(x: Int, y: Int) {
        try {
            prefs.edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
        } catch (_: Exception) {}
    }
}
