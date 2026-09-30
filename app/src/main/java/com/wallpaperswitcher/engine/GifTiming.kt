package com.wallpaperswitcher.engine

import android.content.Context
import android.net.Uri
import com.wallpaperswitcher.util.AppLog
import java.io.InputStream

/**
 * The frame delays of an animated GIF, read from the file itself.
 *
 * The wallpaper engine used to re-rasterize and re-upload the current GIF frame
 * on a fixed 20fps ticker. That is right for a 20fps GIF and pure waste for
 * everything slower: the app's own sample GIFs are 100-120ms per frame, so two
 * ticks out of three re-drew and re-uploaded a frame the screen already showed
 * (measured: 25 distinct frames per 3s of animation, drawn and uploaded 60
 * times). The platform does not expose the timing (`AnimatedImageDrawable` only
 * reports its next frame through `Drawable.Callback` for the first few frames,
 * verified on-device), so the delays are parsed here - the same numbers Android
 * itself would use.
 *
 * Only the graphics-control-extension delays are collected; everything else in
 * the stream is skipped. A file that is not a GIF (animated WebP/HEIF also
 * decode to `AnimatedImageDrawable`), is truncated, or declares no delay at all
 * returns null and the caller keeps its fixed cadence.
 */
object GifTiming {

    private const val TAG = "GifTiming"

    /**
     * Upper bound on parsed frames. A GIF longer than this keeps playing: the
     * caller reuses the last known delay for the remaining frames.
     */
    private const val MAX_FRAMES = 1_024

    /**
     * Upper bound on bytes inspected. The delay of every frame sits in its own
     * graphics-control extension, so a complete answer needs the whole stream,
     * but a pathological file must not be walked to the end for a timing hint.
     */
    private const val MAX_SCAN_BYTES = 32 * 1024 * 1024

    /**
     * Frame delays of [uriStr] in milliseconds, or null when they cannot be
     * read (not a GIF, unreadable provider, no delays). Never throws: timing is
     * an optimisation, and a wallpaper must still play without it.
     */
    fun frameDelaysMs(context: Context, uriStr: String): IntArray? {
        val delays = try {
            context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { parse(it) }
        } catch (t: Throwable) {
            AppLog.d(TAG, "GIF timing unreadable: ${t.message}")
            null
        }
        if (delays != null) {
            AppLog.d(
                TAG,
                "GIF timing: ${delays.size} frames, first delays " +
                    delays.take(6).joinToString(prefix = "[", postfix = "]") + "ms"
            )
        }
        return delays
    }

    /**
     * Parse the per-frame delays of the GIF in [input]. Exposed for tests.
     */
    internal fun parse(input: InputStream): IntArray? {
        val reader = ByteReader(input)
        // Header: GIF87a / GIF89a.
        if (reader.next() != 'G'.code || reader.next() != 'I'.code ||
            reader.next() != 'F'.code
        ) {
            return null
        }
        repeat(3) { if (reader.next() < 0) return null }
        // Logical screen descriptor (7 bytes); the packed field is index 4.
        val descriptor = reader.readExact(7) ?: return null
        val packed = descriptor[4].toInt() and 0xFF
        if (packed and 0x80 != 0) {
            // Global colour table.
            reader.skip(3L * (1 shl ((packed and 0x07) + 1)))
        }
        val delays = ArrayList<Int>(64)
        while (delays.size < MAX_FRAMES && reader.total <= MAX_SCAN_BYTES) {
            when (val block = reader.next()) {
                -1 -> break
                0x3B -> break // trailer
                0x21 -> {
                    // Extension: label, then sub-blocks.
                    val label = reader.next()
                    if (label < 0) break
                    if (label == 0xF9) {
                        val size = reader.next()
                        if (size < 0) break
                        if (size >= 3) {
                            val body = reader.readExact(size) ?: break
                            // Delay is a little-endian 16-bit value in 1/100s.
                            val hundredths = (body[1].toInt() and 0xFF) or
                                ((body[2].toInt() and 0xFF) shl 8)
                            delays.add(hundredths * 10)
                        } else {
                            reader.skip(size.toLong())
                        }
                        reader.skipSubBlocks() ?: break
                    } else {
                        reader.skipSubBlocks() ?: break
                    }
                }
                0x2C -> {
                    // Image descriptor (9 bytes) + optional local colour table
                    // + LZW minimum code size + image data sub-blocks.
                    val image = reader.readExact(9) ?: break
                    val imagePacked = image[8].toInt() and 0xFF
                    if (imagePacked and 0x80 != 0) {
                        reader.skip(3L * (1 shl ((imagePacked and 0x07) + 1)))
                    }
                    if (reader.next() < 0) break // LZW minimum code size
                    reader.skipSubBlocks() ?: break
                }
                else -> break // unknown block: stop instead of guessing
            }
        }
        return delays.takeIf { it.isNotEmpty() }?.toIntArray()
    }

    /** Byte-wise reader with a scan budget; skips still count as consumed. */
    private class ByteReader(private val input: InputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var length = 0
        private var position = 0

        /** Bytes consumed so far (the scan budget; not an exact file offset). */
        var total = 0L
            private set

        fun next(): Int {
            if (position >= length) {
                length = try {
                    input.read(buffer)
                } catch (_: Throwable) {
                    -1
                }
                position = 0
                if (length <= 0) return -1
            }
            total++
            return buffer[position++].toInt() and 0xFF
        }

        fun readExact(count: Int): ByteArray? {
            if (count <= 0) return ByteArray(0)
            val out = ByteArray(count)
            for (i in 0 until count) {
                val b = next()
                if (b < 0) return null
                out[i] = b.toByte()
            }
            return out
        }

        fun skip(count: Long) {
            var left = count
            while (left > 0) {
                val b = next()
                if (b < 0) return
                left--
            }
        }

        /**
         * Skip a chain of data sub-blocks (length byte + data, terminated by a
         * zero-length block). Returns false on a truncated stream.
         */
        fun skipSubBlocks(): Boolean? {
            while (true) {
                val size = next()
                if (size < 0) return null
                if (size == 0) return true
                skip(size.toLong())
                if (total > MAX_SCAN_BYTES) return true
            }
        }
    }
}
