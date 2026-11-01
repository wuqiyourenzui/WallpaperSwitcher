package com.wallpaperswitcher.engine

import android.media.AudioFormat

/**
 * Pure helpers for the video-wallpaper audio path.
 *
 * Everything here is a plain calculation so it can be unit-tested without a
 * device (the Android constants used are compile-time ints).
 */
internal object VideoSound {

    /** Output encoding of the audio decoder, and what AudioTrack is fed. */
    const val PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    /** 32-bit float PCM: a few decoders emit this instead of 16-bit. */
    const val PCM_ENCODING_FLOAT = AudioFormat.ENCODING_PCM_FLOAT

    /** Bytes per sample of [PCM_ENCODING]. */
    const val BYTES_PER_SAMPLE = 2

    private const val MIN_SAMPLE_RATE = 8_000
    private const val MAX_SAMPLE_RATE = 192_000
    /**
     * Target buffer length. Long enough to cover scheduling jitter and the
     * moment between an audio pass ending and the next one starting, short
     * enough that a stop/pause is still felt as immediate.
     *
     * 实测结论（别再靠加大缓冲去补循环缝）：350 与 700 两档跑同一条 8.7s 循环短片，
     * 边界处"等视频重启"的时长会跟着缓冲一起变长（350 → waited 440ms；700 →
     * waited 768ms），**缺口恒定 ≈ 视频重启时间 95–121ms**。缺口来自视频侧重启，
     * 不是缓冲不够；补它要靠边界继续预写（见 VideoAudioPlayer）。
     */
    private const val TARGET_BUFFER_MS = 350

    /** Sample rate to use, falling back when the track does not declare one. */
    fun sampleRateFor(declaredSampleRate: Int): Int =
        if (declaredSampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) declaredSampleRate else 44_100

    /**
     * Channels to hand to AudioTrack, which must match the PCM the decoder
     * produces. Feeding 6-channel PCM into a stereo track (the first version)
     * made multi-channel media play at 3x speed - the platform downmixes a
     * correctly declared surround track to the output device instead.
     */
    fun playbackChannelsFor(decodedChannels: Int): Int = when {
        decodedChannels <= 1 -> 1
        decodedChannels == 2 -> 2
        decodedChannels <= 6 -> 6
        else -> 8
    }

    /** AudioTrack channel mask matching [playbackChannelsFor]. */
    fun channelMaskFor(channels: Int): Int = when {
        channels <= 1 -> AudioFormat.CHANNEL_OUT_MONO
        channels == 2 -> AudioFormat.CHANNEL_OUT_STEREO
        channels <= 6 -> AudioFormat.CHANNEL_OUT_5POINT1
        else -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    }

    /**
     * Encoding to play: whatever the decoder says it produces, as long as
     * AudioTrack can consume it. Feeding float PCM into a 16-bit track (or the
     * other way round) turns speech into noise, so this must never guess.
     */
    fun playbackEncodingFor(decoderEncoding: Int): Int = when (decoderEncoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> PCM_ENCODING_FLOAT
        else -> PCM_ENCODING
    }

    /** Bytes per sample for [playbackEncodingFor]'s result. */
    fun bytesPerSampleFor(playbackEncoding: Int): Int =
        if (playbackEncoding == PCM_ENCODING_FLOAT) 4 else BYTES_PER_SAMPLE

    /**
     * Buffer size in bytes: at least the platform minimum and at least
     * [TARGET_BUFFER_MS] of audio, which is the runway that keeps playback
     * gapless while the video decoder restarts between loops. Always even:
     * 16-bit PCM is written in whole samples.
     */
    fun bufferBytesFor(
        minBufferBytes: Int,
        sampleRate: Int,
        channels: Int,
        bytesPerSample: Int = BYTES_PER_SAMPLE
    ): Int {
        // All of this in Long: a 192kHz 8-channel float format is ~6.1MB/s, and
        // bytesPerSecond * 350ms overflowed an Int (giving a negative target, and
        // then a buffer as small as the platform minimum - the classic cause of
        // crackling).
        val bytesPerSecond = sampleRate.coerceAtLeast(MIN_SAMPLE_RATE).toLong() *
            channels.coerceAtLeast(1) * bytesPerSample.coerceAtLeast(1)
        val target = bytesPerSecond * TARGET_BUFFER_MS / 1_000L
        val minimum = minBufferBytes.coerceAtLeast(0).toLong()
        // Never below twice the platform minimum: enough headroom that a
        // non-blocking writer survives scheduling jitter.
        val wanted = maxOf(target, minimum * 2)
        return wanted.coerceAtMost(Int.MAX_VALUE.toLong() - 2).toInt() and 0x7FFFFFFE
    }
}
