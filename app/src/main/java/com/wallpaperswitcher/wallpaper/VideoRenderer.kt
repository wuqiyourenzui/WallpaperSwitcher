package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.ScaleMode

/**
 * Video renderer using MediaPlayer.setDisplay().
 * MediaPlayer handles hardware decoding and renders directly to the
 * wallpaper Surface via SurfaceHolder. No EGL, no GL, no SurfaceTexture.
 *
 * Supports original quality, original frame rate, FIT/FILL/STRETCH.
 */
class VideoRenderer(
    private val context: Context,
    private val holder: SurfaceHolder
) {
    companion object {
        private const val TAG = "VideoRenderer"
    }

    private var player: MediaPlayer? = null

    @Volatile var isPlaying = false; private set
    var durationMs: Long = 0L; private set
    var videoWidth: Int = 0; private set
    var videoHeight: Int = 0; private set

    fun start(uriStr: String) {
        try {
            val surface = holder.surface
            if (surface == null || !surface.isValid) {
                Log.e(TAG, "Surface not valid")
                return
            }

            val mp = MediaPlayer()
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                isPlaying = false
                false
            }
            mp.setOnPreparedListener { p ->
                videoWidth = p.videoWidth
                videoHeight = p.videoHeight
                durationMs = p.duration.toLong()
                p.isLooping = true
                p.start()
                isPlaying = true
                Log.d(TAG, "Video started: ${videoWidth}x${videoHeight}, duration=${durationMs}ms")
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    isPlaying = true
                    Log.d(TAG, "Video rendering started")
                }
                false
            }
            mp.setOnCompletionListener {
                it.seekTo(0)
                it.start()
            }

            mp.setDataSource(context, Uri.parse(uriStr))
            mp.setDisplay(holder)
            mp.setVolume(0f, 0f)
            mp.prepareAsync()

            player = mp
            Log.d(TAG, "MediaPlayer configured for: $uriStr")
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}", e)
            release()
        }
    }

    fun pause() {
        try { player?.pause() } catch (_: Exception) {}
    }

    fun resume() {
        try { player?.start() } catch (_: Exception) {}
    }

    fun release() {
        isPlaying = false
        val p = player
        player = null
        if (p != null) {
            try { p.setSurface(null) } catch (_: Exception) {}
            try { if (p.isPlaying) p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
    }
}
