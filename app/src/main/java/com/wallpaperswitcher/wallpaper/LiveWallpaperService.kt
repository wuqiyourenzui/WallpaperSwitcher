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
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import kotlinx.coroutines.*

/**
 * Live Wallpaper Service.
 *
 * Architecture:
 * - Draws images directly on the surface (no WallpaperManager dependency)
 * - Double-tap detection via GestureDetector on the Engine
 * - Listens for ACTION_SWITCH broadcast from timed service / unlock receiver
 * - Single source of truth: database stores current image index per group
 */
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

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_SWITCH) {
                    doSwitch("broadcast")
                }
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

            // Register switch broadcast receiver
            val filter = IntentFilter(ACTION_SWITCH)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(switchReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                applicationContext.registerReceiver(switchReceiver, filter)
            }
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
            try {
                applicationContext.unregisterReceiver(switchReceiver)
            } catch (_: Exception) {}
            currentBitmap?.recycle()
            currentBitmap = null
            scope.cancel()
            super.onDestroy()
        }

        /**
         * Core switch logic: load next image from DB, draw on surface.
         */
        private fun doSwitch(source: String) {
            if (isSwitching) return
            isSwitching = true
            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()

                    // Get enabled groups
                    val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                    if (groups.isEmpty()) {
                        Log.w(TAG, "No enabled groups")
                        isSwitching = false
                        return@launch
                    }

                    // Pick primary group
                    val group = groups.first()
                    val images = imageDao.getImagesByGroupSync(group.id)
                    if (images.isEmpty()) {
                        Log.w(TAG, "No images in group")
                        isSwitching = false
                        return@launch
                    }

                    // Get current index and advance
                    val currentIndex = dao.getLong(SettingsKeys.SEQUENTIAL_INDEX).toInt()
                    val nextIndex = (currentIndex + 1) % images.size
                    dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, nextIndex.toLong())

                    val nextImage = images[nextIndex]
                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)

                    // Decode and draw
                    val bitmap = loadBitmap(nextImage.uri)
                    if (bitmap != null) {
                        mainHandler.post {
                            showBitmap(bitmap)
                        }
                        Log.d(TAG, "$source switch OK: ${nextImage.displayName} (${nextIndex + 1}/${images.size})")
                    } else {
                        Log.e(TAG, "$source switch failed: decode error")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "$source switch error", e)
                } finally {
                    isSwitching = false
                }
            }
        }

        /**
         * Draw the current image (from LAST_IMAGE_ID) on the surface.
         */
        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            scope.launch {
                try {
                    val imageId = db.settingsDao().getLong(SettingsKeys.LAST_IMAGE_ID)
                    val image = if (imageId > 0) db.wallpaperImageDao().getImageById(imageId) else null

                    if (image != null) {
                        val bitmap = loadBitmap(image.uri)
                        if (bitmap != null) {
                            mainHandler.post { showBitmap(bitmap) }
                            return@launch
                        }
                    }

                    // Fallback: load first image from first group
                    val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                    if (groups.isNotEmpty()) {
                        val images = db.wallpaperImageDao().getImagesByGroupSync(groups.first().id)
                        if (images.isNotEmpty()) {
                            val bitmap = loadBitmap(images[0].uri)
                            if (bitmap != null) {
                                db.settingsDao().setLong(SettingsKeys.LAST_IMAGE_ID, images[0].id)
                                mainHandler.post { showBitmap(bitmap) }
                                return@launch
                            }
                        }
                    }

                    mainHandler.post { showDefault() }
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                    mainHandler.post { showDefault() }
                }
            }
        }

        private fun showBitmap(bitmap: Bitmap) {
            if (!surfaceReady) return
            try {
                currentBitmap?.recycle()
                currentBitmap = bitmap

                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val m = getMetrics()
                val sw = m.widthPixels.toFloat()
                val sh = m.heightPixels.toFloat()
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val sr = sw / sh
                val dw: Float
                val dh: Float
                if (ratio > sr) { dh = sh; dw = dh * ratio }
                else { dw = sw; dh = dw / ratio }
                val l = (sw - dw) / 2f
                val t = (sh - dh) / 2f
                canvas.drawBitmap(bitmap, null, RectF(l, t, l + dw, t + dh), null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "showBitmap error", e)
            }
        }

        private fun showDefault() {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.DKGRAY)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER
                }
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
