package com.wallpaperswitcher.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The GIF frame-delay parser drives the wallpaper's GIF ticker (see
 * [GifTiming]): a wrong answer means either a stalled animation (delays far too
 * long) or the old redundant redraws (delays missing). These tests pin the
 * block walk down with hand-built streams.
 */
class GifTimingTest {

    @Test
    fun `reads one delay per frame`() {
        val gif = gif(
            frame(100, imageData = byteArrayOf(2, 0x0A, 0x0B, 0)),
            frame(50, imageData = byteArrayOf(2, 0x0A, 0x0B, 0)),
            frame(0, imageData = byteArrayOf(2, 0x0A, 0x0B, 0))
        )
        assertArrayEquals(intArrayOf(100, 50, 0), GifTiming.parse(ByteArrayInputStream(gif)))
    }

    @Test
    fun `skips a global colour table before the frames`() {
        val gif = gif(
            frame(120, imageData = byteArrayOf(2, 0x0A, 0x0B, 0)),
            frame(120, imageData = byteArrayOf(2, 0x0A, 0x0B, 0)),
            globalColorTableEntries = 4
        )
        assertArrayEquals(intArrayOf(120, 120), GifTiming.parse(ByteArrayInputStream(gif)))
    }

    @Test
    fun `skips a local colour table inside a frame`() {
        val gif = gif(
            frame(40, imageData = byteArrayOf(2, 0x0A, 0x0B, 0), localColorTableEntries = 2),
            frame(80, imageData = byteArrayOf(2, 0x0A, 0x0B, 0))
        )
        assertArrayEquals(intArrayOf(40, 80), GifTiming.parse(ByteArrayInputStream(gif)))
    }

    @Test
    fun `other extensions are skipped without eating the next frame`() {
        val comment = byteArrayOf(
            0x21, 0xFE.toByte(), 0x03,
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0
        )
        val gif = gif(
            comment,
            frame(200, imageData = byteArrayOf(2, 0x0A, 0x0B, 0))
        )
        assertArrayEquals(intArrayOf(200), GifTiming.parse(ByteArrayInputStream(gif)))
    }

    @Test
    fun `non-gif input yields null`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertNull(GifTiming.parse(ByteArrayInputStream(png)))
    }

    @Test
    fun `animated stream without delays yields null`() {
        val gif = gif(frameWithoutDelay(imageData = byteArrayOf(2, 0x0A, 0x0B, 0)))
        assertNull(GifTiming.parse(ByteArrayInputStream(gif)))
    }

    @Test
    fun `truncated stream keeps what was already parsed and never throws`() {
        val full = gif(
            frame(90, imageData = byteArrayOf(2, 0x0A, 0x0B, 0)),
            frame(90, imageData = byteArrayOf(2, 0x0A, 0x0B, 0))
        )
        // Cut inside the second frame's image data.
        val cut = full.copyOf(full.size - 2)
        assertArrayEquals(intArrayOf(90, 90), GifTiming.parse(ByteArrayInputStream(cut)))
    }

    // ---- stream builders -------------------------------------------------

    private fun gif(
        vararg blocks: ByteArray,
        globalColorTableEntries: Int = 0
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll("GIF89a".map { it.code.toByte() })
        // Logical screen descriptor: width, height, packed, background, aspect.
        out.addAll(listOf(1, 0, 1, 0))
        val packed = if (globalColorTableEntries > 0) {
            0x80 or (colorTableBits(globalColorTableEntries) - 1)
        } else {
            0
        }
        out.add(packed.toByte())
        out.addAll(listOf(0, 0))
        if (globalColorTableEntries > 0) {
            repeat(globalColorTableEntries * 3) { out.add(0) }
        }
        blocks.forEach { out.addAll(it.toList()) }
        out.add(0x3B) // trailer
        return out.toByteArray()
    }

    private fun colorTableBits(entries: Int): Int {
        var bits = 1
        while ((1 shl bits) < entries) bits++
        return bits
    }

    private fun frame(
        delayMs: Int,
        imageData: ByteArray,
        localColorTableEntries: Int = 0
    ): ByteArray {
        val hundredths = delayMs / 10
        val gce = byteArrayOf(
            0x21, 0xF9.toByte(), 0x04, 0x00,
            (hundredths and 0xFF).toByte(),
            ((hundredths shr 8) and 0xFF).toByte(),
            0x00, 0x00
        )
        return gce + image(imageData, localColorTableEntries)
    }

    private fun frameWithoutDelay(imageData: ByteArray): ByteArray =
        image(imageData, localColorTableEntries = 0)

    private fun image(imageData: ByteArray, localColorTableEntries: Int): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x2C) // image descriptor
        out.addAll(listOf(0, 0, 0, 0)) // left, top
        out.addAll(listOf(1, 0, 1, 0)) // width, height
        out.add(
            if (localColorTableEntries > 0) {
                (0x80 or (colorTableBits(localColorTableEntries) - 1)).toByte()
            } else {
                0
            }
        )
        if (localColorTableEntries > 0) {
            repeat(localColorTableEntries * 3) { out.add(0) }
        }
        out.add(2) // LZW minimum code size
        out.addAll(imageData.toList())
        return out.toByteArray()
    }
}
