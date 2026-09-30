package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.util.LogText
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.logCoroutineFailures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serialized writer for the "learned media metadata" write-back.
 *
 * The decode path used to call `runBlocking { dao.updateMediaMeta(...) }` from
 * the thread that had just decoded the image, so a busy database blocked a
 * decode (and with it the switch that was waiting for it). The writes are tiny
 * and independent, so they are queued here and applied by one background
 * coroutine instead.
 *
 * The queue is bounded and drops the OLDEST entry when full: a lost write only
 * costs one extra media probe the next time that item is decoded.
 */
internal object MediaMetaWriter {

    private const val TAG = "MediaMetaWriter"
    private const val QUEUE_CAPACITY = 256

    private data class Entry(
        val appContext: Context,
        val uri: String,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )

    private val queue = Channel<Entry>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + logCoroutineFailures(TAG)
    )

    init {
        scope.launch {
            for (entry in queue) {
                try {
                    AppDatabase.getInstance(entry.appContext)
                        .wallpaperImageDao()
                        .updateMediaMeta(
                            entry.uri, entry.width, entry.height, entry.rotationDegrees
                        )
                } catch (t: Throwable) {
                    // The row may be gone, or the DB is busy: the in-memory cache
                    // still covers this session.
                    AppLog.d(TAG, "updateMediaMeta failed for ${LogText.short(entry.uri)}: ${t.message}")
                }
            }
        }
    }

    /** Queue [uri]'s decode metadata for storage (never blocks the caller). */
    fun enqueue(context: Context, uri: String, width: Int, height: Int, rotationDegrees: Int) {
        queue.trySend(
            Entry(context.applicationContext, uri, width, height, rotationDegrees)
        )
    }
}
