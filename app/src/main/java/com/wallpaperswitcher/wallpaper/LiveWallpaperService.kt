package com.wallpaperswitcher.wallpaper

import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.engine.WallpaperEngine
import kotlinx.coroutines.*

/**
 * Live Wallpaper Service.
 * Draws the current wallpaper image on the surface.
 * Supports double-tap and timed switching.
 */
class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private lateinit var engine: WallpaperEngine
        private lateinit var db: AppDatabase
        private var switchJob: Job? = null
        private var surfaceReady = false
        private var visible = false

        private val gestureDetector = GestureDetector(
            applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    onDoubleTapDetected()
                    return true
                }
            }
        )

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            engine = WallpaperEngine(applicationContext)
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            drawCurrentWallpaper()
            startSwitchLoop()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawCurrentWallpaper()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            switchJob?.cancel()
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (visible) {
                drawCurrentWallpaper()
                startSwitchLoop()
            } else {
                switchJob?.cancel()
            }
        }

        override fun onDestroy() {
            switchJob?.cancel()
            scope.cancel()
            super.onDestroy()
        }

        /**
         * Draw the current wallpaper image on the surface.
         */
        private fun drawCurrentWallpaper() {
            if (!surfaceReady || !visible) return
            scope.launch {
                try {
                    val lastImageId = db.settingsDao().getLong(SettingsKeys.LAST_IMAGE_ID)
                    if (lastImageId == 0L) {
                        drawDefault()
                        return@launch
                    }

                    val image = db.wallpaperImageDao().getImageById(lastImageId)
                    if (image == null) {
                        drawDefault()
                        return@launch
                    }

                    val bitmap = decodeSampledBitmap(image.uri)
                    if (bitmap != null) {
                        drawBitmap(bitmap)
                        bitmap.recycle()
                    } else {
                        drawDefault()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Draw wallpaper failed", e)
                    drawDefault()
                }
            }
        }

        private fun drawDefault() {
            val holder = surfaceHolder ?: return
            try {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.DKGRAY)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 48f
                        textAlign = Paint.Align.CENTER
                    }
                    val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(metrics)
                    canvas.drawText(
                        "Wallpaper Switcher",
                        metrics.widthPixels / 2f,
                        metrics.heightPixels / 2f,
                        paint
                    )
                    holder.unlockCanvasAndPost(canvas)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Draw default failed", e)
            }
        }

        private fun drawBitmap(bitmap: Bitmap) {
            val holder = surfaceHolder ?: return
            try {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(metrics)
                    val screenW = metrics.widthPixels.toFloat()
                    val screenH = metrics.heightPixels.toFloat()

                    // Center-crop scaling
                    val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val screenRatio = screenW / screenH
                    val drawW: Float
                    val drawH: Float
                    if (imgRatio > screenRatio) {
                        drawH = screenH
                        drawW = drawH * imgRatio
                    } else {
                        drawW = screenW
                        drawH = drawW / imgRatio
                    }
                    val left = (screenW - drawW) / 2f
                    val top = (screenH - drawH) / 2f
                    val dst = RectF(left, top, left + drawW, top + drawH)
                    canvas.drawBitmap(bitmap, null, dst, null)
                    holder.unlockCanvasAndPost(canvas)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Draw bitmap failed", e)
            }
        }

        private fun decodeSampledBitmap(uriString: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriString)
                val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                val screenW = metrics.widthPixels
                val screenH = metrics.heightPixels

                // First pass: bounds only
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }

                // Calculate sample size
                var sample = 1
                while (opts.outWidth / sample > screenW * 2 || opts.outHeight / sample > screenH * 2) {
                    sample *= 2
                }

                // Second pass: decode
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decode failed", e)
                null
            }
        }

        private fun startSwitchLoop() {
            switchJob?.cancel()
            switchJob = scope.launch {
                while (isActive) {
                    try {
                        val serviceEnabled = db.settingsDao()
                            .getBool(SettingsKeys.SERVICE_ENABLED, false)
                        if (!serviceEnabled) {
                            delay(30_000L)
                            continue
                        }

                        val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                        if (groups.isEmpty()) {
                            delay(60_000L)
                            continue
                        }

                        val minInterval = groups.minOf { it.switchIntervalMs }
                            .coerceAtLeast(60_000L)
                        delay(minInterval)

                        engine.switchToNext()
                        // Draw the new wallpaper on the surface
                        drawCurrentWallpaper()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Switch loop error", e)
                        delay(10_000L)
                    }
                }
            }
        }

        private fun onDoubleTapDetected() {
            scope.launch {
                try {
                    val enabled = db.settingsDao()
                        .getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                    if (enabled) {
                        engine.switchToNext()
                        // Draw the new wallpaper on the surface
                        drawCurrentWallpaper()
                        Log.d(TAG, "Double-tap switch OK")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Double-tap switch failed", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "LiveWallpaperService"
    }
}
