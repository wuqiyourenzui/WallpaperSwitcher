package com.wallpaperswitcher.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object BitmapUtils {

    /**
     * Load a bitmap from URI with quality-preserving downsample.
     * Only downsamples if image exceeds 4x screen dimensions.
     * Uses ARGB_8888 for full color depth.
     */
    fun loadBitmap(context: Context, uriStr: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val metrics = getScreenMetrics(context)
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            // Only downsample if image is more than 4x screen size
            var sample = 1
            while (opts.outWidth / sample > screenW * 4 || opts.outHeight / sample > screenH * 4) sample *= 2

            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        } catch (_: Exception) { null }
    }

    /**
     * Get screen metrics, compatible with API 30+.
     */
    fun getScreenMetrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                it.widthPixels = bounds.width()
                it.heightPixels = bounds.height()
            } else {
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
            }
        }
    }
}
