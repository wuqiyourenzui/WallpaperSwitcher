package com.wallpaperswitcher.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.wallpaperswitcher.data.ScaleMode

object BitmapUtils {

    private const val TAG = "BitmapUtils"

    /**
     * Load a bitmap from URI with quality-preserving downsample.
     * Only downsamples if image exceeds 4x screen dimensions.
     * Uses ARGB_8888 for full color depth.
     * Single ContentResolver.openInputStream call (reads bounds + decodes in one pass).
     */
    /**
     * @param scaleMode null = default behavior (screen resolution cap);
     * FIT keeps the screen cap, FILL/STRETCH use a higher ceiling so large
     * sources stay sharp when the wallpaper magnifies them.
     */
    fun loadBitmap(context: Context, uriStr: String, scaleMode: ScaleMode? = null): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)

            // Open InputStream twice: once for bounds, once for decode.
            // Using openInputStream avoids fd position issues with decodeFileDescriptor.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream1 = context.contentResolver.openInputStream(uri)
            if (stream1 == null) {
                Log.e(TAG, "openInputStream returned null for: $uriStr")
                return null
            }
            stream1.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.e(TAG, "decodeStream bounds invalid: ${opts.outWidth}x${opts.outHeight} for: $uriStr")
                return null
            }

            // Decode to at most the screen resolution: the rendered wallpaper
            // is displayed at screen size, so this keeps the picture identical
            // to the source on 2K/4K displays while avoiding full-size decode
            // of far larger photos (memory + power savings).
            var sample = 1
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            val screenMax = maxOf(
                getScreenMetrics(context).widthPixels,
                getScreenMetrics(context).heightPixels
            )
            val decodeCap = when (scaleMode) {
                ScaleMode.FILL, ScaleMode.STRETCH ->
                    minOf(screenMax * 2, 4096).coerceAtLeast(2560)
                else -> minOf(screenMax, 3200).coerceAtLeast(1920)
            }
            while (maxDim / sample > decodeCap) sample *= 2

            // Second pass: decode actual bitmap from a fresh stream
            val stream2 = context.contentResolver.openInputStream(uri)
            if (stream2 == null) {
                Log.e(TAG, "openInputStream (2nd) returned null for: $uriStr")
                return null
            }
            val bitmap = stream2.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
            if (bitmap == null) {
                Log.e(TAG, "decodeStream returned null for: $uriStr")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap exception: ${e.message} for: $uriStr", e)
            null
        }
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
