package com.wallpaperswitcher.engine

import com.wallpaperswitcher.util.AppLog

import com.wallpaperswitcher.util.LogText

import android.content.Context

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.graphics.Matrix

import android.net.Uri

import android.os.Build

import android.util.DisplayMetrics

import android.view.WindowManager
import com.wallpaperswitcher.data.ScaleMode
import java.util.LinkedHashMap

object BitmapUtils {

    private const val TAG = "BitmapUtils"
    /**
     * Bytes the first-decode stream may buffer so [java.io.InputStream.reset]
     * rewinds to the start after the bounds + EXIF passes. Large enough for a
     * full 64KB JPEG EXIF block plus the header scans; exceeding it only costs the
     * rare fallback read (see [decodeAndLearnSize]).
     */
    /**
     * How much of a file is buffered in memory for the EXIF/orientation and
     * bounds passes (see [decodeAndLearnSize]).
     *
     * 256 KB comfortably covers a JPEG's APPn/EXIF blocks *and* the SOF header
     * the bounds pass needs, without reading a whole 20 MP photo into memory. A
     * file whose header does not fit still decodes: the buffered path then hands
     * over to [decodeWithFreshStreams].
     */
    private const val HEAD_BUFFER_BYTES = 256 * 1024
    /** Upper bound of [metaCache] (entries are tiny: three ints + a string key). */
    private const val META_CACHE_MAX_ENTRIES = 512

