package com.wallpaperswitcher.wallpaper

import android.content.res.AssetFileDescriptor
import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "WallpaperRenderer"

/**
 * 视频解码循环（从 [WallpaperRenderer] 拆分到独立文件；逐字搬移，行为不变）。
 *
 * 仍作为渲染器的 internal 扩展函数工作，共享其视频/GL/音频状态；后续可进一步
 * 收敛为真正的协作者类。视频回归见 emulator 的 video_only 夹具。
 */
internal fun WallpaperRenderer.decodeLoop(uriStr: String, scaleMode: ScaleMode, gen: Int, handler: Handler) {
        // Use local variables to avoid race with new decode thread's instance fields.
        var localExtractor: MediaExtractor? = null
        var localDecoder: MediaCodec? = null
        var localAfd: AssetFileDescriptor? = null
        // Reset the health-monitor timestamp for this video: lastVideoFrameAt
        // is shared across videos, and a stale value from the previous video
        // made the engine misjudge a healthy video as stalled.
        lastVideoFrameAt = 0L
        try {
            // Outer loop: restart the codec cleanly when the video loops.
            // Flushing and re-feeding an in-place codec can crash some hardware
            // decoders during repeat playback, which is how the engine died
            // while just playing (no switch involved) in the captured logs.
            // Prefer an AssetFileDescriptor: MediaExtractor streaming through
            // a ContentResolver on cloud-mounted SAF URIs (e.g. PikPak) can
            // block for tens of seconds, which made the engine look dead.
            // Opening a cloud-hosted document can itself block for tens of
            // seconds, so run it on a helper thread with a 15s timeout:
            // switching must never be stuck on an unresponsive provider.
            // The descriptor is opened ONCE and reused across loop passes so
            // cloud files are not re-opened on every playback loop.
            val openResult = java.util.concurrent.atomic.AtomicReference<AssetFileDescriptor?>(null)
            val openError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            // Set when the 15s timeout fires while the helper thread is still
            // blocked in openAssetFileDescriptor: if it later returns, the
            // descriptor is closed immediately instead of leaking.
            val abandonOpen = java.util.concurrent.atomic.AtomicBoolean(false)
            val openThread = Thread({
                try {
                    val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")
                    if (abandonOpen.get()) {
                        try { afd?.close() } catch (_: Exception) {}
                    } else {
                        openResult.set(afd)
                        // Same race as openAudioDescriptor: the 15s timeout can land
                        // between the check and this publish, and the waiting caller
                        // then reports failure without ever seeing the descriptor.
                        // Close it ourselves unless the caller won the handoff.
                        if (abandonOpen.get() && openResult.compareAndSet(afd, null)) {
                            try { afd?.close() } catch (_: Exception) {}
                        }
                    }
                } catch (t: Throwable) {
                    openError.set(t)
                }
            }, "VideoOpen").apply {
                // Never keep the process alive because a cloud provider is
                // unresponsive.
                isDaemon = true
                start()
            }
            try {
                openThread.join(15_000)
            } catch (_: InterruptedException) {
                abandonOpen.set(true)
                openThread.interrupt()
                Thread.currentThread().interrupt()
                return
            }
            if (openThread.isAlive) {
                abandonOpen.set(true)
                openThread.interrupt()
                AppLog.e(TAG, "Timed out opening video stream: ${LogText.short(uriStr)}")
                if (videoGeneration.get() == gen) {
                    isVideoPlaying = false
                    onVideoStartFailed?.invoke()
                }
                return
            }
            val openErr = openError.get()
            if (openErr != null) {
                AppLog.e(TAG, "Failed to open video stream: ${LogText.short(uriStr)}", openErr)
                if (videoGeneration.get() == gen) {
                    isVideoPlaying = false
                    onVideoStartFailed?.invoke()
                }
                return
            }
            val afd = openResult.get()
            if (afd == null) {
                AppLog.e(TAG, "Cannot open video stream: ${LogText.short(uriStr)}")
                if (videoGeneration.get() == gen) {
                    isVideoPlaying = false
                    onVideoStartFailed?.invoke()
                }
                return
            }
            localAfd = afd
            // Some pickers (Google Photos / third-party SAF providers on
            // non-Xiaomi devices) hand out descriptors with UNKNOWN length or
            // that are not seekable; MediaExtractor then fails and the video
            // stays black. Fall back to copying the stream into the app cache
            // and decoding from that seekable file.
            var copiedVideoPath: String? = null
            var errorPasses = 0
            var giveUp = false
            // ---- Warm session, reused across loop passes of the SAME file ----
            //
            // The old code created a MediaCodec + MediaExtractor + a fresh
            // SurfaceTexture on EVERY playback pass, so a 5s clip paid a full
            // codec create/configure/start (~30-80ms) and a GL rebuild at every
            // loop point - visible as a hitch (and, with the old framebuffer
            // clear, a black flash). A loop restart now only flushes the codec
            // and rewinds the extractor; a switch to a DIFFERENT file still
            // rebuilds everything (a codec cannot change format).
            var sessionExtractor: MediaExtractor? = null
            var sessionDecoder: MediaCodec? = null
            var reuseSession = false
            while (videoGeneration.get() == gen && !Thread.interrupted() && !giveUp) {
                // --- Setup MediaExtractor ---
                // On a loop restart the warm extractor is kept AS IS: the source
                // is already attached and only needs a rewind, which the codec
                // block below does with seekTo(0). Re-calling setDataSource on an
                // extractor that already reached EOF threw
                // `IOException: Failed to instantiate extractor` on the tablet,
                // which turned every loop point into a first-frame fallback +
                // recovery switch - i.e. the clip never looped.
                val ext = sessionExtractor ?: MediaExtractor()
                localExtractor = ext
                if (sessionExtractor !== ext) {
                    // Fresh session: attach the source. Use the descriptor's
                    // offset/length - cloud-hosted or container-backed documents
                    // can expose a non-zero start offset, and decoding from the
                    // beginning would fail or read the wrong bytes.
                    try {
                        val existingCopy = copiedVideoPath
                        if (existingCopy != null) {
                            // A previous attempt already copied this stream into
                            // the cache (the descriptor is not seekable): use that
                            // file instead of copying it again.
                            ext.setDataSource(existingCopy)
                        } else if (afd.length > 0L) {
                            ext.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        } else {
                            // length == UNKNOWN_LENGTH: let the framework fstat()
                            // the descriptor instead of passing -1 as the length.
                            ext.setDataSource(afd.fileDescriptor)
                        }
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "Video source not seekable, copying to cache: ${LogText.short(uriStr)}", t)
                        val cached = copyVideoToCache(
                            uriStr,
                            isCancelled = { videoGeneration.get() != gen },
                        )
                        if (cached == null) {
                            AppLog.e(TAG, "Video cache fallback failed: ${LogText.short(uriStr)}")
                            if (videoGeneration.get() == gen) {
                                isVideoPlaying = false
                                onVideoStartFailed?.invoke()
                            }
                            return
                        }
                        copiedVideoPath = cached.absolutePath
                        if (videoGeneration.get() == gen) {
                            // The audio path prefers this copy: it is a seekable
                            // local file, so the sound never re-opens a cloud
                            // provider (which could block for seconds).
                            currentVideoCachePath = cached.absolutePath
                        }
                        ext.setDataSource(cached.absolutePath)
                    }
                }
                val trackIdx = (0 until ext.trackCount).firstOrNull { i ->
                    ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: run {
                    AppLog.e(TAG, "No video track")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }
                ext.selectTrack(trackIdx)
                // "接着上次位置继续播放": the FIRST round of a startVideo() that
                // carried a remembered position begins there instead of at 0.
                // Later rounds fall into the warm-session branch below and rewind
                // to 0, so the clip still loops from the beginning.
                val resumeUs = pendingStartPositionUs
                if (resumeUs > 0L) {
                    pendingStartPositionUs = 0L
                    try {
                        ext.seekTo(resumeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        AppLog.d(
                            TAG,
                            "Video resumes at ${resumeUs / 1000}ms (kept position, not the start)"
                        )
                    } catch (t: Throwable) {
                        // A source that refuses the seek simply plays from the
                        // start (the old behaviour) instead of failing.
                        AppLog.w(TAG, "Video resume seek failed: ${t.message}")
                    }
                }
                val format = ext.getTrackFormat(trackIdx)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime == null) {
                    AppLog.e(TAG, "Video track has no MIME type")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }
                var videoW = format.getIntegerSafe(MediaFormat.KEY_WIDTH)
                var videoH = format.getIntegerSafe(MediaFormat.KEY_HEIGHT)
                // Videos with a 90/270 degree rotation (e.g. portrait phone
                // recordings) display with swapped width/height — needed up
                // front to compute the FIT decode cap below.
                val rotation = format.getIntegerSafe(MediaFormat.KEY_ROTATION)
                val isRotated = rotation == 90 || rotation == 270
                val quadW = if (isRotated) videoH else videoW
                val quadH = if (isRotated) videoW else videoH
                val maxDim = maxOf(videoW, videoH)
                // Decode to at most the screen resolution. This keeps the
                // rendered picture pixel-identical to the source on the actual
                // display (the old fixed 1280px cap made large videos blurry)
                // while avoiding the wasted power/memory of decoding far
                // larger sources (4K/8K videos) at full size.
                val screenWpx = context.resources.displayMetrics.widthPixels
                val screenHpx = context.resources.displayMetrics.heightPixels
                // The engine may turn the video another 90° (auto rotate
                // mismatch, see refreshVideoQuad): what lands on screen is then
                // the swapped rect, so the FIT size - and the decode cap derived
                // from it - has to use those dims. Without this a 4K landscape
                // clip shown rotated on a portrait screen was decoded at
                // 1500x842 (fitted WITHOUT the turn) and then magnified 1.7x by
                // the GPU - the "适应模式下视频发虚" case.
                val willTurn = autoRotateMismatch && quadW > 0 && quadH > 0 &&
                    quadW != quadH && ((quadW > quadH) != (screenWpx > screenHpx))
                // One shared rule with the image decode (see displaySpan): what
                // lands on screen after the engine's own quarter turn.
                val (fitW, fitH) = com.wallpaperswitcher.engine.BitmapUtils
                    .displaySpan(quadW, quadH, willTurn)
                val screenMax = maxOf(screenWpx, screenHpx)
                val baseCap = minOf(screenMax, 3200).coerceAtLeast(1280)
                // FIT letterboxes the video: it is displayed at the fitted
                // size, so capping to the raw screen max over-decodes
                // aspect-mismatched videos (e.g. a landscape video on a
                // portrait phone is shown ~1080 wide but used to decode up to
                // 2400, ~5x the pixels). Cap to the fitted size with a 1.25x
                // quality headroom — the GPU then only downscales, never
                // magnifies. FILL/STRETCH keep the full-screen cap (with a
                // 1920 floor so small sources stay sharp when magnified).
                val decodeCapBase = when (scaleMode) {
                    ScaleMode.FIT -> {
                        val fitScale = if (fitW > 0 && fitH > 0) {
                            minOf(screenWpx.toFloat() / fitW, screenHpx.toFloat() / fitH)
                        } else 1f
                        val fittedLong =
                            maxOf(fitW * fitScale, fitH * fitScale).toInt().coerceAtLeast(1)
                        (fittedLong * 1.25f).toInt().coerceIn(1280, baseCap)
                    }
                    ScaleMode.FILL, ScaleMode.STRETCH ->
                        minOf(screenMax, 3200).coerceAtLeast(1920)
                }
                // Frame rate does NOT change the decode resolution: 50/60fps
                // sources are decoded exactly like any other media, i.e. by the
                // screen-pixel rule above (FIT fits the screen, FILL/STRETCH
                // uses the screen max). A 60fps source therefore keeps full
                // sharpness; on a device whose GPU cannot fill the screen at
                // 60fps the presentation rate simply follows the GPU, the
                // playback speed stays 1:1 (pacing follows the frame
                // timestamps).
                val decodeCap = decodeCapBase
                if (maxDim > decodeCap) {
                    val scale = decodeCap.toFloat() / maxDim
                    // Even dimensions keep codec/SurfaceTexture happy, but the
                    // even-bit mask must never produce 0 (a 1px result would
                    // become 0 and break setDefaultBufferSize/decode).
                    videoW = (videoW * scale).toInt().and(0xFFFFFFFE.toInt()).coerceAtLeast(2)
                    videoH = (videoH * scale).toInt().and(0xFFFFFFFE.toInt()).coerceAtLeast(2)
                }
                // Playback pacing follows the source frame timestamps (see the
                // decode loop below); this is only a floor so a very high-fps
                // source cannot spin the loop. KEY_FRAME_RATE is missing on
                // plenty of containers (SAF/ffmpeg files, variable-rate
                // recordings) and guessing 15fps for them made a 30fps clip play
                // in slow motion while a 1.5fps clip raced through its loop
                // every ~0.8s (a repeating "Video started" in the logs).
                val minFrameGapNs = 16_000_000L

                // --- Setup GL texture + SurfaceTexture on render thread ---
                val setupLatch = CountDownLatch(1)
                var setupOk = false
                var setupAttempts = 0
                // A warm session already owns a SurfaceTexture + texture bound
                // to its codec, so a loop restart has nothing to rebuild here.
                val reuseGl = reuseSession && sessionDecoder != null &&
                    surfaceTexture != null && codecSurface != null && videoTexId != 0
                lateinit var attemptVideoSetup: () -> Unit
                attemptVideoSetup = {
                    if (reuseGl) {
                        setupOk = true
                        setupLatch.countDown()
                    } else {
                        handler.post {
                            try {
                                if (videoGeneration.get() != gen) {
                                    setupLatch.countDown()
                                    return@post
                                }
                                if (!surfaceReady || !contextReady) {
                                    // The EGL surface is recreated on rotation (and can
                                    // briefly be unavailable). Retry for ~1.6s instead
                                    // of failing the whole video: on non-Xiaomi devices
                                    // failing here left the wallpaper black for
                                    // seconds until the recovery switch.
                                    setupAttempts++
                                    if (setupAttempts <= 8) {
                                        AppLog.d(TAG, "Video GL setup waiting for surface (attempt $setupAttempts)")
                                        handler.postDelayed(attemptVideoSetup, 200L)
                                    } else {
                                        AppLog.e(TAG, "Video GL setup failed: surface never became ready")
                                        setupLatch.countDown()
                                    }
                                    return@post
                                }
                                if (videoTexId == 0) {
                                    val texIds = IntArray(1)
                                    GLES20.glGenTextures(1, texIds, 0)
                                    videoTexId = texIds[0]
                                }
                                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
                                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        
                                // Some non-Xiaomi devices/drivers fail to compile the
                                // external-texture shader or lose the program after a
                                // context loss. Without this guard the decoder keeps
                                // running while renderVideoFrame() skips every frame,
                                // leaving the wallpaper black for the health monitor's
                                // 15s window. Fail fast so the engine recovers with a
                                // different media (usually a static image) instead.
                                if (gl.videoProgram == 0) {
                                    AppLog.e(TAG, "Video shader program unavailable; aborting video setup")
                                    setupLatch.countDown()
                                    return@post
                                }
        
                                val st = SurfaceTexture(videoTexId)
                                st.setDefaultBufferSize(videoW, videoH)
                                surfaceTexture = st
                                codecSurface = Surface(st)
                                // Remember the on-screen size + scale mode so the frame
                                // renderer can sharpen low-res videos that are magnified
                                // to fill the screen.
                                videoDisplayW = quadW.toFloat()
                                videoDisplayH = quadH.toFloat()
                                videoSrcW = videoW.toFloat()
                                videoSrcH = videoH.toFloat()
                                videoScaleMode = scaleMode
                                // Do NOT clear the framebuffer here.
                                //
                                // This used to swap a fully black frame "so the previous
                                // video's frame cannot linger", which showed up as a
                                // black flash on every image→video switch and on every
                                // loop restart (the codec is recreated per pass, so the
                                // flash happened every few seconds for short clips).
                                // Keeping the previous frame on screen until the new
                                // video's first frame is presented is both seamless
                                // (looping) and less jarring (a switch keeps the old
                                // wallpaper visible instead of flashing black).
                                // A video that never produces a frame is handled by the
                                // engine's recovery (first-frame fallback + media switch)
                                // rather than by painting black over it.
                                setupOk = true
                                setupLatch.countDown()
                            } catch (t: Throwable) {
                                AppLog.e(TAG, "Video GL setup failed", t)
                                setupLatch.countDown()
                            }
                        }
                    }
                }
                attemptVideoSetup()
                try { setupLatch.await(6, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                if (!setupOk || videoGeneration.get() != gen) {
                    // Being superseded by a newer video (switch/rotation right
                    // after this one started) is the normal teardown path, not a
                    // failure: logging it as an error buried the real ones in the
                    // tablet log.
                    if (videoGeneration.get() != gen) {
                        AppLog.d(TAG, "Video GL setup abandoned (superseded)")
                    } else {
                        AppLog.e(TAG, "Video GL setup failed")
                    }
                    // Only reset engine state when THIS video is still the
                    // current one. A superseded setup must not stop a newer
                    // video that is already decoding/playing.
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }
                // A concurrent stopVideoAndRender/stopVideoInternal may have
                // already posted cleanup that nulled the shared fields right
                // after our generation check. Capture the references now and
                // bail out if they are gone instead of hitting an NPE in
                // configure() or the decode loop (seen in the logs as
                // decodeLoop NullPointerException right after a timed switch).
                val st = surfaceTexture
                val cs = codecSurface
                if (st == null || cs == null || videoGeneration.get() != gen) {
                    AppLog.e(TAG, "Video resources torn down during setup")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }

                // --- Setup MediaCodec on THIS thread (decode thread) ---
                val warm = if (reuseSession) sessionDecoder else null
                val dec: MediaCodec
                if (warm != null) {
                    // Loop restart of the same file: keep the warmed codec and
                    // rewind it instead of re-creating the decoder + surface.
                    dec = try {
                        warm.flush()
                        // Some vendor decoders want the SPS/PPS again after a
                        // flush (the same workaround ExoPlayer applies).
                        requeueCodecSpecificData(warm, format)
                        ext.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        AppLog.d(TAG, "Video loop restart (codec + GL reused, no re-init)")
                        warm
                    } catch (t: Throwable) {
                        // A codec that refuses a flush must not be used again:
                        // drop the whole warm session (codec + extractor + the
                        // GL resources bound to it) and rebuild from scratch on
                        // the next iteration - the same path a new video takes.
                        AppLog.w(
                            TAG,
                            "Codec reuse failed (${t.javaClass.simpleName}: ${t.message}); " +
                                "rebuilding the video session"
                        )
                        try { warm.stop() } catch (_: Exception) {}
                        try { warm.release() } catch (_: Exception) {}
                        if (decoder === warm) decoder = null
                        if (localDecoder === warm) localDecoder = null
                        sessionExtractor?.let { e -> try { e.release() } catch (_: Exception) {} }
                        if (localExtractor === sessionExtractor) localExtractor = null
                        sessionDecoder = null
                        sessionExtractor = null
                        reuseSession = false
                        handler.post {
                            try {
                                if (videoGeneration.get() == gen) {
                                    cleanupVideoResourcesOnRenderThread()
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        continue
                    }
                } else {
                    dec = MediaCodec.createDecoderByType(mime)
                    dec.configure(format, cs, null, 0)
                    dec.start()
                }
                localDecoder = dec
                decoder = dec
                // Audio/video re-anchor: a new playback pass starts here, so the
                // audio thread may start (or restart) its own pass. Kept after
                // dec.start() so a pass that fails to set up never makes noise.
                audio.onVideoPassStart(gen)

                // Cache render quad on render thread
                handler.post {
                    try {
                        if (videoGeneration.get() != gen) return@post
                        refreshVideoQuad(quadW.toFloat(), quadH.toFloat(), scaleMode)
                        AppLog.d(TAG, "Video quad set: video=${quadW}x${quadH} mode=$scaleMode " +
                                "screen=${screenW.toInt()}x${screenH.toInt()} rotate=$videoExtraRotate")
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "Video quad computation failed", t)
                    }
                }

                val codecName = try { dec.name } catch (_: Exception) { "unknown" }
                // KEY_FRAME_RATE is informational only now (pacing uses the
                // frame timestamps), and it is missing on some containers.
                val declaredFps = format.getIntegerSafe(MediaFormat.KEY_FRAME_RATE)
                AppLog.d(
                    TAG,
                    "Video started: ${videoW}x${videoH} @ " +
                        "${if (declaredFps > 0) "${declaredFps}fps" else "unspecified fps"} codec=$codecName"
                )

                // --- Inner decode loop (one playback pass) ---
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var eof = false
                // Frames presented during THIS pass; a reused pass that presents
                // nothing drops the session so the next pass rebuilds it (see the
                // safety net after the decode loop).
                var passFramesPresented = 0L
                // Presentation clock for this playback pass: frames are paced
                // against their own presentation timestamps so the clip plays
                // at its real speed regardless of the container metadata.
                var passStartNs = -1L
                var firstPtsUs = -1L
                var lastPresentNs = 0L
                // Paused on purpose while the wallpaper is not visible (see
                // below). Tracked so the pause/resume pair is logged once.
                var pausedForVisibility = false
                while (videoGeneration.get() == gen && !Thread.interrupted() && !eof) {
                    try {
                        // Not visible (screen off / another app in front / the
                        // system live-wallpaper dialog): pause COMPLETELY.
                        //
                        // The old behaviour kept a ~1fps slideshow running,
                        // which meant the decoder, the GL upload and the power
                        // all kept working for a wallpaper nobody could see.
                        // Nothing is dequeued here, so the codec stalls by
                        // itself (its output queue fills up and it stops
                        // decoding) and playback resumes from the exact frame
                        // it stopped on - the pacing clock is re-anchored on
                        // the first frame after the pause, so the clip never
                        // fast-forwards to "catch up" either.
                        if (powerSaveMode) {
                            if (!pausedForVisibility) {
                                pausedForVisibility = true
                                // One line per hidden episode, not per playback
                                // pass (the timer may restart the video while it
                                // is hidden).
                                if (!videoPauseAnnounced) {
                                    videoPauseAnnounced = true
                                    AppLog.d(TAG, "Video paused (wallpaper not visible)")
                                }
                            }
                            // Wait for the visibility change to wake us instead
                            // of polling: a screen-off wallpaper then costs no
                            // wakeups at all (see powerSaveMode).
                            synchronized(pauseLock) {
                                if (powerSaveMode) {
                                    try {
                                        pauseLock.wait(PAUSE_WAIT_MAX_MS)
                                    } catch (_: InterruptedException) {
                                        // stopVideo()/engine teardown interrupts
                                        // this thread: restore the flag so the
                                        // loop conditions see it and exit.
                                        Thread.currentThread().interrupt()
                                    }
                                }
                            }
                            continue
                        }
                        if (pausedForVisibility) {
                            pausedForVisibility = false
                            if (videoPauseAnnounced) {
                                AppLog.d(TAG, "Video resumed (wallpaper visible again)")
                            }
                        }
                        if (!inputDone) {
                            // Pre-fill a few input buffers: on cloud-hosted
                            // files each readSampleData can block on the
                            // network, and serializing read->decode->read made
                            // playback stutter. Filling the codec input queue
                            // in batches keeps the reads continuous while the
                            // output cadence stays controlled by intervalNs
                            // (playback speed is unchanged).
                            var fed = 0
                            while (fed < 3 && !inputDone) {
                                val inIdx = dec.dequeueInputBuffer(0)
                                if (inIdx >= 0) {
                                    val buf = dec.getInputBuffer(inIdx) ?: break
                                    val size = ext.readSampleData(buf, 0)
                                    if (size < 0) {
                                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    } else {
                                        dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                                        ext.advance()
                                        fed++
                                    }
                                } else {
                                    break
                                }
                            }
                        }

                        val outIdx = dec.dequeueOutputBuffer(bufferInfo, 10_000)
                        if (outIdx >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                dec.releaseOutputBuffer(outIdx, false)
                                eof = true
                                continue
                            }

                           dec.releaseOutputBuffer(outIdx, true)
                           passFramesPresented++
                           // Remember the position for "接着上次位置继续播放"
                           // (read by the engine when it releases the session
                           // while the device is locked).
                           lastVideoPositionUs =
                               bufferInfo.presentationTimeUs.coerceAtLeast(0L)

                            // Only one pending render post at a time; if the
                            // render thread is busy, the newest frame simply
                            // supersedes the previous one (updateTexImage
                            // always picks up the latest buffer).
                            if (renderPostQueued.compareAndSet(false, true)) {
                                handler.post {
                                    renderPostQueued.set(false)
                                    if (videoGeneration.get() != gen) return@post
                                    if (!surfaceReady || !contextReady) return@post
                                    try {
                                        st.updateTexImage()
                                        st.getTransformMatrix(videoTexMatrix)
                                        renderVideoFrame(videoTexMatrix)
                                    } catch (t: Throwable) {
                                        // Once per frame while the SurfaceTexture or the GL
                                        // context is broken, and AppLog flushes every
                                        // important line to disk - an unthrottled throwable
                                        // here was 30-60 line+stack writes per second (see
                                        // FRAME_WARN_INTERVAL_MS). Shares that budget with
                                        // the other render warnings.
                                        val nowWarn = android.os.SystemClock.elapsedRealtime()
                                        if (nowWarn - lastFrameWarnAt > FRAME_WARN_INTERVAL_MS) {
                                            lastFrameWarnAt = nowWarn
                                            AppLog.e(TAG, "renderVideoFrame failed", t)
                                        }
                                    }
                                }
                            }

                            // Pace against the frame's own timestamp: the clip
                            // plays at its true speed even when the container
                            // does not report a frame rate. The floor keeps a
                            // 60fps+ source from spinning the loop. While the
                            // wallpaper is not visible the loop pauses before it
                            // ever gets here (see above), so this floor only
                            // ever applies to visible playback.
                            val nowNs = System.nanoTime()
                            if (passStartNs < 0L) {
                                passStartNs = nowNs
                                firstPtsUs = bufferInfo.presentationTimeUs
                            }
                            val ptsOffsetNs =
                                ((bufferInfo.presentationTimeUs - firstPtsUs) * 1000L)
                                    .coerceAtLeast(0L)
                            // Playback clock vs. wall clock: while throttled
                            // (power save) the frames are ~1s late by design, and
                            // after a freeze they can be seconds late. Re-anchor
                            // so the clip continues at its normal speed from the
                            // frame that is due now instead of fast-forwarding to
                            // catch up the time nobody could see.
                            val lagNs = nowNs - (passStartNs + ptsOffsetNs)
                            if (!powerSaveMode) powerSavePauseLogged = false
                            // Visible playback again: the next hidden episode
                            // announces itself.
                            if (!powerSaveMode) videoPauseAnnounced = false
                            if (lagNs > maxPlaybackLagNs) {
                                passStartNs = nowNs - ptsOffsetNs
                                if (powerSaveMode) {
                                    // One line per throttled episode instead of
                                    // one per frame (screen-off logs must stay
                                    // quiet).
                                    if (!powerSavePauseLogged) {
                                        powerSavePauseLogged = true
                                        AppLog.d(
                                            TAG,
                                            "Playback clock paused for power save " +
                                                "(${lagNs / 1_000_000}ms behind)"
                                        )
                                    }
                                } else {
                                    val elapsed = SystemClock.elapsedRealtime()
                                    if (elapsed - lastReanchorLogAt > 5_000L) {
                                        lastReanchorLogAt = elapsed
                                        AppLog.d(
                                            TAG,
                                            "Playback was ${lagNs / 1_000_000}ms behind; " +
                                            "re-anchored instead of fast-forwarding"
                                        )
                                    }
                                }
                            }
                            val floorNs = if (powerSaveMode) 1_000_000_000L else minFrameGapNs
                            val targetNs = maxOf(passStartNs + ptsOffsetNs, lastPresentNs + floorNs)
                            val sleepNs = targetNs - System.nanoTime()
                            if (sleepNs > 0) {
                                // InterruptedException is the normal "stop" signal.
                                try {
                                    Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                                } catch (_: InterruptedException) {}
                            }
                            lastPresentNs = targetNs
                        } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // 5ms poll interval instead of 1ms: with slow /
                            // cloud-hosted decoders this cuts idle CPU wakeups
                            // by 5x with no visible latency impact.
                            try { Thread.sleep(5) } catch (_: InterruptedException) {}
                        }
                    } catch (t: Throwable) {
                        // A codec can throw (e.g. IllegalStateException) when it
                        // is being torn down concurrently with a switch. End this
                        // pass cleanly: the round cleanup releases the codec and
                        // the outer loop retries (same generation) or exits
                        // (superseded). Never let this freeze the previous frame.
                        errorPasses++
                        if (errorPasses >= 3) {
                            giveUp = true
                            if (videoGeneration.get() == gen) onVideoStartFailed?.invoke()
                        }
                        // A superseded video's decoder is torn down under it; that
                        // is expected during a switch and must not look like an
                        // error (only a pass that failed while still current is).
                        if (videoGeneration.get() != gen) {
                            AppLog.d(TAG, "Decode pass ended (superseded)")
                        } else {
                            AppLog.e(TAG, "Decode pass interrupted", t)
                        }
                        eof = true
                    }
                }

                // Keep this pass's codec + extractor + SurfaceTexture alive when
                // the same file is about to loop again: the next pass then only
                // pays a flush + seek instead of a full rebuild. Everything is
                // released by the thread's own teardown (finally) once the video
                // is switched away, rotated, or the engine is destroyed.
                val keepWarm = eof && passFramesPresented > 0L &&
                    videoGeneration.get() == gen && !Thread.interrupted() && !giveUp
                reuseSession = keepWarm
                if (keepWarm) {
                    sessionExtractor = ext
                    sessionDecoder = dec
                } else {
                    // Release this round's codec cleanly.
                    try { dec.stop() } catch (_: Exception) {}
                    try { dec.release() } catch (_: Exception) {}
                    if (decoder === dec) decoder = null
                    if (localDecoder === dec) localDecoder = null
                    sessionDecoder = null
                    // Release this round's extractor too: it holds a file
                    // descriptor, and looping videos would otherwise accumulate an
                    // extractor + fd per playback pass until the thread exits.
                    try { ext.release() } catch (_: Exception) {}
                    if (localExtractor === ext) localExtractor = null
                    sessionExtractor = null
                    // The cache copy (if any) is only needed while decoding.
                    copiedVideoPath?.let { path ->
                        try { java.io.File(path).delete() } catch (_: Exception) {}
                        copiedVideoPath = null
                    }
                }

                if (videoGeneration.get() != gen || Thread.interrupted()) break
                // 视频播完再切: one notification per completed pass, on BOTH
                // loop paths. The warm path (codec + GL reused, which is the
                // normal loop of the same file) used to `continue` before the
                // eof block below, so the callback never ran and a held timed
                // switch waited forever while the clip looped.
                if (eof && passFramesPresented > 0L) {
                    try {
                        onVideoPassCompleted?.invoke()
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "onVideoPassCompleted callback failed", t)
                    }
                }
                if (keepWarm) {
                    // The warm session already has its GL resources; cleaning
                    // them here would also break the codec's surface, and the
                    // on-screen frame is kept until the next pass presents its
                    // own first frame (no black flash at the loop point).
                    // Only the per-pass frame-rate line is still emitted, so a
                    // looping video keeps the same diagnostics as before.
                    handler.post {
                        if (videoGeneration.get() == gen) logPassFrameRate()
                    }
                    continue
                }
                if (eof) {
                    // A pass that presented nothing (codec reuse rejected the
                    // rewound stream): drop the session so the next iteration
                    // rebuilds the decoder from scratch instead of looping
                    // forever without a frame.
                    if (warm != null && passFramesPresented == 0L) {
                        AppLog.w(
                            TAG,
                            "Reused video codec produced no frame; rebuilding the session"
                        )
                    }
                    // Loop: clean this round's GL resources, then the outer
                    // loop recreates the extractor + codec + SurfaceTexture.
                    handler.post {
                        // Re-check the generation when this actually runs: a
                        // newer video may have started since this EOF cleanup
                        // was queued, and destroying "its" shared GL resources
                        // would corrupt the new video's rendering.
                        if (videoGeneration.get() != gen) return@post
                        try {
                            cleanupVideoResourcesOnRenderThread()
                        } catch (t: Throwable) {
                            AppLog.e(TAG, "Video cleanup failed", t)
                        }
                    }
                    continue
                }
                break
            }
        } catch (t: Throwable) {
            // Catch Throwable (incl. OutOfMemoryError) so a decode failure can
            // never crash the whole process and kill the wallpaper engine.
            if (videoGeneration.get() == gen) {
                // The CURRENT video failed (e.g. codec configure raced a
                // cleanup). Tell the engine to reset lastDisplayedId and retry,
                // otherwise the previous frame stays frozen on screen forever.
                AppLog.e(TAG, "Decode error", t)
                isVideoPlaying = false
                onVideoStartFailed?.invoke()
            } else {
                // Superseded by a newer switch: expected during rapid
                // double-tap switching. The new video owns the screen, so this
                // is not an error.
                AppLog.d(TAG, "Decode thread superseded during setup", t)
            }
        } finally {
            // Only clear the playing flag when THIS thread is still the current
            // video. A superseded thread must never clobber a newer video's
            // state (this used to make the engine restart the new video).
            if (videoGeneration.get() == gen) isVideoPlaying = false
            try { localDecoder?.stop() } catch (_: Exception) {}
            try { localDecoder?.release() } catch (_: Exception) {}
            if (decoder === localDecoder) decoder = null
            try { localExtractor?.release() } catch (_: Exception) {}
            if (extractor === localExtractor) extractor = null
            try { localAfd?.close() } catch (_: Exception) {}
            // Only post GL cleanup if stopVideoInternal/stopVideoAndRender hasn't already done it.
            // Those methods set videoCleanupDone=true and post their own cleanup.
            // Posting here would race: an old decode thread that exits late could
            // clean up the NEW video's resources after its setup. The generation
            // check makes sure only the CURRENT video's thread may clean up.
            if (videoGeneration.get() == gen && !videoCleanupDone.getAndSet(true)) {
                handler.post {
                    try {
                        cleanupVideoResourcesOnRenderThread()
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "Video cleanup failed", t)
                    }
                }
            }
        }
    }
