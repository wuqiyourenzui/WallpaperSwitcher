package com.wallpaperswitcher.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * `decodeAndLearnSize` buffers the head of a media file instead of rewinding a
 * markable provider stream (HyperOS' ExifInterface broke the rewind window, see
 * [BitmapUtils.readHead]). These tests pin the two invariants that make the
 * single-provider-read path correct: the head never over-reads, and head + rest
 * reproduces the original bytes exactly.
 */
class BitmapHeadBufferTest {

    @Test
    fun `head stops at the limit and leaves the rest unread`() {
        val source = ByteArray(1000) { (it % 251).toByte() }
        val stream = ByteArrayInputStream(source)

        val head = BitmapUtils.readHead(stream, 256)

        assertEquals(256, head.size)
        assertArrayEquals(source.copyOfRange(0, 256), head)
        // The caller continues with the same stream (SequenceInputStream), so the
        // bytes after the head must still be there.
        assertArrayEquals(source.copyOfRange(256, 1000), stream.readBytes())
    }

    @Test
    fun `short files are returned whole`() {
        val source = byteArrayOf(1, 2, 3, 4, 5)
        val head = BitmapUtils.readHead(ByteArrayInputStream(source), 4096)
        assertArrayEquals(source, head)
    }

    @Test
    fun `head plus rest reproduces the file byte for byte`() {
        val source = ByteArray(5000) { (it * 7 % 256).toByte() }
        val stream = ByteArrayInputStream(source)

        val head = BitmapUtils.readHead(stream, 1024)
        val whole = SequenceInputStream(ByteArrayInputStream(head), stream).readBytes()

        assertArrayEquals(source, whole)
    }

    @Test
    fun `a stream that fails mid read yields what was read so far`() {
        val source = ByteArray(2048) { it.toByte() }
        // Fails after the first 600 bytes, like a provider dying mid-file.
        val flaky = object : FilterInputStream(ByteArrayInputStream(source)) {
            private var served = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 600) throw IOException("provider went away")
                val n = super.read(b, off, minOf(len, 600 - served))
                if (n > 0) served += n
                return n
            }
        }

        val head = BitmapUtils.readHead(flaky, 4096)

        assertEquals(600, head.size)
        assertArrayEquals(source.copyOfRange(0, 600), head)
    }

    @Test
    fun `small chunked reads still fill the head`() {
        val source = ByteArray(3000) { (it % 97).toByte() }
        // A provider that returns at most 100 bytes per call.
        val chunked: InputStream = object : InputStream() {
            private val delegate = ByteArrayInputStream(source)
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                delegate.read(b, off, minOf(len, 100))
        }

        val head = BitmapUtils.readHead(chunked, 2048)

        assertEquals(2048, head.size)
        assertArrayEquals(source.copyOfRange(0, 2048), head)
    }
}
