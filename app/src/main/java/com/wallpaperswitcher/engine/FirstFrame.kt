package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.util.LogText
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import com.wallpaperswitcher.util.AppLog

/**
 * First-frame extraction shared by the live engine (video/GIF recovery
 * fallback) and the static applier (a static wallpaper cannot animate, so
 * videos and GIFs are written as their first frame).
 *
 * The two callers used to carry their own copies and they had drifted apart:
 * only the applier applied the container's rotation metadata, so a portrait
 * video showed sideways in static mode. One implementation now serves both.
 */
object FirstFrame {

    private const val TAG = "FirstFrame"

    /**
     * First frame of the video at [uriStr], with its rotation metadata applied
     * and the long side capped to the screen (a 4K/8K frame is ~33MB of ARGB and
     * is displayed at screen size anyway).
     *
     * [positionUs] selects which frame to extract: 0 (the default) is the file's
     * first frame, which is what a fresh video needs. When a video resumes at a
     * remembered position (see the lock-release path in LiveWallpaperService) the
     * still frame shown during the rebuild must come from THAT position, or the
     * rebuild would flash the beginning of the clip before continuing.
     */
    fun video(context: Context, uriStr: String, positionUs: Long = 0L): Bitmap? {
        var retriever: android.media.MediaMetadataRetriever? = null
        return try {
            retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.parse(uriStr))
            val metrics = BitmapUtils.getScreenMetrics(context)
            val screenMax = maxOf(metrics.widthPixels, metrics.heightPixels)
            val cap = minOf(screenMax, 3200).coerceAtLeast(1920)
            // API 27 (O_MR1) introduced the scaled extraction: a 4K/8K video frame
            // is ~33MB of ARGB at full size and is then thrown away by the
            // downscale below, so ask for the small one when the coded size is
            // known (a low-memory device could otherwise OOM on a single 8K
            // frame). On API 26 the call does not exist at all, so the full-size
            // frame is fetched and scaled below instead - keep the runtime guard
            // even though the lint annotation below makes it look redundant.
            val codedW = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val codedH = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val codedMax = maxOf(codedW, codedH)
            val scaledRequest = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                codedMax > cap && codedW > 0 && codedH > 0
            ) {
                val scale = cap.toFloat() / codedMax
                (codedW * scale).toInt().coerceAtLeast(1) to
                    (codedH * scale).toInt().coerceAtLeast(1)
            } else {
                null
            }
            // Same landmarks as before: the requested position, else the file's
            // first frame, else one second in (some containers have no frame at 0).
            @androidx.annotation.RequiresApi(Build.VERSION_CODES.O_MR1)
            fun frameAt(timeUs: Long): Bitmap? = scaledRequest?.let { (w, h) ->
                try {
                    retriever!!.getScaledFrameAtTime(
                        timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h
                    )
                } catch (_: Throwable) {
                    // Any retriever that dislikes the scaled call falls back to the
                    // full-size one below.
                    null
                }
            } ?: retriever!!.getFrameAtTime(
                timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            val frame = if (positionUs > 0L) {
                frameAt(positionUs)
            } else {
                frameAt(0L) ?: frameAt(1_000_000L) ?: retriever.frameAtTime
            } ?: return null
            val maxDim = maxOf(frame.width, frame.height)
            var result = if (maxDim > cap) {
                val scale = cap.toFloat() / maxDim
                val w = (frame.width * scale).toInt().coerceAtLeast(1)
                val h = (frame.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
                if (scaled !== frame) frame.recycle()
                scaled
            } else {
                frame
            }
            // getFrameAtTime() returns the frame in the stored (sensor)
            // orientation; apply the rotation metadata so portrait-recorded
            // videos display upright.
            val rotation = try {
                retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
            } catch (_: Exception) {
                0
            }
            if (rotation != 0 && rotation % 90 == 0) {
                try {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotation.toFloat())
                    val rotated = Bitmap.createBitmap(
                        result, 0, 0, result.width, result.height, matrix, false
                    )
                    if (rotated !== result && !result.isRecycled) result.recycle()
                    result = rotated
                } catch (t: Throwable) {
                    AppLog.e(TAG, "videoFrame rotation failed: ${LogText.short(uriStr)}", t)
                }
            }
            result
        } catch (e: Throwable) {
            AppLog.e(TAG, "videoFrame failed: ${LogText.short(uriStr)}", e)
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /**
     * First frame of the GIF/animated image at [uriStr], rendered at most at
     * screen resolution so a very large frame cannot OOM the wallpaper.
     */
    fun gif(context: Context, uriStr: String): Bitmap? {
        val uri = Uri.parse(uriStr)
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
                    val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
                    val metrics = BitmapUtils.getScreenMetrics(context)
                    val screenMax = maxOf(metrics.widthPixels, metrics.heightPixels)
                    val cap = minOf(screenMax, 3200).coerceAtLeast(1920)
                    val scale = if (maxOf(intrinsicW, intrinsicH) > cap) {
                        cap.toFloat() / maxOf(intrinsicW, intrinsicH)
                    } else {
                        1f
                    }
                    val w = (intrinsicW * scale).toInt().coerceAtLeast(1)
                    val h = (intrinsicH * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp)
                    if (scale < 1f) cv.scale(scale, scale)
                    drawable.draw(cv)
                    bmp
                } finally {
                    try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
            } else {
                BitmapUtils.loadBitmap(context, uriStr)
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "gifFirstFrame failed: ${LogText.short(uriStr)}", e)
            null
        }
    }
}
