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
     * [AUDIO_WRITE_RETRY_MS] paces a full AudioTrack buffer (a blocking write is
     * deliberately avoided - see [writePcm]). [AUDIO_OUTPUT_WAIT_US] is the
     * timeout of the output dequeue when the decoder has nothing to chew on.
     */
    private val AUDIO_WRITE_RETRY_MS = 5L
    private val AUDIO_OUTPUT_WAIT_US = 10_000L
    /** Bound for opening the audio source (same idea as the video's 15s). */
    private val AUDIO_OPEN_TIMEOUT_MS = 12_000L

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
        audioSession.pause()
        if (thread != null) {
            thread.interrupt()
            synchronized(audioPassLock) { audioPassLock.notifyAll() }
        }
    }

    /** Silence instantly (power-save setter), keeping the track for the resume. */
    fun pauseTrack() {
        audioSession.pause()
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
        while (generation() == gen && soundEnabled() &&
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
            while (generation() == gen && soundEnabled() &&
                !Thread.currentThread().isInterrupted
            ) {
                val nextPass = awaitNextVideoPass(pass, gen) ?: break
                pass = nextPass
                // Rewind for the new pass instead of rebuilding anything. The
                // video restarts its own codec right now; the audio already
                // buffered in the AudioTrack covers that restart, so playback
                // stays gapless and the two stay in lockstep (no drift, because
                // the audio waits for the video at every pass).
                // The first pass starts where the video resumed (lock-release
                // position); later passes rewind to 0 so the audio loops with the
                // picture.
                extractor.seekTo(
                    if (firstPass) startPositionUs.coerceAtLeast(0L) else 0L,
                    MediaExtractor.SEEK_TO_CLOSEST_SYNC
                )
                decoder.flush()
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
                var paused = false
                var passDone = false
                var inputDone = false
                while (!passDone && generation() == gen &&
                    videoPassCounter.get() == pass && soundEnabled() &&
                    !Thread.currentThread().isInterrupted
                ) {
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
                                if (outBuf != null &&
                                    !writePcm(session, outBuf, info.offset, info.size, gen, pass, runGen)
                                ) {
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
                                        passDone = true
                                        exitReason = "pass boundary (pass=$pass)"
                                    } else {
                                        exitReason = "PCM write refused (pass=$pass)"
                                        return
                                    }
                                }
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                passDone = true
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
            val n = writeToTrack(track, buf, size - written)
            if (n < 0) return false
            if (n == 0) {
                // Buffer full: the track is draining it in real time.
                Thread.sleep(AUDIO_WRITE_RETRY_MS)
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
        val n = writeToTrack(track, pending, remaining)
        if (n < 0) {
            audioPending = null
            return false
        }
        if (pending.remaining() <= 0) {
            audioPending = null
            return true
        }
        // Buffer full again: keep the rest for the next iteration.
        Thread.sleep(AUDIO_WRITE_RETRY_MS)
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
    private fun writeToTrack(track: AudioTrack, buf: ByteBuffer, size: Int): Int {
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
        generation() == gen && videoPassCounter.get() == pass &&
            soundEnabled() && !Thread.currentThread().isInterrupted

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

        /** Silence for a reason outside the media (see [policyMuted]). */
        fun muteForPolicy() {
            policyMuted = true
            pause()
        }

        /** Lift [muteForPolicy]; the caller re-starts the audio itself. */
        fun clearPolicyMute() {
            policyMuted = false
        }

        fun pause() = synchronized(lock) {
            if (!playing) return@synchronized
            playing = false
            try { track?.pause() } catch (_: Throwable) {}
        }

        fun resume() = synchronized(lock) {
            if (policyMuted) return@synchronized
            val t = track ?: return@synchronized
            try {
                t.play()
                playing = true
            } catch (_: Throwable) {
                playing = false
            }
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
    }
}
