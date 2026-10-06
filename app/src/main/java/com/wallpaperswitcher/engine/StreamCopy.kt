package com.wallpaperswitcher.engine

import java.io.InputStream
import java.io.OutputStream

/**
 * Bounded, cancellable, optionally deadline-limited stream copy shared by the
 * RSS media downloader and the video-cache fallback.
 *
 * The two callers used to carry their own copies of this loop, which is how
 * the video fallback ended up unbounded and uninterruptible. Keeping ONE loop
 * with an injected clock makes the bounds unit-testable (see StreamCopyTest)
 * without a device.
 */
internal object StreamCopy {

    const val DEFAULT_BUFFER_BYTES = 64 * 1024

    class Result(
        val complete: Boolean,
        val bytes: Long,
        val cancelled: Boolean,
        val timedOut: Boolean,
        val exceededLimit: Boolean,
    )

    /**
     * Copy until EOF, [maxBytes], the deadline or cancellation.
     *
     * The limit is strict: a chunk that would push the total past [maxBytes] is
     * not written, so the output never exceeds the budget (the old loops wrote
     * the overshooting chunk first and checked afterwards).
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        deadlineAtMs: Long? = null,
        nowMs: () -> Long = { System.currentTimeMillis() },
        isCancelled: () -> Boolean = { false },
        bufferSize: Int = DEFAULT_BUFFER_BYTES,
        onChunk: ((ByteArray, Int) -> Unit)? = null,
    ): Result {
        var bytes = 0L
        val buffer = ByteArray(bufferSize.coerceAtLeast(1))
        while (true) {
            if (isCancelled() || Thread.currentThread().isInterrupted) {
                return Result(
                    complete = false, bytes = bytes,
                    cancelled = true, timedOut = false, exceededLimit = false,
                )
            }
            if (deadlineAtMs != null && nowMs() > deadlineAtMs) {
                return Result(
                    complete = false, bytes = bytes,
                    cancelled = false, timedOut = true, exceededLimit = false,
                )
            }
            val read = input.read(buffer)
            if (read < 0) {
                return Result(
                    complete = true, bytes = bytes,
                    cancelled = false, timedOut = false, exceededLimit = false,
                )
            }
            if (read == 0) continue
            if (bytes + read > maxBytes) {
                return Result(
                    complete = false, bytes = bytes,
                    cancelled = false, timedOut = false, exceededLimit = true,
                )
            }
            output.write(buffer, 0, read)
            bytes += read
            onChunk?.invoke(buffer, read)
        }
    }
}
