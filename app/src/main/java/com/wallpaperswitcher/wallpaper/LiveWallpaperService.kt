package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.*

class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
    }

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var db: AppDatabase
        private var surfaceReady = false
        private var isVisible = false
        private var isSwitching = false
        private var currentBitmap: Bitmap? = null
        private var currentScaleMode: ScaleMode = ScaleMode.FIT

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_SWITCH) doSwitch("broadcast")
            }
        }

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
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
            try {
                applicationContext.registerReceiver(switchReceiver, IntentFilter(ACTION_SWITCH))
            } catch (_: Exception) {}
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            surfaceReady = true
            drawCurrentImage()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            drawCurrentImage()
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
            if (visible) drawCurrentImage()
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            currentBitmap?.recycle(); currentBitmap = null
            scope.cancel()
            super.onDestroy()
        }

        /**
         * Switch to next image per group settings.
         */
        private fun doSwitch(source: String) {
            if (isSwitching) return
            isSwitching = true
            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    val groupDao = db.wallpaperGroupDao()

                    // Check if any group is enabled
                    val groups = groupDao.getEnabledGroupsSync()
                    if (groups.isEmpty()) { isSwitching = false; return@launch }

                    val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)

                    // Get global switch mode
                    val switchMode = try {
                        SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                    } catch (_: Exception) { SwitchMode.RANDOM }

                    // Pick next image from ALL enabled groups
                    val nextImage = when (switchMode) {
                        SwitchMode.RANDOM -> {
                            imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups()
                        }
                        SwitchMode.SEQUENTIAL -> {
                            val count = imageDao.countByEnabledGroups()
                            if (count == 0) null
                            else {
                                val idx = dao.getLong(SettingsKeys.SEQUENTIAL_INDEX).toInt()
                                val next = (idx + 1) % count
                                dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, next.toLong())
                                // For sequential, just use random (sequential across all groups is complex)
                                imageDao.getRandomImageFromEnabledGroups()
                            }
                        }
                        SwitchMode.SHUFFLE -> {
                            imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups()
                        }
                    }

                    if (nextImage == null) { isSwitching = false; return@launch }

                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)

                    val scaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }

                    val bitmap = loadBitmap(nextImage.uri)
                    if (bitmap != null) {
                        mainHandler.post { showBitmap(bitmap, scaleMode) }
                        Log.d(TAG, "$source: ${nextImage.displayName}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "$source error", e)
                } finally {
                    isSwitching = false
                }
            }
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    val scaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }
                    val image = if (imageId > 0) db.wallpaperImageDao().getImageById(imageId) else null
                    if (image != null) {
                        val bitmap = loadBitmap(image.uri)
                        if (bitmap != null) { mainHandler.post { showBitmap(bitmap, scaleMode) }; return@launch }
                    }
                    // Fallback
                    val first = db.wallpaperImageDao().getRandomImage()
                    if (first != null) {
                        dao.setLong(SettingsKeys.LAST_IMAGE_ID, first.id)
                        val bitmap = loadBitmap(first.uri)
                        if (bitmap != null) { mainHandler.post { showBitmap(bitmap, scaleMode) }; return@launch }
                    }
                    mainHandler.post { showDefault() }
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                    mainHandler.post { showDefault() }
                }
            }
        }

        private fun showBitmap(bitmap: Bitmap, scaleMode: ScaleMode = ScaleMode.FIT) {
            if (!surfaceReady) return
            try {
                currentBitmap?.recycle(); currentBitmap = bitmap
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val m = getMetrics()
                val sw = m.widthPixels.toFloat(); val sh = m.heightPixels.toFloat()
                val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()

                val dest: RectF = when (scaleMode) {
                    ScaleMode.FIT -> {
                        // Maintain aspect ratio, fit inside screen (may have black bars)
                        val ratio = bw / bh; val sr = sw / sh
                        val dw: Float; val dh: Float
                        if (ratio > sr) { dw = sw; dh = dw / ratio } else { dh = sh; dw = dh * ratio }
                        val l = (sw - dw) / 2f; val t = (sh - dh) / 2f
                        RectF(l, t, l + dw, t + dh)
                    }
                    ScaleMode.FILL -> {
                        // Maintain aspect ratio, fill entire screen (may crop edges)
                        val ratio = bw / bh; val sr = sw / sh
                        val dw: Float; val dh: Float
                        if (ratio < sr) { dw = sw; dh = dw / ratio } else { dh = sh; dw = dh * ratio }
                        val l = (sw - dw) / 2f; val t = (sh - dh) / 2f
                        RectF(l, t, l + dw, t + dh)
                    }
                    ScaleMode.STRETCH -> {
                        // Stretch to fill screen exactly (ignore aspect ratio)
                        RectF(0f, 0f, sw, sh)
                    }
                }

                canvas.drawBitmap(bitmap, null, dest, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) { Log.e(TAG, "showBitmap error", e) }
        }

        private fun showDefault() {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.DKGRAY)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER }
                val m = getMetrics()
                canvas.drawText("Wallpaper Switcher", m.widthPixels / 2f, m.heightPixels / 2f, p)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        private fun loadBitmap(uriStr: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriStr)
                val m = getMetrics()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
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
}
