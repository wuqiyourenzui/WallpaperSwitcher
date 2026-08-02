package com.wallpaperswitcher.engine

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wallpaper engine - sets system wallpaper via WallpaperManager.
 * Used for "Set as Wallpaper" feature.
 */
class WallpaperEngine(private val context: Context) {

    private val wallpaperManager = WallpaperManager.getInstance(context)
    private val db = AppDatabase.getInstance(context)

    /**
     * Set wallpaper for the given image URI.
     */
    suspend fun setWallpaperForImage(uriString: String, scaleMode: ScaleMode = ScaleMode.FIT): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                when (scaleMode) {
                    ScaleMode.FIT -> {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            wallpaperManager.setStream(stream)
                        }
                    }
                    ScaleMode.FILL, ScaleMode.STRETCH -> {
                        val bitmap = decodeAndScale(uri, scaleMode)
                        if (bitmap != null) {
                            wallpaperManager.setBitmap(bitmap)
                            bitmap.recycle()
                        } else {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                wallpaperManager.setStream(stream)
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "setWallpaperForImage failed", e)
                false
            }
        }
    }

    private fun decodeAndScale(uri: Uri, scaleMode: ScaleMode): Bitmap? {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val imgW = opts.outWidth; val imgH = opts.outHeight
            if (imgW <= 0 || imgH <= 0) return null

            var sample = 1
            while (imgW / sample > screenW * 2 || imgH / sample > screenH * 2) sample *= 2

            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
                })
            } ?: return null

            when (scaleMode) {
                ScaleMode.FILL -> {
                    val imgRatio = decoded.width.toFloat() / decoded.height.toFloat()
                    val screenRatio = screenW.toFloat() / screenH.toFloat()
                    val cropW: Int; val cropH: Int
                    if (imgRatio > screenRatio) { cropH = decoded.height; cropW = (cropH * screenRatio).toInt() }
                    else { cropW = decoded.width; cropH = (cropW / screenRatio).toInt() }
                    val cropX = ((decoded.width - cropW) / 2).coerceAtLeast(0)
                    val cropY = ((decoded.height - cropH) / 2).coerceAtLeast(0)
                    val cropped = Bitmap.createBitmap(decoded, cropX, cropY, cropW.coerceAtMost(decoded.width - cropX), cropH.coerceAtMost(decoded.height - cropY))
                    val scaled = Bitmap.createScaledBitmap(cropped, screenW, screenH, true)
                    if (cropped !== scaled) cropped.recycle()
                    if (decoded !== cropped) decoded.recycle()
                    scaled
                }
                ScaleMode.STRETCH -> {
                    val scaled = Bitmap.createScaledBitmap(decoded, screenW, screenH, true)
                    if (decoded !== scaled) decoded.recycle()
                    scaled
                }
                else -> decoded
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeAndScale failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "WallpaperEngine"
    }
}
