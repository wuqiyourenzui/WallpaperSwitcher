package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.ScaleMode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video renderer using MediaPlayer (timing) + MediaMetadataRetriever (frames).
 *
 * - MediaPlayer handles looping and timing (muted)
 * - MediaMetadataRetriever extracts frames via OPTION_CLOSEST
 * - Dedicated extraction thread runs at video fps rate
 * - Frame buffer (8 slots) decouples extraction from rendering
 * - Main thread renders via Canvas (supports FIT/FILL/STRETCH)
 */
class VideoRenderer(
    private val context: Context,
    private val holder: SurfaceHolder,
    private val mainHandler: Handler
) {
    companion object {
        private const val TAG = "VideoRenderer"
    }

    private var player: MediaPlayer? = null
    private var retriever: MediaMetadataRetriever? = null
    private var extractThread: Thread? = null

    @Volatile var isPlaying = false; private set
    @Volatile private var stopped = false
    var durationMs: Long = 0L; private set
    var videoWidth: Int = 0; private set
    var videoHeight: Int = 0; private set
    var fps: Int = 30; private set

    private val frameBuffer = LinkedBlockingQueue<Bitmap>(8)
    private val renderLock = Object()
    private val destRect = RectF()

    private var renderRunnable: Runnable? = null

    fun start(uriStr: String, scaleMode: ScaleMode, screenW: Float, screenH: Float) {
        stopped = false
        val uri = Uri.parse(uriStr)

        // MediaPlayer for timing + looping
        try {
            val mp = MediaPlayer()
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                isPlaying = false
                false
            }
            mp.setDataSource(context, uri)
            mp.setVolume(0f, 0f)
            mp.isLooping = true
            mp.prepare()
            durationMs = mp.duration.toLong()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed: ${e.message}")
        }

        // MediaMetadataRetriever for frame extraction
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            retriever = r

            videoWidth = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            videoHeight = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val fpsStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            if (fpsStr != null && fpsStr > 0) fps = fpsStr.toInt().coerceIn(15, 60)

            Log.d(TAG, "Video: ${videoWidth}x${videoHeight} @ ${fps}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Retriever failed: ${e.message}")
            release()
            return
        }

        isPlaying = true

        // Start extraction thread
        extractThread = Thread({
            extractLoop()
        }, "VideoExtract").also { it.start() }

        // Start render timer on main thread (independent of extraction)
        val renderInterval = (1000L / fps).coerceAtLeast(16L)
        val runnable = object : Runnable {
            override fun run() {
                if (stopped || !isPlaying) return
                renderFromBuffer(scaleMode, screenW, screenH)
                mainHandler.postDelayed(this, renderInterval)
            }
        }
        renderRunnable = runnable
        mainHandler.postDelayed(runnable, renderInterval)
    }

    fun pause() {
        isPlaying = false
        try { player?.pause() } catch (_: Exception) {}
    }

    fun resume() {
        isPlaying = true
        try { player?.start() } catch (_: Exception) {}
    }

    fun release() {
        stopped = true
        isPlaying = false

        renderRunnable?.let { mainHandler.removeCallbacks(it) }
        renderRunnable = null

        try { player?.setSurface(null) } catch (_: Exception) {}
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null

        try { retriever?.release() } catch (_: Exception) {}
        retriever = null

        extractThread?.interrupt()
        extractThread = null

        while (frameBuffer.isNotEmpty()) { try { frameBuffer.poll()?.recycle() } catch (_: Exception) {} }

        synchronized(renderLock) { renderLock.notifyAll() }
    }

    /**
     * Extraction loop on dedicated thread.
     * Extracts frames at video fps rate, puts into buffer.
     * Render timer on main thread consumes from buffer independently.
     */
    private fun extractLoop() {
        val r = retriever ?: return
        val intervalMs = (1000L / fps).coerceAtLeast(16L)
        var posMs = 0L

        while (!stopped && !Thread.interrupted()) {
            if (!isPlaying) {
                try { Thread.sleep(50) } catch (_: Exception) { break }
                continue
            }

            val frameStart = System.currentTimeMillis()

            // Extract frame — OPTION_CLOSEST_SYNC returns nearest keyframe (fast)
            // OPTION_CLOSEST decodes from keyframe to exact frame (slow, 10-50ms)
            // For wallpaper, smooth motion > frame accuracy
            val frame = try {
                r.getFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) { null }

            if (frame != null) {
                // Drop old frames if buffer full
                while (frameBuffer.remainingCapacity() <= 0) {
                    try { frameBuffer.poll()?.recycle() } catch (_: Exception) {}
                }
                frameBuffer.offer(frame)
            }

            // Advance position
            posMs += intervalMs
            if (posMs >= durationMs) posMs = 0L

            // Frame pacing
            val elapsed = System.currentTimeMillis() - frameStart
            val sleepMs = intervalMs - elapsed
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs) } catch (_: Exception) { break }
            }
        }
    }

    private fun renderFromBuffer(scaleMode: ScaleMode, screenW: Float, screenH: Float) {
        if (stopped) return
        val frame = frameBuffer.poll() ?: return
        try {
            val canvas = holder.lockCanvas() ?: return
            canvas.drawColor(Color.BLACK)
            calcDestRect(frame.width.toFloat(), frame.height.toFloat(), screenW, screenH, scaleMode)
            canvas.drawBitmap(frame, null, destRect, null)
            holder.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {}
        frame.recycle()
    }

    private fun calcDestRect(bw: Float, bh: Float, sw: Float, sh: Float, scaleMode: ScaleMode) {
        when (scaleMode) {
            ScaleMode.FIT -> {
                val r = bw / bh; val sr = sw / sh
                val dw: Float; val dh: Float
                if (r > sr) { dw = sw; dh = dw / r } else { dh = sh; dw = dh * r }
                destRect.set((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f)
            }
            ScaleMode.FILL -> {
                val r = bw / bh; val sr = sw / sh
                val dw: Float; val dh: Float
                if (r < sr) { dw = sw; dh = dw / r } else { dh = sh; dw = dh * r }
                destRect.set((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f)
            }
            ScaleMode.STRETCH -> destRect.set(0f, 0f, sw, sh)
        }
    }
}
