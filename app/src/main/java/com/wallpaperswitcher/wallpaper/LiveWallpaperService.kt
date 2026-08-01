package com.wallpaperswitcher.wallpaper

import android.graphics.*
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.engine.WallpaperEngine
import kotlinx.coroutines.*

class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private lateinit var engine: WallpaperEngine
        private lateinit var db: AppDatabase
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
            surfaceReady = true
            drawCurrent()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            drawCurrent()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) drawCurrent()
        }

        override fun onDestroy() {
            scope.cancel()
            super.onDestroy()
        }

        private fun doSwitch(source: String) {
            if (isSwitching) return
            isSwitching = true
            scope.launch {
                try {
                    engine.switchToNext()
                    drawCurrent()
                    Log.d(TAG, "$source switch done")
                } catch (e: Exception) {
                    Log.e(TAG, "$source switch failed", e)
                } finally {
                    isSwitching = false
                }
            }
        }

        private fun drawCurrent() {
            if (!surfaceReady || !isVisible) return
            scope.launch {
                try {
                    val imageId = db.settingsDao().getLong(SettingsKeys.LAST_IMAGE_ID)
                    val image = if (imageId > 0) db.wallpaperImageDao().getImageById(imageId) else null
                    if (image != null) {
                        val bitmap = loadBitmap(image.uri)
                        if (bitmap != null) { showBitmap(bitmap); bitmap.recycle(); return@launch }
                    }
                    // No image yet - load first available
                    val firstImage = db.wallpaperImageDao().getFirstImage()
                    if (firstImage != null) {
                        val bitmap = loadBitmap(firstImage.uri)
                        if (bitmap != null) { showBitmap(bitmap); bitmap.recycle(); return@launch }
                    }
                    showDefault()
                } catch (e: Exception) {
                    Log.e(TAG, "draw failed", e)
                    showDefault()
                }
            }
        }

        private fun showDefault() {
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.DKGRAY)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER }
                val m = getMetrics()
                canvas.drawText("Wallpaper Switcher", m.widthPixels / 2f, m.heightPixels / 2f, p)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        private fun showBitmap(bitmap: Bitmap) {
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val m = getMetrics()
                val sw = m.widthPixels.toFloat(); val sh = m.heightPixels.toFloat()
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val sr = sw / sh
                val dw: Float; val dh: Float
                if (ratio > sr) { dh = sh; dw = dh * ratio } else { dw = sw; dh = dw / ratio }
                val l = (sw - dw) / 2f; val t = (sh - dh) / 2f
                canvas.drawBitmap(bitmap, null, RectF(l, t, l + dw, t + dh), null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        private fun loadBitmap(uriStr: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriStr)
                val m = getMetrics()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var s = 1
                while (opts.outWidth / s > m.widthPixels * 2 || opts.outHeight / s > m.heightPixels * 2) s *= 2
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }
            } catch (_: Exception) { null }
        }

        private fun getMetrics(): DisplayMetrics {
            val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
            return DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        }
    }

    companion object {
        private const val TAG = "LiveWallpaperService"
    }
}
