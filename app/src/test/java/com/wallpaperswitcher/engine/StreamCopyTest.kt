package com.wallpaperswitcher.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class StreamCopyTest {

    @Test
    fun copiesEverythingAndReportsEveryChunk() {
        val source = ByteArray(1000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        var chunked = 0
        val result = StreamCopy.copy(
            input = ByteArrayInputStream(source),
            output = out,
            maxBytes = Long.MAX_VALUE,
            bufferSize = 128,
            onChunk = { _, read -> chunked += read },
        )
        assertTrue(result.complete)
        assertEquals(1000L, result.bytes)
        assertEquals(1000, chunked)
        assertArrayEquals(source, out.toByteArray())
    }

    @Test
    fun abortsBeforeWritingPastTheLimit() {
        val out = ByteArrayOutputStream()
        val result = StreamCopy.copy(
            input = ByteArrayInputStream(ByteArray(10)),
            output = out,
            maxBytes = 4,
            bufferSize = 4,
        )
        assertFalse(result.complete)
        assertTrue(result.exceededLimit)
        assertEquals(4, out.size())
        assertEquals(4L, result.bytes)
    }

    @Test
    fun cancelStopsBetweenChunksAndKeepsWhatWasCopied() {
        val out = ByteArrayOutputStream()
        var checks = 0
        val result = StreamCopy.copy(
            input = ByteArrayInputStream(ByteArray(100)),
            output = out,
            maxBytes = Long.MAX_VALUE,
            bufferSize = 4,
            isCancelled = { ++checks > 1 },
        )
        assertTrue(result.cancelled)
        assertFalse(result.complete)
        assertEquals(4, out.size())
        assertEquals(4L, result.bytes)
    }

    @Test
    fun deadlineStopsATricklingCopy() {
        val out = ByteArrayOutputStream()
        val times = ArrayDeque(listOf(0L, 11L))
        val result = StreamCopy.copy(
            input = ByteArrayInputStream(ByteArray(100)),
            output = out,
            maxBytes = Long.MAX_VALUE,
            deadlineAtMs = 10L,
            nowMs = { times.removeFirst() },
            bufferSize = 4,
        )
        assertTrue(result.timedOut)
        assertFalse(result.complete)
        assertEquals(4L, result.bytes)
    }

    @Test
    fun zeroLengthReadsDoNotSpinForever() {
        val source = byteArrayOf(1, 2, 3, 4, 5)
        var zeros = 1
        val input = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < source.size) source[pos++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (zeros > 0) {
                    zeros--
                    return 0
                }
                return super.read(b, off, len)
            }
        }
        val out = ByteArrayOutputStream()
        val result = StreamCopy.copy(
            input = input,
            output = out,
            maxBytes = Long.MAX_VALUE,
            bufferSize = 8,
        )
        assertTrue(result.complete)
        assertArrayEquals(source, out.toByteArray())
    }
}
