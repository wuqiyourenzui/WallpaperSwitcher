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

class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private lateinit var engine: WallpaperEngine
        private lateinit var db: AppDatabase
        private var switchJob: Job? = null
        private var surfaceReady = false
        private var isVisible = false
        private var isSwitching = false

        private val gestureDetector = GestureDetector(
            applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    doSwitch("double-tap")
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

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
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

        private fun doSwitch(source: String) {
            if (isSwitching) return
            isSwitching = true
            scope.launch {
                try {
                    val result = engine.switchToNext()
                    Log.d(TAG, "$source switch result: $result")
                    if (result) {
                        drawCurrentWallpaper()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "$source switch failed", e)
                } finally {
                    isSwitching = false
                }
            }
        }

        private fun drawCurrentWallpaper() {
            if (!surfaceReady || !isVisible) return
            scope.launch {
                try {
                    val lastImageId = db.settingsDao().getLong(SettingsKeys.LAST_IMAGE_ID)
                    val image = if (lastImageId > 0) {
                        db.wallpaperImageDao().getImageById(lastImageId)
                    } else null

                    if (image != null) {
                        val bitmap = decodeSampledBitmap(image.uri)
                        if (bitmap != null) {
                            drawBitmap(bitmap)
                            bitmap.recycle()
                            return@launch
                        }
                    }
                    drawDefault()
                } catch (e: Exception) {
                    Log.e(TAG, "Draw failed", e)
                    drawDefault()
                }
            }
        }

        private fun drawDefault() {
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.DKGRAY)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER
                }
                val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
                val m = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
                canvas.drawText("Wallpaper Switcher", m.widthPixels / 2f, m.heightPixels / 2f, paint)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "Draw default failed", e)
            }
        }

        private fun drawBitmap(bitmap: Bitmap) {
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
                val m = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
                val sw = m.widthPixels.toFloat()
                val sh = m.heightPixels.toFloat()
                val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val screenRatio = sw / sh
                val dw: Float
                val dh: Float
                if (imgRatio > screenRatio) { dh = sh; dw = dh * imgRatio }
                else { dw = sw; dh = dw / imgRatio }
                val left = (sw - dw) / 2f
                val top = (sh - dh) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "Draw bitmap failed", e)
            }
        }

        private fun decodeSampledBitmap(uriString: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriString)
                val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
                val m = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
                val sw = m.widthPixels
                val sh = m.heightPixels
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (opts.outWidth / sample > sw * 2 || opts.outHeight / sample > sh * 2) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            } catch (e: Exception) { null }
        }

        private fun startSwitchLoop() {
            switchJob?.cancel()
            switchJob = scope.launch {
                while (isActive) {
                    try {
                        val serviceEnabled = db.settingsDao().getBool(SettingsKeys.SERVICE_ENABLED, false)
                        if (!serviceEnabled) { delay(30_000L); continue }
                        val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                        if (groups.isEmpty()) { delay(60_000L); continue }
                        val minInterval = groups.minOf { it.switchIntervalMs }.coerceAtLeast(60_000L)
                        delay(minInterval)
                        doSwitch("timed")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.e(TAG, "Loop error", e); delay(10_000L) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "LiveWallpaperService"
    }
}
