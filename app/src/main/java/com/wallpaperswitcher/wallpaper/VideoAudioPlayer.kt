package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.wallpaperswitcher.engine.VideoSound
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 视频音轨的独立播放线程（从 WallpaperRenderer 拆出，行为不变）。
 *
 * The audio runs on its OWN thread with its own MediaExtractor/MediaCodec/
 * AudioTrack, because the video decode thread must never block: it paces
 * frames against the presentation clock and a blocking PCM write would stall
 * playback. Audio and video are re-anchored at every playback pass (the
 * video calls [onVideoPassStart] when a pass starts; the audio thread waits
 * for it), which keeps the two from drifting apart across loops.
 *
 * The renderer stays the owner of the video-side state; everything the audio
 * loop needs from it arrives through the constructor callbacks, so no audio
 * state leaks back into [WallpaperRenderer].
 */
internal class VideoAudioPlayer(
    private val context: Context,
    private val tag: String,
    /** Renderer's `videoSoundEnabled`. */
    private val soundEnabled: () -> Boolean,
    /** Renderer's `videoGeneration`. */
    private val generation: () -> Int,
    /** Renderer's `currentVideoCachePath` (seekable local copy, if any). */
    private val cachePath: () -> String?,
    /** Renderer's `powerSaveMode` (hidden = pause, never mute). */
    private val powerSavePaused: () -> Boolean,
    /**
     * Blocks (max the renderer's pause timeout) until the wallpaper may be
     * visible again. Runs on the audio thread; the renderer owns the monitor.
     */
    private val waitWhilePaused: () -> Unit,
    /**
     * 视频当前播放位置（us）：只用于 [DIAG_WINDOW_MS] 一次的 A/V 漂移观测。
     * 音频按硬件时钟自由走、画面按自己的呈现时钟走，两者只在每个 playback pass
     * 重新对齐；没有这行数据就没法判断"要不要做补偿"。
     */
    private val videoPositionUs: () -> Long,
    /**
     * 视频是否已经停在最后一帧（循环播放关闭 + 这一遍播完）：这时声音也必须
     * 停，否则画面静止、声音还在循环，用户听到的就是"视频卡住但声音没停"。
     */
    private val holdingLastFrame: () -> Boolean = { false },
) {

    private var audioThread: Thread? = null
    private val videoPassCounter = AtomicInteger(0)
    // Generation for which "this video has no audio track" was already logged.
    @Volatile private var audioNoTrackLoggedFor = -1
    /**
     * One AudioTrack for the whole renderer, reused by every video switch and
     * every playback pass. Creating a track per pass/switch (the first version)
     * cost an audible click each time: the framework tears the old mix down and
     * ramps the new one up.
     */
    private val audioSession = AudioSession(tag)
    /** PCM a pause forced us to hold back, written as soon as we are visible. */
    @Volatile private var audioPending: ByteBuffer? = null
    /**
     * Token of the CURRENT audio run. Bumped by [start] and by [stop], so a
     * thread that survived an interrupt (it was parked in a codec/wait call) can
     * tell that it lost ownership: it must neither write into the new video's
     * track nor call `resume()` on a track the engine just silenced.
     *
     * Only touched from the owner (the render/engine thread).
     */
    @Volatile private var audioRunGen = 0

    /**
     * Audio-side sleeps: the audio thread must notice a pause/switch/sound
     * toggle within a few milliseconds, so it never sleeps as long as the video
     * pause poll.
     *
     * 写满缓冲后的等待不再是固定值：按"这个 chunk 还差多少毫秒的容量"算（见
     * [retryDelayMs]）。固定 5ms 会让稳态唤醒达到 ~210 次/秒（实测音频线程
     * 6.9% 单核）；按缺口睡把唤醒降到 ~1/4，实测降到 5.5%。
     */
    private val AUDIO_WRITE_RETRY_MIN_MS = 4L
    private val AUDIO_WRITE_RETRY_MAX_MS = 25L
    private val AUDIO_OUTPUT_WAIT_US = 10_000L
    /** Bound for opening the audio source (same idea as the video's 15s). */
    private val AUDIO_OPEN_TIMEOUT_MS = 12_000L

    /**
     * 观测窗口：每 5s 打一行，回答两个问题 —— 写循环有多贵（loops/full 的次数）、
     * 声音和画面漂了多少。没有它，"要不要优化音频"只能靠猜。
     */
    private val DIAG_WINDOW_MS = 5_000L
    private var diagWindowStartMs = 0L
    private var diagLoops = 0
    private var diagFullSleeps = 0
    /** AudioTrack 已播帧数在该 pass 起点时的值（漂移的基准）。 */
    private var headAtPassStart = 0L
    /**
     * 视频位置在该 pass 起点时的值（us）。漂移必须比较"同一 pass 内各自走了多远"：
     * 视频位置是文件时间轴上的绝对值，音频头是相对值，直接相减得到的是一段恒定偏移，
     * 会把"首帧提前量"误读成漂移（第一版就是这么错的）。
     */
    private var videoAtPassStartUs = 0L
    /** 上一个 pass 的 seekTo+flush 耗时，以及本 pass 从开始到首包 PCM 的耗时（见日志）。 */
    private var passSeekMs = 0L
    private var passStartedAtMs = 0L
    private var passFirstPcmLogged = false
    /** 循环边界：发现边界的时刻，和那时轨道里还没播掉的毫秒数。 */
    private var boundaryAtMs = 0L
    private var unplayedAtBoundaryMs = 0
    /** 上一次"缓冲满"重试睡了多久、是因为还差多少字节（诊断按缺口休眠用）。 */
    private var lastRetryBytes = 0
    private var lastRetryMs = 0L

    /**
     * Called by the video decode thread every time a playback pass is ready to
     * present. The audio thread waits for this, so both start a pass together
     * and cannot drift apart across loops.
     */
    fun onVideoPassStart(gen: Int) {
        // A late-exiting decode thread of a superseded video must not look like
        // the new video's first pass (the audio would start early and stutter).
        if (generation() != gen) return
        videoPassCounter.incrementAndGet()
        synchronized(audioPassLock) { audioPassLock.notifyAll() }
    }

    /**
     * Playback passes are counted per video: the audio thread waits for the
     * FIRST pass of this video before it makes any sound.
     */
    fun resetPassCounter() {
        videoPassCounter.set(0)
    }

    /** Start the audio thread for [uriStr]; only called when sound is enabled. */
    fun start(uriStr: String, gen: Int, startPositionUs: Long = 0L) {
        stop()
        val runGen = ++audioRunGen
        val thread = Thread({
            // AUDIO priority, not BACKGROUND: the audio must not be starved by
            // the video decode + GL work that runs at the same time (a starved
            // writer is the classic cause of crackling on a busy device).
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            audioLoop(uriStr, gen, startPositionUs, runGen)
        }, "VideoAudio").apply {
            // Never keep the process alive for a wallpaper's sound.
            isDaemon = true
        }
        audioThread = thread
        thread.start()
    }

    /**
     * Ask the audio thread to stop. Deliberately non-blocking and without join():
     * the thread may be inside a codec call, and it releases its own
     * AudioTrack/MediaCodec in its finally block. Sound stops as soon as the
     * thread notices (it never blocks on a PCM write - see [writePcm]).
     */
    fun stop() {
        val thread = audioThread
        audioThread = null
        // Ownership moves on: any surviving thread of the previous run is stale
        // from here on (see audioRunGen) and may neither write nor resume.
        audioRunGen++
        // Silence immediately: the thread may be inside a codec call and takes a
        // few ms to exit, and the buffered AudioTrack would keep playing until
        // then. The track itself is kept for the next video (see audioSession).
        // 用 24ms 音量斜坡收尾而不是硬 pause：切片段时硬切会留在波形中间，听感是一次
        // 咔哒。斜坡在独立线程上跑，这里立刻返回（音频线程该被打断还是打断）。
        audioSession.fadeOutAndPause()
        if (thread != null) {
            thread.interrupt()
            synchronized(audioPassLock) { audioPassLock.notifyAll() }
        }
    }

    /** Silence instantly (power-save setter), keeping the track for the resume. */
    fun pauseTrack() {
        audioSession.fadeOutAndPause()
    }

    /** Sticky silence while our own UI is in front (see WallpaperRenderer). */
    fun muteForPolicy() {
        audioSession.muteForPolicy()
    }

    fun clearPolicyMute() {
        audioSession.clearPolicyMute()
    }

    /** 真正在出声（轨道存在且 play() 成功）：音频看门狗用它判断静音异常。 */
    fun isPlaying(): Boolean = audioSession.isPlaying()

    /**
     * This video generation already proved to have NO audio track. The audio
     * watchdog must not "recover" such a video over and over: silence is the
     * correct output and every retry would only burn a codec + log lines.
     */
    fun hasNoAudioTrack(gen: Int): Boolean = audioNoTrackLoggedFor == gen

    fun release() {
        audioPending = null
        audioSession.release()
    }

    /** Wait until a playback pass newer than [lastPass] has started. */
    private fun awaitNextVideoPass(lastPass: Int, gen: Int): Int? {
            while (generation() == gen && soundEnabled() && !holdingLastFrame() &&
            !Thread.currentThread().isInterrupted
        ) {
            val current = videoPassCounter.get()
            if (current > lastPass) return current
            synchronized(audioPassLock) {
                if (videoPassCounter.get() <= lastPass) {
                    try {
                        audioPassLock.wait(250L)
                    } catch (_: InterruptedException) {
                        return null
                    }
                }
            }
        }
        return null
    }

    private fun audioLoop(
        uriStr: String,
        gen: Int,
        startPositionUs: Long = 0L,
        runGen: Int = audioRunGen,
    ) {
        // The decoder/extractor stay open for the WHOLE video (they are only
        // rewound at each playback pass). Rebuilding them - and the AudioTrack -
        // on every loop is what produced the short gaps and clicks.
        val extractor = MediaExtractor()
        val session = audioSession
        var decoder: MediaCodec? = null
        var afd: AssetFileDescriptor? = null
        // Why this thread ended, for the diagnostic line in `finally`. An audio
        // thread that stops between two passes used to leave nothing in the log
        // beyond "thread finished", which made a silent video impossible to
        // diagnose.
        var exitReason = "loop condition"
        AppLog.d(tag, "Video audio: thread started (gen=$gen)")
        try {
            val cached = cachePath()
            if (cached != null) {
                // The video decode fell back to a local copy: it is seekable and
                // never blocks on the (possibly cloud) provider again.
                extractor.setDataSource(cached)
            } else {
                afd = openAudioDescriptor(uriStr) ?: run {
                    exitReason = "no audio source"
                    return
                }
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            val trackIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
                if (audioNoTrackLoggedFor != gen) {
                    audioNoTrackLoggedFor = gen
                    AppLog.d(tag, "Video has no audio track: ${LogText.short(uriStr)}")
                }
                exitReason = "no audio track"
                return
            }
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                exitReason = "audio track without MIME"
                return
            }
            var sampleRate =
                VideoSound.sampleRateFor(format.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE))
            var channels =
                VideoSound.playbackChannelsFor(format.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT))
            // What the container declares is a hint; the decoder's real output
            // format arrives with INFO_OUTPUT_FORMAT_CHANGED and is applied below.
            var encoding = VideoSound.PCM_ENCODING
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            // Create the track up front from the container's declared format; the
            // handler below retunes it if the decoder turns out to output
            // something else (HE-AAC, downmix). Skipped while the wallpaper is
            // hidden: allocating an AudioTrack only to pause it a millisecond
            // later achieves nothing, the loop below creates it on the first
            // visible frame instead.
            if (!powerSavePaused() &&
                session.trackFor(sampleRate, channels, encoding) == null
            ) {
                exitReason = "no AudioTrack while hidden"
                return
            }

            val info = MediaCodec.BufferInfo()
            var pass = 0
            var firstPass = true
            var announcedStart = false
            /**
             * 只有**第一个** pass 等视频（起播对齐）；之后不等。
             *
             * 实测：边界处"等视频进入下一个 pass"要 432–457ms，而音频在那一刻几乎
             * 已经写满缓冲，于是每次循环漏 94–113ms 静音（`underruns` 每循环 +1），
             * 且这个缺口**与缓冲大小无关**（700ms 缓冲只能让 waited 一起变长）。
             * 缺口的成因是视频侧重启，所以音频侧只能在边界继续往下预写：宁可让声音
             * 比画面早约 100ms（恒定、不累积），也不要在每个循环点挖一个洞。
             */
            var passAligned = false
            while (generation() == gen && soundEnabled() && !holdingLastFrame() &&
                !Thread.currentThread().isInterrupted
            ) {
                if (!passAligned) {
                    val nextPass = awaitNextVideoPass(pass, gen) ?: break
                    pass = nextPass
                    passAligned = true
                } else {
                    pass += 1
                }
                if (boundaryAtMs > 0L) {
                    // 边界到"音频重新开始"的时间：现在不再等视频，所以这里量的是
                    // 我们自己重启的耗时；未播量就是音频领先画面的量级。
                    val waited = android.os.SystemClock.elapsedRealtime() - boundaryAtMs
                    AppLog.d(
                        tag,
                        "Video audio pass $pass restart: ourOwn=${waited}ms " +
                            "unplayedAtBoundary=${unplayedAtBoundaryMs}ms " +
                            "lead≈${session.unplayedMs()}ms"
                    )
                    boundaryAtMs = 0L
                    unplayedAtBoundaryMs = 0
                }
                // Rewind for the new pass instead of rebuilding anything. The
                // video restarts its own codec right now; the audio already
                // buffered in the AudioTrack covers that restart, so playback
                // stays gapless and the two stay in lockstep (no drift, because
                // the audio waits for the video at every pass).
                // The first pass starts where the video resumed (lock-release
                // position); later passes rewind to 0 so the audio loops with the
                // picture.
                val seekStartedAtMs = android.os.SystemClock.elapsedRealtime()
                extractor.seekTo(
                    if (firstPass) startPositionUs.coerceAtLeast(0L) else 0L,
                    MediaExtractor.SEEK_TO_CLOSEST_SYNC
                )
                decoder.flush()
                passSeekMs = android.os.SystemClock.elapsedRealtime() - seekStartedAtMs
                passStartedAtMs = android.os.SystemClock.elapsedRealtime()
                passFirstPcmLogged = false
                audioPending = null
                if (firstPass) {
                    // New media: drop whatever the previous video left buffered.
                    if (session.track != null) {
                        session.restart()
                    } else {
                        // Hidden: no track was allocated yet (see below). The
                        // "started" line comes once it really exists, so a
                        // "buffer=0ms" can never appear in the log again.
                        AppLog.d(tag, "Video audio: waiting for the wallpaper to become visible")
                    }
                    firstPass = false
                } else {
                    session.ensurePlaying()
                }
                // Drift baseline for the diagnostics below: frames already
                // played when this pass starts.
                headAtPassStart = session.playbackHeadFrames()
                videoAtPassStartUs = videoPositionUs()
                var paused = false
                var passDone = false
                var inputDone = false
                // 不再拿 `videoPassCounter == pass` 当条件：边界之后音频要继续预写
                // 下一个 pass（见 passAligned 的说明）。循环由音频自己的 EOS 收尾。
                while (!passDone && generation() == gen && soundEnabled() &&
                    !holdingLastFrame() && !Thread.currentThread().isInterrupted
                ) {
                    // 循环播放关闭、画面停在最后一帧：声音立刻停（pause），不再把
                    // 这一遍剩下的 PCM 写完。否则画面静止了声音还要响好几秒，
                    // 用户听到的就是"视频卡住了声音没停"。
                    if (holdingLastFrame()) {
                        exitReason = "video frozen on its last frame (pass=$pass)"
                        try {
                            session.pause()
                        } catch (_: Exception) {}
                        break
                    }
                    if (powerSavePaused()) {
                        // Same rule as the picture: hidden means paused, not
                        // muted - playback continues where it stopped.
                        if (!paused) {
                            paused = true
                            session.pause()
                            AppLog.d(tag, "Video audio paused (wallpaper not visible)")
                        }
                        // Same event-based wait as the picture: no wakeups while
                        // the wallpaper is hidden, instant resume once it is back.
                        waitWhilePaused()
                        continue
                    } else if (paused) {
                        paused = false
                        session.resume()
                        AppLog.d(tag, "Video audio resumed (wallpaper visible again)")
                    }
                    if (session.track == null) {
                        // First visible frame of this video: create the track now
                        // (see the note at the pipeline setup).
                        if (session.trackFor(sampleRate, channels, encoding) == null) {
                            exitReason = "AudioTrack allocation failed (pass=$pass)"
                            return
                        }
                    }
                    // 每一轮都确保在播：解除 policy mute（引擎在主线程）与音频线程
                    // 的 ensurePlaying 存在时序竞争，只在创建轨道时调用一次会留下
                    // “日志说 started、AudioFlinger 里轨道其实 idle”的永久静音。
                    session.ensurePlaying()
                    diagLoops++
                    maybeLogDiag(session, pass)
                    if (!announcedStart && session.isPlaying()) {
                        announcedStart = true
                        AppLog.d(
                            tag,
                            "Video audio started: ${sampleRate}Hz ${channels}ch " +
                                "pcm=${if (encoding == VideoSound.PCM_ENCODING_FLOAT) "float" else "16bit"} " +
                                "buffer=${session.bufferMillis}ms mime=$mime"
                        )
                    }
                    // Samples held back by a pause are written first, so a
                    // hide/show cycle does not cut a chunk in half (a click).
                    if (!writePendingPcm(session, gen, pass)) continue
                    // Did this iteration hand the decoder new input? When it did
                    // not, the output dequeue below is the only thing left to
                    // wait for and may block (see AUDIO_OUTPUT_WAIT_US).
                    var fedInput = false
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(0)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)
                            if (inBuf == null) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, 0)
                                fedInput = true
                            } else {
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(
                                        inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                    fedInput = true
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                    fedInput = true
                                }
                            }
                        }
                    }
                    // Blocking wait when nothing was fed: the old loop polled
                    // with timeout 0 and only slept once the input was already
                    // exhausted, so a slow codec whose input queue was full
                    // could spin here at 200Hz. Waiting for the codec costs no
                    // latency - it returns as soon as a frame is ready.
                    val outIdx = decoder.dequeueOutputBuffer(
                        info, if (fedInput) 0L else AUDIO_OUTPUT_WAIT_US
                    )
                    if (outIdx >= 0) {
                        try {
                            if (info.size > 0 &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                val outBuf = decoder.getOutputBuffer(outIdx)
                                if (outBuf != null) {
                                    val wrote = writePcm(
                                        session, outBuf, info.offset, info.size, gen, pass, runGen
                                    )
                                    if (wrote && !passFirstPcmLogged) {
                                        // 每个 pass 的音频就绪延迟：从 pass 开始（视频画面已经
                                        // 重启）到这一拍真的把 PCM 交给 AudioTrack。缓冲覆盖得住
                                        // 的话它应该只比 seek+flush 多几毫秒；数大了就是循环有声缝。
                                        passFirstPcmLogged = true
                                        AppLog.d(
                                            tag,
                                            "Video audio pass $pass: first PCM after " +
                                                "${android.os.SystemClock.elapsedRealtime() - passStartedAtMs}ms " +
                                                "(seek+flush=${passSeekMs}ms)"
                                        )
                                    }
                                    if (!wrote) {
                                        if (powerSavePaused()) {
                                            paused = true
                                            session.pause()
                                            AppLog.d(
                                                tag,
                                                "Video audio paused (wallpaper not visible)"
                                            )
                                        } else if (generation() == gen && soundEnabled()) {
                                            // The video reached its next playback pass
                                            // while this chunk was being written - a
                                            // normal loop boundary, not a reason to
                                            // stop making sound. Dropping the rest of
                                            // THIS pass and waiting for the next one
                                            // keeps the audio alive across loops.
                                            //
                                            // 记录边界时刻和"轨道里还剩多少没播"：剩下的
                                            // 就是等下一个 pass 时能用掉的跑道，差值才是真
                                            // 正会被人听见的静音长度（见 awaitNextVideoPass
                                            // 之后的 pass resumed 行）。
                                            boundaryAtMs = android.os.SystemClock.elapsedRealtime()
                                            unplayedAtBoundaryMs = session.unplayedMs()
                                            passDone = true
                                            exitReason = "pass boundary (pass=$pass)"
                                        } else {
                                            exitReason = "PCM write refused (pass=$pass)"
                                            return
                                        }
                                    }
                                }
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                // 音频自己先到 EOS（比视频侧推进 pass 更早，实测就是
                                // 这条路径在驱动循环边界）：在这里也记边界，否则
                                // "循环边界空了多久"永远量不到。
                                boundaryAtMs = android.os.SystemClock.elapsedRealtime()
                                unplayedAtBoundaryMs = session.unplayedMs()
                                passDone = true
                                exitReason = "audio EOS (pass=$pass)"
                            }
                        } finally {
                            try { decoder.releaseOutputBuffer(outIdx, false) } catch (_: Throwable) {}
                        }
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // The decoder may output a different rate/channel count
                        // than the container declares (HE-AAC, downmixes): trust
                        // the output format, otherwise the PCM is reinterpreted
                        // at the wrong speed and sounds broken.
                        val outFormat = try { decoder.outputFormat } catch (_: Throwable) { null }
                        if (outFormat != null) {
                            val rate = VideoSound.sampleRateFor(
                                outFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE)
                            )
                            val ch = VideoSound.playbackChannelsFor(
                                outFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT)
                            )
                            // Missing KEY_PCM_ENCODING means 16-bit PCM.
                            val enc = VideoSound.playbackEncodingFor(
                                outFormat.getIntegerSafe(MediaFormat.KEY_PCM_ENCODING)
                            )
                            if (rate != sampleRate || ch != channels || enc != encoding) {
                                AppLog.d(
                                    tag,
                                    "Video audio format: ${rate}Hz ${ch}ch " +
                                        "pcm=${if (enc == VideoSound.PCM_ENCODING_FLOAT) "float" else "16bit"}"
                                )
                                sampleRate = rate
                                channels = ch
                                encoding = enc
                                session.trackFor(rate, ch, enc)
                                session.ensurePlaying()
                            }
                        }
                    }
                }
                if (generation() != gen) break
            }
        } catch (t: Throwable) {
            AppLog.d(tag, "Video audio pass ended: ${t.message}")
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            try { afd?.close() } catch (_: Throwable) {}
            audioPending = null
            // Reason on the same line: "why did the sound stop" was guesswork in
            // the logs whenever the thread ended between two passes.
            AppLog.d(
                tag,
                "Video audio: thread finished ($exitReason; gen=$gen interrupted=" +
                    "${Thread.currentThread().isInterrupted} sound=${soundEnabled()} " +
                    "currentGen=${generation()} passes=${videoPassCounter.get()})"
            )
        }
    }

    /**
     * 一行/5s 的观测（见 [DIAG_WINDOW_MS]）：写循环的开销 + A/V 漂移。
     *
     * `loops/s` 是内层循环的迭代频率（受 [AUDIO_WRITE_RETRY_MS] 与编解码节奏影响），
     * `full/s` 是"缓冲区满、只能睡 5ms 再试"的次数 —— 稳态下这两者基本上就是
     * 音频线程的唤醒频率。`drift` = 本 pass 内音频已播时长 − 视频当前位置；
     * 两者只在 pass 边界重新对齐，所以这个数就是"要不要做补偿"的依据。
     */
    private fun maybeLogDiag(session: AudioSession, pass: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (diagWindowStartMs == 0L) {
            diagWindowStartMs = now
            return
        }
        val elapsed = now - diagWindowStartMs
        if (elapsed < DIAG_WINDOW_MS) return
        val loops = diagLoops
        val fulls = diagFullSleeps
        diagLoops = 0
        diagFullSleeps = 0
        diagWindowStartMs = now
        val rate = session.sampleRateSafe
        val head = session.playbackHeadFrames()
        val audioMs = when {
            rate <= 0 || head <= 0L -> -1L
            // A recreated track (format change) / restart resets the head: report
            // "unknown" for that window instead of a bogus negative drift.
            head < headAtPassStart -> {
                headAtPassStart = head
                -1L
            }
            else -> (head - headAtPassStart) * 1_000L / rate
        }
        val videoMs = videoPositionUs() / 1_000L
        val videoSincePassMs = (videoMs - videoAtPassStartUs / 1_000L).coerceAtLeast(0L)
        AppLog.d(
            tag,
            "Video audio diag: ${loops * 1_000L / elapsed}loops/s " +
                "${fulls * 1_000L / elapsed}full/s audio=${audioMs}ms " +
                "videoPass=${videoSincePassMs}ms " +
                "drift=${if (audioMs < 0L) "?" else (audioMs - videoSincePassMs).toString()}ms " +
                "underruns=${session.underrunCount()} retry=${lastRetryMs}ms/${lastRetryBytes}B " +
                "bps=${session.bytesPerSecond()} buffer=${session.bufferMillis}ms pass=$pass"
        )
    }

    /**
     * How long to wait before retrying a write that found the buffer full.
     *
     * 按"还差多少毫秒的音频容量"来睡，而不是固定 5ms：一个 AAC chunk 通常 20ms
     * 上下，固定 5ms 会在稳态里空转 4 次（那是 ~210 次唤醒/秒的来源）。上下限
     * 保证仍然能在 ~25ms 内响应暂停/切换。
     */
    private fun retryDelayMs(session: AudioSession, bytes: Int): Long {
        val bps = session.bytesPerSecond()
        if (bps <= 0L || bytes <= 0) return AUDIO_WRITE_RETRY_MIN_MS
        val needMs = bytes * 1_000L / bps
        val delay = needMs.coerceIn(AUDIO_WRITE_RETRY_MIN_MS, AUDIO_WRITE_RETRY_MAX_MS)
        lastRetryBytes = bytes
        lastRetryMs = delay
        return delay
    }

    /**
     * Write decoded PCM without ever blocking.
     *
     * A blocking write would be fatal here: while the wallpaper is hidden the
     * AudioTrack is paused, so the buffer never drains and the audio thread would
     * hang until the screen came back. Non-blocking writes plus a short retry
     * sleep keep the thread responsive to pauses and switches.
     *
     * A chunk that is interrupted by a pause is NOT thrown away: the tail is
     * copied into [audioPending] and written again after the pause, so a
     * hide/show cycle does not clip a chunk in half (audible as a tick).
     *
     * @return true when the whole chunk was written or safely held back, false
     *   when the pass is no longer current (the audio is going away anyway).
     */
    private fun writePcm(
        session: AudioSession,
        buf: ByteBuffer,
        offset: Int,
        size: Int,
        gen: Int,
        pass: Int,
        runGen: Int
    ): Boolean {
        buf.position(offset)
        buf.limit(offset + size)
        var written = 0
        while (written < size) {
            if (!audioWriteStillWanted(gen, pass)) return false
            // A thread that lost ownership (see audioRunGen) must not touch the
            // track any more: the engine may have released it, or a newer video
            // may already be writing through the same AudioSession.
            if (audioThreadIsStale(runGen)) {
                audioPending = null
                return false
            }
            // Re-read the track every iteration: [AudioSession.trackFor] releases
            // and recreates it, so a stale local copy would be written into a
            // destroyed AudioTrack.
            val track = session.track
            if (powerSavePaused() || track == null) {
                holdBackPcm(buf)
                return true
            }
            val n = writeToTrack(session, track, buf, size - written)
            if (n < 0) return false
            if (n == 0) {
                // Buffer full: the track is draining it in real time.
                diagFullSleeps++
                Thread.sleep(retryDelayMs(session, size - written))
            } else {
                written += n
            }
        }
        return true
    }

    /**
     * Write PCM that a pause forced us to hold back, before the next chunk.
     *
     * @return false when the caller should re-evaluate (paused again/stopped).
     */
    private fun writePendingPcm(session: AudioSession, gen: Int, pass: Int): Boolean {
        val pending = audioPending ?: return true
        if (powerSavePaused()) return false
        val track = session.track ?: return true
        val remaining = pending.remaining()
        if (remaining <= 0) {
            audioPending = null
            return true
        }
        val n = writeToTrack(session, track, pending, remaining)
        if (n < 0) {
            audioPending = null
            return false
        }
        if (pending.remaining() <= 0) {
            audioPending = null
            return true
        }
        // Buffer full again: keep the rest for the next iteration.
        diagFullSleeps++
        Thread.sleep(retryDelayMs(session, remaining))
        return !powerSavePaused() && audioWriteStillWanted(gen, pass)
    }

    /**
     * Write [size] bytes from [buf] to [track], leaving [buf]'s position exactly
     * [n] bytes further along.
     *
     * The framework advances the position itself when it accepts bytes, but that
     * is an implementation detail of the write mode; normalising it here keeps
     * the streaming loop correct either way (a position that did not advance
     * would re-send the same samples forever - which sounds like a distorted,
     * stuttering loop).
     *
     * @return bytes accepted, or -1 when the track refused the call.
     */
    private fun writeToTrack(
        session: AudioSession,
        track: AudioTrack,
        buf: ByteBuffer,
        size: Int
    ): Int {
        if (size <= 0) return 0
        val before = buf.position()
        val n = try {
            track.write(buf, size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Throwable) {
            return -1
        }
        val advanced = when {
            buf.position() != before -> buf.position() - before
            n > 0 -> n
            else -> 0
        }
        buf.position(before + advanced)
        // Feed the "how much is still unplayed" counter (see AudioSession.unplayedMs).
        session.noteWrittenBytes(advanced)
        return advanced
    }

    /** Copy the not-yet-written tail of [buf] into [audioPending]. */
    private fun holdBackPcm(buf: ByteBuffer) {
        val remaining = buf.remaining()
        if (remaining <= 0) return
        val existing = audioPending
        val dst = if (existing != null && existing.capacity() >= remaining) {
            existing
        } else {
            ByteBuffer.allocate(remaining)
        }
        dst.clear()
        dst.put(buf)
        dst.flip()
        audioPending = dst
    }

    private fun audioWriteStillWanted(gen: Int, pass: Int): Boolean =
        generation() == gen && soundEnabled() && !Thread.currentThread().isInterrupted

    /**
     * True when this audio thread no longer owns the session: a newer
     * [start]/[stop] happened (see [audioRunGen]), so writing would either hit a
     * released AudioTrack or another video's audio.
     */
    private fun audioThreadIsStale(runGen: Int): Boolean = runGen != audioRunGen

    /**
     * Open the media descriptor for the audio pass on a helper thread with a
     * timeout, mirroring the video path: a cloud SAF provider can block for
     * dozens of seconds and the audio thread must stay interruptible.
     */
    private fun openAudioDescriptor(uriStr: String): AssetFileDescriptor? {
        val result = AtomicReference<AssetFileDescriptor?>(null)
        val abandon = AtomicBoolean(false)
        val helper = Thread({
            try {
                val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")
                if (abandon.get()) {
                    try { afd?.close() } catch (_: Exception) {}
                } else {
                    result.set(afd)
                    // The timeout can fire between the check above and this publish:
                    // the caller has already given up (it returned null), so nobody
                    // else would ever close this descriptor - one leaked fd per
                    // timed-out open, which accumulates into TooManyOpenFiles on the
                    // flaky cloud providers this code exists for. Take it back out and
                    // close it here; compareAndSet keeps the two sides from closing it
                    // twice.
                    if (abandon.get() && result.compareAndSet(afd, null)) {
                        try { afd?.close() } catch (_: Exception) {}
                    }
                }
            } catch (t: Throwable) {
                AppLog.d(tag, "Video audio source open failed: ${t.message}")
            }
        }, "VideoAudioOpen").apply {
            isDaemon = true
            start()
        }
        try {
            helper.join(AUDIO_OPEN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            abandon.set(true)
            helper.interrupt()
            Thread.currentThread().interrupt()
            return null
        }
        if (helper.isAlive) {
            abandon.set(true)
            helper.interrupt()
            AppLog.d(tag, "Timed out opening the video's audio stream")
            return null
        }
        return result.get()
    }

    private val audioPassLock = Object()

    /**
     * One AudioTrack, reused across playback passes (and across the pause/resume
     * pairs) so looping does not re-allocate a track every time.
     */
    private class AudioSession(private val tag: String) {
        /**
         * Serialises every track transition.
         *
         * The audio thread writes through `track` while the engine thread can
         * call [pause] (stop / power save), [resume] (visibility) and above all
         * [trackFor] -> [release], which drops the AudioTrack the writer is using.
         * @Volatile alone only made the reference visible, it never stopped that
         * interleaving: `trackFor` could release the AudioTrack while the audio
         * thread was inside `write()`, and a stale thread could flip [playing]
         * back on right after [pause] silenced it - two videos' audio
         * overlapping, or sound returning after a switch.
         */
        private val lock = Any()
        // Written by the audio thread, read/paused/released from the engine thread
        // (stop(), release()). Without @Volatile the engine could keep acting
        // on a stale track - i.e. fail to silence the old video, or write into a
        // released AudioTrack.
        @Volatile
        var track: AudioTrack? = null
            private set
        /** Buffer length in ms, for the start-up log (diagnostics). */
        @Volatile
        var bufferMillis = 0
            private set
        private var sampleRate = 0
        private var channels = 0
        private var encoding = VideoSound.PCM_ENCODING
        @Volatile
        private var playing = false
        /**
         * Set while the engine must stay silent for a reason OUTSIDE the media
         * ("our own UI is in the foreground"). [pause] alone is not enough: the
         * audio thread calls [ensurePlaying]/[resume]/[restart] on every playback
         * pass, on a decoder format change and on the first visible frame, and any
         * of those would turn the sound back on behind the UI. Those helpers
         * therefore refuse to play while this flag is set; only
         * [clearPolicyMute] (the engine's explicit unmute) lifts it.
         */
        @Volatile
        private var policyMuted = false
        /**
         * 累计写入轨道的字节数（flush / 重建轨道后归零），只用于 [unplayedMs] 的
         * "已写入但没播掉"推算。
         */
        private var writtenBytes = 0L
        /** 音量斜坡的令牌：任何新的停/起都会让正在跑的斜坡作废。 */
        private var fadeGen = 0

        fun trackFor(
            wantedSampleRate: Int,
            wantedChannels: Int,
            wantedEncoding: Int = VideoSound.PCM_ENCODING
        ): AudioTrack? = synchronized(lock) {
            val existing = track
            if (existing != null && wantedSampleRate == sampleRate &&
                wantedChannels == channels && wantedEncoding == encoding
            ) {
                return@synchronized existing
            }
            releaseLocked()
            val created = create(wantedSampleRate, wantedChannels, wantedEncoding)
                ?: return@synchronized null
            track = created
            sampleRate = wantedSampleRate
            channels = wantedChannels
            encoding = wantedEncoding
            created
        }

        /**
         * Start (or restart) playback with an empty buffer. Only for a NEW media:
         * flushing between playback passes would cut the music every loop.
         */
        fun restart() = synchronized(lock) {
            val t = track ?: return@synchronized
            try {
                t.pause()
                t.flush()
                // flush() 清空轨道：已写入量必须同步归零，否则 unplayedMs 会算出一个
                // 巨大且错误的"未播量"。
                writtenBytes = 0L
                // 新素材：作废任何正在跑的音量斜坡，并把音量复位（否则下一次
                // play() 会接着上一段斜坡的 0 音量，听起来像没声音）。
                ++fadeGen
                try {
                    t.setVolume(1f)
                } catch (_: Throwable) {
                }
                // A new media still drops the previous media's buffered samples
                // (otherwise they would be heard later, when the UI leaves), but
                // it does not play while the engine is policy-muted.
                if (policyMuted) {
                    playing = false
                } else {
                    t.play()
                    playing = true
                }
            } catch (_: Throwable) {
                playing = false
            }
        }

        /** Make sure a (possibly paused) track is playing again. */
        fun ensurePlaying() {
            if (policyMuted) return
            if (playing) return
            resume()
        }

        /** 真正在出声：存在轨道且已经 play() 成功。 */
        fun isPlaying(): Boolean = synchronized(lock) { playing && track != null }

        /** 已播帧数（轨道创建/flush 后从 0 起）；没有轨道时为 0。诊断用。 */
        fun playbackHeadFrames(): Long = synchronized(lock) {
            try {
                track?.playbackHeadPosition?.toLong() ?: 0L
            } catch (_: Throwable) {
                0L
            }
        }

        /** 当前轨道的采样率（0 = 还没有轨道）。诊断用。 */
        val sampleRateSafe: Int get() = synchronized(lock) { sampleRate }

        /** 当前轨道的字节吞吐（B/s，0 = 还没有轨道）：按缺口休眠要用它。 */
        fun bytesPerSecond(): Long = synchronized(lock) {
            if (sampleRate <= 0) return@synchronized 0L
            sampleRate.toLong() * channels.coerceAtLeast(1) *
                VideoSound.bytesPerSampleFor(encoding)
        }

        /** AudioTrack 累计欠载次数：>0 就说明真的出现过断流，诊断用。 */
        fun underrunCount(): Int = synchronized(lock) {
            try {
                track?.underrunCount ?: 0
            } catch (_: Throwable) {
                0
            }
        }

        /**
         * 已写入轨道但还没播掉的毫秒数（诊断用，判循环边界有没有声缝）。
         *
         * 轨道没有 flush 时 playbackHeadPosition 是累计值，所以"未播量"= 累计写入帧
         * − 已播帧。写入量在这里自己记（[noteWrittenBytes]），flush / 重建轨道时归零。
         */
        fun unplayedMs(): Int = synchronized(lock) {
            val t = track ?: return@synchronized 0
            if (sampleRate <= 0) return@synchronized 0
            val bytesPerFrame = channels.coerceAtLeast(1) * VideoSound.bytesPerSampleFor(encoding)
            if (bytesPerFrame <= 0) return@synchronized 0
            val head = try {
                t.playbackHeadPosition.toLong()
            } catch (_: Throwable) {
                0L
            }
            val frames = (writtenBytes / bytesPerFrame - head).coerceAtLeast(0L)
            (frames * 1_000L / sampleRate).toInt()
        }

        /** Count PCM handed to the track (see [unplayedMs]). */
        fun noteWrittenBytes(bytes: Int) {
            if (bytes <= 0) return
            synchronized(lock) { writtenBytes += bytes }
        }

        /** Silence for a reason outside the media (see [policyMuted]). */
        fun muteForPolicy() {
            policyMuted = true
            // 斜坡而不是硬切：自己的 UI 打开时要"跟着这一下静下来"，24ms 仍然算即时，
            // 但不会在波形中途切断（后者是一次咔哒）。
            fadeOutAndPause()
        }

        /** Lift [muteForPolicy]; the caller re-starts the audio itself. */
        fun clearPolicyMute() {
            policyMuted = false
        }

        /**
         * 未播满之前先按缓坡降音量再暂停：直接 `pause()` 会在波形中途切断，听感是
         * 一次咔哒（切片段、锁屏静音、自己的 UI 打开都会踩到）。24ms 的斜坡在独立
         * 线程上跑，调用方（引擎主线程 / 音频线程）不会被阻塞。
         */
        fun fadeOutAndPause(fadeMs: Long = FADE_MS) {
            val pair = synchronized(lock) {
                if (!playing) return
                val tr = track ?: return
                (++fadeGen) to tr
            }
            val gen = pair.first
            val t = pair.second
            Thread({
                val steps = (fadeMs / FADE_STEP_MS).coerceAtLeast(1L).toInt()
                for (i in steps downTo 1) {
                    if (synchronized(lock) { fadeGen != gen }) return@Thread
                    try {
                        t.setVolume(i.toFloat() / steps)
                    } catch (_: Throwable) {
                        return@Thread
                    }
                    try {
                        Thread.sleep(FADE_STEP_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                synchronized(lock) {
                    if (fadeGen != gen) return@synchronized
                    pause()
                    // 复位音量，下一次 play() 不会从 0 开始（见 resume 的淡入）。
                    try {
                        t.setVolume(1f)
                    } catch (_: Throwable) {
                    }
                }
            }, "VideoAudioFade").apply { isDaemon = true }.start()
        }

        fun pause() = synchronized(lock) {
            if (!playing) return@synchronized
            playing = false
            try { track?.pause() } catch (_: Throwable) {}
        }

        fun resume() = synchronized(lock) {
            if (policyMuted) return@synchronized
            val t = track ?: return@synchronized
            val gen = ++fadeGen
            try {
                t.setVolume(0f)
                t.play()
                playing = true
            } catch (_: Throwable) {
                playing = false
                return@synchronized
            }
            // 24ms 淡入：从 0 起播会在波形中途接上（同样是一次咔哒）。
            val steps = (FADE_MS / FADE_STEP_MS).coerceAtLeast(1L).toInt()
            Thread({
                for (i in 1..steps) {
                    if (synchronized(lock) { fadeGen != gen }) return@Thread
                    try {
                        t.setVolume(i.toFloat() / steps)
                    } catch (_: Throwable) {
                        return@Thread
                    }
                    try {
                        Thread.sleep(FADE_STEP_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "VideoAudioFade").apply { isDaemon = true }.start()
        }

        fun release() = synchronized(lock) { releaseLocked() }

        /** [release] body; the caller already holds [lock]. */
        private fun releaseLocked() {
            val t = track ?: return
            track = null
            playing = false
            policyMuted = false
            try { t.pause() } catch (_: Throwable) {}
            try { t.flush() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
            writtenBytes = 0L
            ++fadeGen
            sampleRate = 0
            channels = 0
        }

        private fun create(
            wantedSampleRate: Int,
            wantedChannels: Int,
            wantedEncoding: Int
        ): AudioTrack? {
            return try {
                val mask = VideoSound.channelMaskFor(wantedChannels)
                val bytesPerSample = VideoSound.bytesPerSampleFor(wantedEncoding)
                val minBytes = AudioTrack.getMinBufferSize(
                    wantedSampleRate, mask, wantedEncoding
                )
                val bufferBytes = VideoSound.bufferBytesFor(
                    if (minBytes > 0) minBytes else 0,
                    wantedSampleRate,
                    wantedChannels,
                    bytesPerSample
                )
                bufferMillis = (bufferBytes.toLong() * 1_000L /
                    (wantedSampleRate.toLong() * wantedChannels.coerceAtLeast(1) *
                        bytesPerSample).coerceAtLeast(1L)).toInt()
                val attrs = AudioAttributes.Builder()
                    // USAGE_MEDIA + CONTENT_TYPE_MOVIE: the system media volume
                    // (and mute) applies, exactly like any other video sound.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(wantedEncoding)
                    .setSampleRate(wantedSampleRate)
                    .setChannelMask(mask)
                    .build()
                val created = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (created.state != AudioTrack.STATE_INITIALIZED) {
                    try { created.release() } catch (_: Throwable) {}
                    AppLog.e(tag, "AudioTrack was not initialized (video sound off)")
                    return null
                }
                // Report the buffer the framework really allocated, not just the
                // one requested: that is what tells crackling apart from silence.
                try {
                    val frames = created.bufferSizeInFrames
                    if (frames > 0 && wantedSampleRate > 0) {
                        bufferMillis = (frames.toLong() * 1_000L / wantedSampleRate).toInt()
                    }
                } catch (_: Throwable) {
                }
                created.setVolume(1f)
                created
            } catch (t: Throwable) {
                AppLog.e(tag, "AudioTrack create failed: ${t.message}")
                null
            }
        }

        private companion object {
            /** 音量斜坡长度与步长：24ms / 4ms = 6 步，足以吃掉波形切断的咔哒。 */
            const val FADE_MS = 24L
            const val FADE_STEP_MS = 4L
        }
    }
}
