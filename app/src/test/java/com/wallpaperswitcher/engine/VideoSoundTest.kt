package com.wallpaperswitcher.engine

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSoundTest {

    @Test
    fun keepsDeclaredSampleRate() {
        assertEquals(44_100, VideoSound.sampleRateFor(44_100))
        assertEquals(8_000, VideoSound.sampleRateFor(8_000))
        assertEquals(48_000, VideoSound.sampleRateFor(48_000))
        assertEquals(192_000, VideoSound.sampleRateFor(192_000))
    }

    @Test
    fun fallsBackWhenSampleRateIsMissingOrAbsurd() {
        assertEquals(44_100, VideoSound.sampleRateFor(0))
        assertEquals(44_100, VideoSound.sampleRateFor(-1))
        assertEquals(44_100, VideoSound.sampleRateFor(1))
        assertEquals(44_100, VideoSound.sampleRateFor(400_000))
    }

    @Test
    fun channelsFollowTheDecodedPcmLayout() {
        assertEquals(1, VideoSound.playbackChannelsFor(0))
        assertEquals(1, VideoSound.playbackChannelsFor(1))
        assertEquals(2, VideoSound.playbackChannelsFor(2))
        // 5.1 must stay 5.1: the platform downmixes it, but PCM reinterpreted as
        // stereo would play at 3x speed.
        assertEquals(6, VideoSound.playbackChannelsFor(6))
        assertEquals(8, VideoSound.playbackChannelsFor(8))
    }

    @Test
    fun channelMaskMatchesChannelCount() {
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, VideoSound.channelMaskFor(1))
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, VideoSound.channelMaskFor(2))
        assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, VideoSound.channelMaskFor(6))
        assertEquals(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, VideoSound.channelMaskFor(8))
    }

    @Test
    fun bufferCoversTheVideoRestartGap() {
        // 44100Hz stereo 16-bit = 176400 B/s -> 350ms = 61740 bytes, which is
        // well above any platform minimum and covers the video codec restart
        // between loops.
        assertEquals(61_740, VideoSound.bufferBytesFor(1_024, 44_100, 2))
    }

    @Test
    fun bufferNeverGoesBelowThePlatformMinimum() {
        // A platform minimum bigger than the target wins (padded to whole
        // samples), otherwise AudioTrack would reject the size.
        val min = 40_000
        val bytes = VideoSound.bufferBytesFor(min, 8_000, 1)
        assertTrue("buffer $bytes must cover the platform minimum", bytes >= min)
        assertEquals(0, bytes % 2)
    }

    @Test
    fun bufferIsEvenForEveryFormat() {
        for (rate in listOf(8_000, 22_050, 44_100, 48_000, 192_000)) {
            for (channels in listOf(1, 2)) {
                for (min in listOf(0, 1, 999, 4_096, 100_003)) {
                    val bytes = VideoSound.bufferBytesFor(min, rate, channels)
                    assertEquals(
                        "rate=$rate ch=$channels min=$min",
                        0,
                        bytes % 2
                    )
                }
            }
        }
    }
}