    /**
     * "What did we learn about this media": pixel size + EXIF rotation, so a
     * repeated decode costs ONE provider read instead of three (bounds pass,
     * decode pass, EXIF pass). The engine re-decodes the same media over and
     * over (switches, prefetch, rotation) and on MIUI/HyperOS every provider
     * read shows up as "this app is accessing photos and videos". Access-ordered
     * LRU: 512 entries cover a whole shuffle deck, so a long session keeps
     * taking the fast path.
     */
    private val metaCache = object : LinkedHashMap<String, MediaMeta>(128, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MediaMeta>?
        ): Boolean = size > META_CACHE_MAX_ENTRIES
    }

    /**
     * Load a bitmap from URI with quality-preserving downsample.
     * Uses ARGB_8888 for full color depth.
     * Reads bounds and decodes in separate passes over fresh streams.
     */
    /**
     * @param scaleMode null = default behavior (same rule as FIT);
     * FIT targets the letterboxed on-screen rect, FILL/STRETCH target the
     * whole screen so large sources never get upscaled by the GPU.
     */
    fun loadBitmap(context: Context, uriStr: String, scaleMode: ScaleMode? = null): Bitmap? {
        return loadBitmap(context, uriStr, scaleMode, null, null, true, true)
    }

    /**
     * Variant that decodes against an explicit on-screen size instead of the
     * (possibly stale) window/display metrics. The wallpaper engine passes the
     * authoritative Surface dimensions here after a rotation, because on some
     * OEM ROMs (notably HyperOS tablets) `getScreenMetrics()` can still report
     * the previous orientation right after the surface has been recreated.
     *
     * @param targetW/targetH target on-screen pixels; null falls back to
     *        [getScreenMetrics].
     */
    fun loadBitmap(
        context: Context,
        uriStr: String,
        scaleMode: ScaleMode?,
        targetW: Int?,
        targetH: Int?,
        rotateMismatch: Boolean = true,
        rotateClockwise: Boolean = true,
        // Decode metadata stored with the media row (see WallpaperImage). When
        // present, decoding needs ONE media-library read even in a fresh process.
        knownWidth: Int = 0,
        knownHeight: Int = 0,
        knownRotationDegrees: Int = 0
    ): Bitmap? {
        val loaded = loadBitmapForEngine(
            context, uriStr, scaleMode, targetW, targetH,
            rotateMismatch, rotateClockwise,
            knownWidth, knownHeight, knownRotationDegrees
        ) ?: return null
        // The WallpaperManager path cannot rotate anything: bake the rotation
        // into the pixels (one full-size copy, but that path is not the hot one).
        return loaded.rotateCw?.let { bakeQuarterTurn(loaded.bitmap, it) } ?: loaded.bitmap
    }

    /**
     * Decode for the LIVE WALLPAPER engine: same pixels, but the FILL/STRETCH
     * "orientation mismatch" rotation is NOT baked into a copy - it is reported
     * back so the GL renderer can do it for free by permuting the quad's texture
     * coordinates ([com.wallpaperswitcher.engine.WallpaperGeometry.computeQuad]).
     *
     * That removes a full-screen ARGB allocation + copy (~18MB on a 1440x3200
     * screen) for every rotated image, which is exactly the media the feature
     * exists for.
     *
     * EXIF orientation is still baked here: `BitmapFactory` ignores EXIF, and
     * the renderer's quad rotation is about the *screen* mismatch, not the
     * camera metadata.
     */
    fun loadBitmapForEngine(
        context: Context,
        uriStr: String,
        scaleMode: ScaleMode?,
        targetW: Int?,
        targetH: Int?,
        rotateMismatch: Boolean = true,
        rotateClockwise: Boolean = true,
        knownWidth: Int = 0,
        knownHeight: Int = 0,
        knownRotationDegrees: Int = 0
    ): EngineImage? {
        return try {
            val uri = Uri.parse(uriStr)
            val metrics = getScreenMetrics(context)
            val screenW = targetW ?: metrics.widthPixels
            val screenH = targetH ?: metrics.heightPixels
            val known = synchronized(metaCache) { metaCache[uriStr] }
                ?: if (knownWidth > 0 && knownHeight > 0) {
                    MediaMeta(knownWidth, knownHeight, knownRotationDegrees).also {
                        synchronized(metaCache) { metaCache[uriStr] = it }
                    }
                } else {
                    null
                }
            if (known != null) {
                decodeWithKnownSize(
                    context, uri, uriStr, known, screenW, screenH,
                    scaleMode, rotateMismatch, rotateClockwise
                )
            } else {
                decodeAndLearnSize(
                    context, uri, uriStr, screenW, screenH,
                    scaleMode, rotateMismatch, rotateClockwise
                )
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "loadBitmapForEngine exception: ${e.message} for: ${LogText.short(uriStr)}", e)
            null
        }
    }

    /** A decoded image plus the quarter turn the GPU still has to apply. */
    data class EngineImage(val bitmap: Bitmap, val rotateCw: Boolean?)

    /** Bake [rotateCw] (true = 90° clockwise) into the pixels. */
    fun bakeQuarterTurn(bitmap: Bitmap, rotateCw: Boolean): Bitmap =
        rotateQuarterTurn(bitmap, rotateCw)

    /**
     * Fast path: this item's pixel size (and EXIF rotation) is already known, so
     * exactly ONE provider read is needed - no bounds pass, no `getType()`, no
     * separate EXIF stream.
     *
     * Media is re-decoded over and over (every switch, every prefetch, every
     * rotation), and on MIUI/HyperOS each provider read shows up as "this app is
     * accessing photos and videos". This path is what keeps normal switching
     * quiet: three reads per decode become one.
     */
    private fun decodeWithKnownSize(
        context: Context,
        uri: Uri,
        uriStr: String,
        meta: MediaMeta,
        screenW: Int,
        screenH: Int,
        scaleMode: ScaleMode?,
        rotateMismatch: Boolean,
        rotateClockwise: Boolean
    ): EngineImage? {
        // The orientation rule is applied by the caller (the quad for the live
        // wallpaper, the baked copy for the static path), but the decode size has
        // to know about it: a turned media fills/fits a different screen rect.
        val rotated = wantsQuarterTurn(
            meta.width, meta.height, screenW, screenH, rotateMismatch
        )
        val sample = chooseSample(meta.width, meta.height, screenW, screenH, scaleMode, rotated)
        // Decode straight to the size the screen can actually show (see
        // displayTargetSize): the old power-of-two-only rule kept a 12MP photo
        // resident as a 3024x4032 (48 MB) bitmap on a 1200x2608 screen.
        val target = displayTargetSize(
            meta.width, meta.height, screenW, screenH, scaleMode, rotated
        )
        val stream = context.contentResolver.openInputStream(uri)
        if (stream == null) {
            AppLog.e(TAG, "openInputStream returned null for: ${LogText.short(uriStr)}")
            return null
        }
        val decoded = stream.use {
            BitmapFactory.decodeStream(it, null, decodeOptions(sample, meta.width, target))
        }
        if (decoded == null) {
            AppLog.e(TAG, "decodeStream returned null for: ${LogText.short(uriStr)}")
            return null
        }
        decoded.density = Bitmap.DENSITY_NONE
        // The stored size can be stale when the file behind this URI was
        // replaced (album edit, a different image saved over it). The decoded
        // pixels are still valid, but the sample size - and the rotation we are
        // about to apply - may be wrong, so compare against what came out and
        // re-read properly once.
        //
        // When the target is smaller than the sampled source the decoder scales
        // to it, but its own sample-size quantisation can undershoot the request
        // by ~10-15% (real device: expected 1739x2608, decoded 1584x2376), so the
        // comparison has to be proportional - a genuinely stale row is off by a
        // factor, not by a few percent. Without the scaling the decoder delivers
        // the sampled size, which only needs rounding room.
        val scales = decodeScale(meta.width, sample, target?.get(0)) != null
        val expectedW = if (scales) target!![0] else (meta.width / sample).coerceAtLeast(1)
        val expectedH = if (scales) target!![1] else (meta.height / sample).coerceAtLeast(1)
        val toleranceW = if (scales) decodeSizeTolerance(expectedW) else 2
        val toleranceH = if (scales) decodeSizeTolerance(expectedH) else 2
        if (kotlin.math.abs(decoded.width - expectedW) > toleranceW ||
            kotlin.math.abs(decoded.height - expectedH) > toleranceH
        ) {
            AppLog.w(
                TAG,
                "Stale media metadata for ${LogText.short(uriStr)}: decoded ${decoded.width}x${decoded.height}, " +
                    "expected ${expectedW}x$expectedH - re-reading"
            )
            if (!decoded.isRecycled) decoded.recycle()
            synchronized(metaCache) { metaCache.remove(uriStr) }
            return decodeAndLearnSize(
                context, uri, uriStr, screenW, screenH,
                scaleMode, rotateMismatch, rotateClockwise
            )
        }
        val oriented = rotateByDegrees(decoded, uri, meta.rotationDegrees)
        return EngineImage(
            oriented,
            fillRotationFor(oriented, screenW, screenH, rotateMismatch, rotateClockwise)
        )
    }

    /**
     * Slow path: the first decode of this item in this process. Reads the bounds,
     * decodes, reads the EXIF orientation (JPEG only) and REMEMBERS the size +
     * rotation, so every later decode of the same item takes the fast path.
     */
    private fun decodeAndLearnSize(
        context: Context,
        uri: Uri,
        uriStr: String,
        screenW: Int,
        screenH: Int,
        scaleMode: ScaleMode?,
        rotateMismatch: Boolean,
        rotateClockwise: Boolean
    ): EngineImage? {
        // ONE provider read for all three passes (bounds, EXIF, decode).
        //
        // The first implementation rewound a markable stream between the passes,
        // which works on AOSP but NOT on HyperOS: the Redmi tablet's log showed
        // 603 of 695 first decodes falling back to `decodeWithFreshStreams`
        // ("Learned media metadata (3 reads)") because the platform's
        // ExifInterface(InputStream) consumed more than the 128 KB mark window
        // (the same files only need 39 KB on the emulator - measured), so the
        // rewind failed and the file was opened three times. MIUI/HyperOS counts
        // every open as "this app is accessing photos and videos", so that
        // fallback is exactly what the user notices.
        //
        // Now the head of the file is buffered in memory instead: EXIF and bounds
        // are read from that buffer, and the decode streams `buffer + rest of the
        // provider stream` (SequenceInputStream). Still one open, and no
        // mark/reset anywhere.
        val raw = context.contentResolver.openInputStream(uri)
        if (raw == null) {
            AppLog.e(TAG, "openInputStream returned null for: ${LogText.short(uriStr)}")
            return null
        }
        return raw.use { input ->
            // 1) Head of the file into memory (bounded; the EXIF block of a JPEG
            //    lives in the first few hundred KB, and a truncated tail is fine
            //    for both the EXIF and the bounds pass).
            val head = readHead(input, HEAD_BUFFER_BYTES)
            if (head.isEmpty()) {
                AppLog.e(TAG, "empty stream for: ${LogText.short(uriStr)}")
                return@use null
            }
            val jpeg = head.size >= 2 &&
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
            val rotationDegrees = if (jpeg) {
                readExifRotationDegrees(java.io.ByteArrayInputStream(head))
            } else {
                0
            }

            // 2) bounds
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(java.io.ByteArrayInputStream(head), null, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                // A header longer than the buffer (huge EXIF / unusual container):
                // the buffered path cannot describe it, so use the old
                // re-open-based flow instead of giving up.
                AppLog.d(
                    TAG,
                    "Buffered head unusable (${opts.outWidth}x${opts.outHeight}); " +
                        "falling back to fresh streams for: ${LogText.short(uriStr)}"
                )
                return@use decodeWithFreshStreams(
                    context, uri, uriStr, screenW, screenH,
                    scaleMode, rotateMismatch, rotateClockwise
                )
            }
            val srcW = opts.outWidth
            val srcH = opts.outHeight
            val rotated = wantsQuarterTurn(srcW, srcH, screenW, screenH, rotateMismatch)
            val sample = chooseSample(srcW, srcH, screenW, screenH, scaleMode, rotated)
            val target = displayTargetSize(srcW, srcH, screenW, screenH, scaleMode, rotated)

            // 3) the decode itself: the buffered head followed by the rest of the
            //    SAME provider stream (no second open, no rewind).
            val decoded = BitmapFactory.decodeStream(
                java.io.SequenceInputStream(java.io.ByteArrayInputStream(head), input),
                null,
                decodeOptions(sample, srcW, target)
            )
            if (decoded == null) {
                AppLog.e(TAG, "decodeStream returned null for: ${LogText.short(uriStr)}")
                return@use null
            }
            finishLearnedDecode(
                context, uri, uriStr, decoded, srcW, srcH, rotationDegrees,
                screenW, screenH, rotateMismatch, rotateClockwise,
                providerReads = 1
            )
        }
    }

    /**
     * Safety net for a file whose header does not fit in [HEAD_BUFFER_BYTES]
     * (a huge EXIF block or an unusual container): the pre-optimization flow with
     * one provider read per pass. Only reachable in that rare case - the common
     * path is [decodeAndLearnSize], which costs ONE provider read.
     */
    private fun decodeWithFreshStreams(
        context: Context,
        uri: Uri,
        uriStr: String,
        screenW: Int,
        screenH: Int,
        scaleMode: ScaleMode?,
        rotateMismatch: Boolean,
        rotateClockwise: Boolean
    ): EngineImage? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
        if (boundsStream == null) {
            AppLog.e(TAG, "openInputStream returned null for: ${LogText.short(uriStr)}")
            return null
        }
        boundsStream.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            AppLog.e(TAG, "decodeStream bounds invalid: ${opts.outWidth}x${opts.outHeight} for: ${LogText.short(uriStr)}")
            return null
        }
        val srcW = opts.outWidth
        val srcH = opts.outHeight
        val rotated = wantsQuarterTurn(srcW, srcH, screenW, screenH, rotateMismatch)
        val sample = chooseSample(srcW, srcH, screenW, screenH, scaleMode, rotated)
        val target = displayTargetSize(srcW, srcH, screenW, screenH, scaleMode, rotated)
        val exifDegrees = try {
            context.contentResolver.openInputStream(uri)?.use { readExifRotationDegrees(it) } ?: 0
        } catch (_: Exception) {
            0
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions(sample, srcW, target))
        }
        if (decoded == null) {
            AppLog.e(TAG, "decodeStream returned null for: ${LogText.short(uriStr)}")
            return null
        }
        return finishLearnedDecode(
            context, uri, uriStr, decoded, srcW, srcH, exifDegrees,
            screenW, screenH, rotateMismatch, rotateClockwise,
            providerReads = 3
        )
    }

    /**
     * Shared tail of the first-decode paths: bake the EXIF turn, decide the
     * screen-mismatch turn, remember the learned size/rotation and log how many
     * provider reads this cost.
     */
    private fun finishLearnedDecode(
        context: Context,
        uri: Uri,
        uriStr: String,
        decoded: Bitmap,
        srcW: Int,
        srcH: Int,
        rotationDegrees: Int,
        screenW: Int,
        screenH: Int,
        rotateMismatch: Boolean,
        rotateClockwise: Boolean,
        providerReads: Int
    ): EngineImage {
        decoded.density = Bitmap.DENSITY_NONE
        // BitmapFactory.decodeStream() ignores EXIF orientation, so camera photos
        // taken in portrait (EXIF 90/270) would otherwise display sideways on the
        // wallpaper - the orientation read above is baked in here.
        val oriented = rotateByDegrees(decoded, uri, rotationDegrees)
        // FILL/FIT/STRETCH rotate media whose orientation mismatches the screen
        // (see quarterTurnFor). The turn itself is left to the caller: the GPU
        // quad for the engine, a baked copy for the static path.
        val rotateCw = fillRotationFor(
            oriented, screenW, screenH, rotateMismatch, rotateClockwise
        )
        // Diagnostic: this path runs once per media (then the values are
        // remembered in the DB and every later decode takes the fast path).
        AppLog.d(TAG, "Learned media metadata ($providerReads read(s), now stored): ${LogText.short(uriStr)}")
        rememberMeta(context, uriStr, MediaMeta(srcW, srcH, rotationDegrees))
        return EngineImage(oriented, rotateCw)
    }

    /** Rewind a marked stream; false when the mark limit was exceeded. */
    /**
     * Read up to [limit] bytes from [input] into memory.
     *
     * The stream is NOT rewound afterwards: the caller keeps reading from where
     * this stopped (see the SequenceInputStream in [decodeAndLearnSize]), which is
     * what makes the whole decode cost a single provider read. A short read (EOF,
     * or a provider that returns chunks) is fine - the callers only need the head
     * of the file.
     */
    internal fun readHead(input: java.io.InputStream, limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(limit.coerceAtMost(64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val want = minOf(chunk.size, limit - total)
            val read = try {
                input.read(chunk, 0, want)
            } catch (_: Throwable) {
                -1
            }
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    /** Pixel size + EXIF rotation of one media item (in-memory, per process). */
    private class MediaMeta(val width: Int, val height: Int, val rotationDegrees: Int)

    /**
     * Remember what was just learned about [uriStr]: in memory for this process
     * and in the database so a later session (and the static applier) also takes
     * the one-read path. Both stores are best effort - a failure only costs one
     * extra probe the next time.
     */
    private fun rememberMeta(context: Context, uriStr: String, meta: MediaMeta) {
        synchronized(metaCache) {
            metaCache[uriStr] = meta
        }
        // Queued, never blocking: a `runBlocking` write here held up the decode
        // thread (and the switch waiting for it) whenever the database was busy.
        MediaMetaWriter.enqueue(context, uriStr, meta.width, meta.height, meta.rotationDegrees)
    }

    /**
     * EXIF rotation in degrees from an already-open (markable) stream, or 0 when
     * unknown / not a JPEG. Reading it from the stream we already hold is what
     * keeps the first decode of a media down to ONE provider read - see
     * [decodeAndLearnSize].
     */
    private fun readExifRotationDegrees(input: java.io.InputStream): Int {
        val orientation = try {
            android.media.ExifInterface(input).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        }
        return when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    /** Rotate a decoded bitmap by the EXIF degrees (0 = unchanged). */
    private fun rotateByDegrees(bitmap: Bitmap, uri: Uri, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        return try {
            val matrix = Matrix()
            matrix.postRotate(degrees.toFloat())
            // Exact 90/180/270 rotations are pixel-exact: filter=false skips
            // the unnecessary interpolation pass (faster, less memory churn).
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            rotated
        } catch (t: Throwable) {
            AppLog.e(TAG, "EXIF rotation failed: ${LogText.short(uri.toString())}", t)
            bitmap
        }
    }

    /**
     * Power-of-two decode sample for a [srcW]x[srcH] source shown with
     * [scaleMode] on a [screenW]x[screenH] screen. Pure maths, so it can be
     * reused for the cached-size fast path (see [decodeWithKnownSize]).
     */
    private fun chooseSample(
        srcW: Int,
        srcH: Int,
        screenW: Int,
        screenH: Int,
        scaleMode: ScaleMode?,
        rotated: Boolean = false
    ): Int {
        if (srcW <= 0 || srcH <= 0 || screenW <= 0 || screenH <= 0) return 1
        val srcLong = maxOf(srcW, srcH)
        // When the orientation rule turns this media 90°, the screen rect it has
        // to fill/fit is measured against the SWAPPED source dims (see
        // [quarterTurnFor]). Ignoring that over-decoded FILL (the unrotated cover
        // of a mismatched source is always the bigger one) and under-sized FIT
        // (the turned media is displayed larger), so both branches below use the
        // dims that actually end up on screen.
        val (spanW, spanH) = displaySpan(srcW, srcH, rotated)
        // The on-screen region the media occupies, in screen pixels:
        // - FIT: the letterboxed rect (never enlarges the source)
        // - FILL / STRETCH: the whole screen
        // - null (static wallpaper): same rule as FIT
        val fitScale = minOf(screenW.toFloat() / spanW, screenH.toFloat() / spanH)
        val (regionW, regionH) = when (scaleMode) {
            ScaleMode.FILL, ScaleMode.STRETCH -> screenW to screenH
            else -> {
                (srcW * fitScale).toInt().coerceAtLeast(1) to
                    (srcH * fitScale).toInt().coerceAtLeast(1)
            }
        }
        val targetLong = maxOf(regionW, regionH).coerceAtLeast(1)
        var sample = 1
        if (scaleMode == ScaleMode.FILL || scaleMode == ScaleMode.STRETCH) {
            // FILL/STRETCH spans the whole screen and can crop part of the
            // source, so the visible pixels must decode near 1:1 with the
            // screen. Choose the largest power-of-two sample that avoids GPU
            // upscaling (the old 75%-of-screen rule could magnify the CROPPED
            // axis 1.6x+, which looked soft); the memory bound below caps
            // monster sources at 4096px.
            val coverScale = maxOf(screenW.toFloat() / spanW, screenH.toFloat() / spanH)
            val noUpscaleMaxSample = if (coverScale > 0f) 1f / coverScale else 1f
            while ((sample * 2).toFloat() <= noUpscaleMaxSample && srcLong / (sample * 2) >= 1) {
                sample *= 2
            }
        } else {
            // FIT / static: decode at the largest power-of-two sample whose
            // decoded long side stays AT OR ABOVE ~75% of the on-screen region
            // (minLong). Letterboxed media is shown smaller, so the GPU
            // magnifies at most ~1.33x and the sharpening shader covers it,
            // while typical images decode at roughly a QUARTER of the pixels of
            // a strict "never upscale" rule.
            val minLong = (targetLong * 3 / 4).coerceAtLeast(1)
            while (srcLong / (sample * 2) >= minLong && srcLong / (sample * 2) >= 1) {
                sample *= 2
            }
        }
        // Memory bound: never decode more than 4096px on the long side. If that
        // drops below the no-upscale target (very large sources), the residual
        // GPU upscale is kept minimal and the sharpening shader covers it.
        while (srcLong / sample > 4096 && sample * 2 <= srcLong) {
            sample *= 2
        }
        return sample
    }

    /**
     * The size this media is really DISPLAYED at, in pixels, or null when no
     * downscale is possible (the source is already at or below it).
     *
     * - FILL / STRETCH cover the whole screen: the target is the source scaled
     *   up until it covers the screen (the crop happens on the GPU quad);
     * - FIT and the static-wallpaper path are shown letterboxed: the target is
     *   the source scaled down to fit inside the screen.
     *
     * Nothing above that size can ever reach a pixel, but `inSampleSize` alone
     * can only halve, so a 12MP camera photo was decoded at its full 3024x4032
     * (48 MB) while a 1200x2608 screen can only ever show 1956x2608 of it
     * (20 MB) - and with the prefetch copy that difference is what drives the
     * measured ~215 MB peak PSS. [decodeOptions] asks the decoder for exactly
     * this size instead, so the extra pixels are never allocated (JPEG decodes
     * directly at the requested scale; other formats decode and scale in one
     * step).
     */
    internal fun displayTargetSize(
        srcW: Int,
        srcH: Int,
        screenW: Int,
        screenH: Int,
        scaleMode: ScaleMode?,
        rotated: Boolean = false
    ): IntArray? {
        if (srcW <= 0 || srcH <= 0 || screenW <= 0 || screenH <= 0) return null
        // Rotated media is measured against the swapped dims: FILL then covers
        // the screen with fewer source pixels (it used to decode the bigger of
        // the two covers for nothing), FIT keeps the turned, larger rect sharp.
        val (spanW, spanH) = displaySpan(srcW, srcH, rotated)
        val scale = when (scaleMode) {
            ScaleMode.FILL, ScaleMode.STRETCH ->
                maxOf(screenW.toFloat() / spanW, screenH.toFloat() / spanH)
            else -> minOf(screenW.toFloat() / spanW, screenH.toFloat() / spanH)
        }
        if (scale >= 1f) return null
        val w = (srcW * scale + 0.5f).toInt().coerceAtLeast(1)
        val h = (srcH * scale + 0.5f).toInt().coerceAtLeast(1)
        // Scaling both axes by the same factor never turns "smaller" into
        // "bigger", but guard against rounding surprises.
        if (w >= srcW && h >= srcH) return null
        return intArrayOf(w, h)
    }

    /**
     * Decode options for one media item. [target] (see [displayTargetSize]) is
     * handed to the decoder through the density pair, which is only used as a
     * scale carrier here: `inTargetDensity / inDensity` is the wanted factor.
     * The resulting bitmap's own density is reset to [Bitmap.DENSITY_NONE] by
     * the callers so no Canvas scales it a second time.
     *
     * [inDensity] must be the size the decoder delivers BEFORE that scale - the
     * source subsampled by [sample] - because BitmapFactory applies the density
     * factor *on top of* `inSampleSize` instead of instead of it: with
     * `inDensity = srcW` a 6048x8064 photo (sample 2, target 1956x2608) came out
     * at 978x1304, i.e. every picture was scaled by `sample` too far (found on a
     * real device as `Stale media metadata ... decoded 978x1304, expected
     * 1956x2608`, which also cost an extra decode plus three media reads per
     * switch).
     */
    private fun decodeOptions(
        sample: Int,
        srcW: Int,
        target: IntArray?
    ): BitmapFactory.Options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        val scale = decodeScale(srcW, sample, target?.get(0))
        if (scale != null) {
            inScaled = true
            inDensity = scale.first
            inTargetDensity = scale.second
        }
    }

    /**
     * Density pair (`inDensity to inTargetDensity`) that makes the decoder land
     * on [targetW], or null when no scaling is needed.
     *
     * Pure arithmetic, unit-tested: [inDensity] has to be the source AFTER
     * `inSampleSize` ([sample]), because BitmapFactory multiplies the two -
     * see [decodeOptions].
     */
    internal fun decodeScale(srcW: Int, sample: Int, targetW: Int?): Pair<Int, Int>? {
        if (targetW == null || targetW <= 0) return null
        val sampledW = (srcW / sample.coerceAtLeast(1)).coerceAtLeast(1)
        if (targetW >= sampledW) return null
        return sampledW to targetW
    }

    /**
     * Room [decodeWithKnownSize] gives the decoder's output size, in pixels.
     *
     * The decoder honours the requested (scaled) size only to within its own
     * sample-size quantisation: on the test tablet a request for 1739x2608 came
     * back as 1584x2376 (-8.9%), and 1738x2608 as 1499x2250 (-13.8%). Comparing
     * with a 2px tolerance there marked every such decode as "stale metadata"
     * and re-read the file three more times per switch. A genuinely stale row
     * differs by a factor (the file was replaced by a different image), so 20%
     * still catches it while tolerating the quantisation.
     */
    internal fun decodeSizeTolerance(expected: Int): Int = maxOf(4, expected / 5)

    /**
     * The one "方向与屏幕不符就转 90°" rule, shared by the image decode, the GL
     * renderer's quad, the GIF frame path and the static applier so the four can
     * never drift apart again.
     *
     * FILL/STRETCH rotate so a mismatched media covers the screen instead of
     * showing a thin perpendicular strip. FIT rotates too (on request): a
     * mismatched orientation fitted into the screen is limited by the screen's
     * SHORT side, so the turned media is displayed much larger while still being
     * fully visible - 1920x1080 on a 1200x2608 screen shows 1200x675 unrotated
     * but 1200x2133 rotated.
     *
     * @return true = turn 90° clockwise, false = counter-clockwise, null = leave
     *   it as it is (feature off, square media, or the orientation matches).
     */
    internal fun quarterTurnFor(
        srcW: Int,
        srcH: Int,
        screenW: Int,
        screenH: Int,
        rotateMismatch: Boolean,
        rotateClockwise: Boolean = true
    ): Boolean? {
        if (!rotateMismatch) return null
        if (screenW <= 0 || screenH <= 0 || srcW <= 0 || srcH <= 0) return null
        if (srcW == srcH) return null
        val imageLandscape = srcW > srcH
        val screenLandscape = screenW > screenH
        if (imageLandscape == screenLandscape) return null
        return rotateClockwise
    }

    /** [quarterTurnFor] without the direction: does this media need a turn? */
    internal fun wantsQuarterTurn(
        srcW: Int,
        srcH: Int,
        screenW: Int,
        screenH: Int,
        rotateMismatch: Boolean
    ): Boolean = quarterTurnFor(srcW, srcH, screenW, screenH, rotateMismatch) != null

    /**
     * The dims that actually land on screen: swapped when the orientation rule
     * turns the media 90°.
     *
     * One helper for the three places that have to agree about which axis is the
     * long one - the image decode target ([displayTargetSize]), the video decode
     * cap in `WallpaperRenderer.startVideo()` and the GIF frame sizing - because
     * all three had their own copy and the video one silently drifted (a 4K clip
     * was decoded at 1500x842 for a screen that needed 1200x2133).
     */
    internal fun displaySpan(srcW: Int, srcH: Int, rotated: Boolean): Pair<Int, Int> =
        if (rotated) srcH to srcW else srcW to srcH

    /**
     * Does the orientation rule want this image turned a quarter turn? Returns
     * true for 90° clockwise, false for 90° counter-clockwise, null when the
     * image already matches the screen (or the feature is off).
     *
     * Pure decision, so the caller can either bake it into the pixels (static
     * WallpaperManager path) or hand it to the GL renderer (live wallpaper, no
     * copy at all).
     */
    internal fun fillRotationFor(
        bitmap: Bitmap,
        screenW: Int,
        screenH: Int,
        rotateMismatch: Boolean,
        rotateClockwise: Boolean
    ): Boolean? = quarterTurnFor(
        bitmap.width, bitmap.height, screenW, screenH, rotateMismatch, rotateClockwise
    )

    /** Actually rotate the pixels a quarter turn, recycling the source. */
    private fun rotateQuarterTurn(bitmap: Bitmap, clockwise: Boolean): Bitmap {
        return try {
            val matrix = Matrix()
            matrix.postRotate(if (clockwise) 90f else -90f)
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            rotated
        } catch (t: Throwable) {
            AppLog.e(TAG, "FILL orientation rotate failed", t)
            bitmap
        }
    }

    /**
     * Cached screen metrics: [getScreenMetrics] is called for every decode and
     * the display size only changes on rotation / density change, so the
     * WindowManager round trip is not worth repeating.
     */
    @Volatile private var metricsCache: DisplayMetrics? = null
    @Volatile private var metricsCacheKey = Int.MIN_VALUE

    /**
     * Get screen metrics, compatible with API 30+.
     *
     * Crash-proofed for the live-wallpaper process: some devices (e.g.
     * Xiaomi/HyperOS tablets) can throw or return zero-size bounds from
     * `WindowManager.currentWindowMetrics` when the context has no Activity
     * window, which previously crashed the wallpaper engine right after the
     * wallpaper was applied. Falls back to the default display and finally to
     * the app resource metrics, so it never throws.
     */
    fun getScreenMetrics(context: Context): DisplayMetrics {
        val configuration = context.resources.configuration
        val key = configuration.orientation * 1_000_003 +
            configuration.screenWidthDp * 1_009 +
            configuration.screenHeightDp * 31 +
            configuration.densityDpi
        metricsCache?.let { cached -> if (metricsCacheKey == key) return cached }
        val displayMetrics = DisplayMetrics()
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        displayMetrics.widthPixels = bounds.width()
                        displayMetrics.heightPixels = bounds.height()
                    }
                }
                if (displayMetrics.widthPixels <= 0 || displayMetrics.heightPixels <= 0) {
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(displayMetrics)
                }
            }
        } catch (_: Exception) {
            // Ignore and fall through to the resource-based fallback below.
        }
        if (displayMetrics.widthPixels <= 0 || displayMetrics.heightPixels <= 0) {
            // The wallpaper engine has no Activity window; the app resource
            // metrics are a reliable last resort.
            val res = context.applicationContext.resources.displayMetrics
            displayMetrics.widthPixels = res.widthPixels
            displayMetrics.heightPixels = res.heightPixels
        }
        metricsCache = displayMetrics
        metricsCacheKey = key
        return displayMetrics
    }
}
