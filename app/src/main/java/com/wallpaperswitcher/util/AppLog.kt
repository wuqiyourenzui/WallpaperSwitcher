package com.wallpaperswitcher.util

import android.content.Context
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application-wide logger: every call is forwarded to logcat (so adb still
 * works) AND kept in an in-memory ring buffer + a bounded file under
 * cacheDir/logs. Settings can export a report (device info + settings + the
 * buffered runtime log) and share it, which makes OEM-specific issues
 * (e.g. vivo/OriginOS video or GIF problems) diagnosable without adb.
 *
 * The wallpaper engine and the foreground switch service run in the same
 * process as the UI, so their logs are captured too.
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val MAX_MEMORY_LINES = 6000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_MEMORY_LINES + 64)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var logFile: File? = null
    @Volatile private var initialized = false
    // Report text prepared for the SAF "保存到手机" flow (see prepareExport).
    @Volatile private var pendingExportText: String? = null
    // Bytes written to the current runtime file. Guarded by [lock]; used to roll
    // the file over instead of letting it grow without bound.
    private var writtenBytes = 0L
    // Bytes written since the last flush, and when that was (see appendLine).
    private var pendingFlushBytes = 0
    private var lastFlushAt = 0L
    // A batch of debug lines is flushed at the latest FLUSH_MAX_INTERVAL_MS after
    // the line that started it - even when NO further line arrives. The flush used
    // to be evaluated only inside appendLine, so a single debug line followed by
    // silence stayed in the 8KB BufferedWriter indefinitely: on-device inspection
    // saw a "frozen" log (already misread once as a hung switch), and a process
    // kill lost the tail. The handler makes the batch time-bounded for real.
    // Nullable + lazy: on a plain JVM (local unit tests) there is no main looper,
    // and a log call there must not blow up.
    private val flushHandler: android.os.Handler? by lazy {
        try {
            android.os.Handler(android.os.Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }
    private var flushScheduled = false
    private val scheduledFlush = Runnable {
        synchronized(lock) {
            flushScheduled = false
            flushLocked(android.os.SystemClock.elapsedRealtime())
        }
    }

    /** Flush the writer and reset the batch counters. Caller holds [lock]. */
    private fun flushLocked(nowMs: Long) {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
        pendingFlushBytes = 0
        lastFlushAt = nowMs
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            try {
                val dir = File(context.cacheDir, "logs").apply { mkdirs() }
                val f = File(dir, RUNTIME_FILE)
                // Keep ONE previous generation instead of deleting the log: a
                // long session used to vanish entirely on the next start (the
                // 13k-line tablet log became 2.7k lines), which made
                // cross-restart diagnosis impossible. The old generation is
                // rotated to runtime.1.log and its tail is included in exports.
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    val previous = File(dir, PREVIOUS_FILE)
                    try {
                        if (previous.exists()) previous.delete()
                        f.renameTo(previous)
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "AppLog rotate failed", t)
                        try { f.delete() } catch (_: Throwable) {}
                    }
                }
                logFile = f
                writtenBytes = if (f.exists()) f.length() else 0L
                writer = java.io.FileOutputStream(f, true).bufferedWriter(Charsets.UTF_8)
                appendLine('I', TAG, "==== AppLog started (pid=${android.os.Process.myPid()}) ====", null)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "AppLog init failed", t)
            }
        }
    }

    fun d(tag: String, message: String) = appendLine('D', tag, message, null)
    fun d(tag: String, message: String, t: Throwable) = appendLine('D', tag, message, t)
    fun w(tag: String, message: String) = appendLine('W', tag, message, null)
    fun w(tag: String, message: String, t: Throwable) = appendLine('W', tag, message, t)
    fun e(tag: String, message: String) = appendLine('E', tag, message, null)
    fun e(tag: String, message: String, t: Throwable) = appendLine('E', tag, message, t)

    /** Buffered runtime log (newest lines last). */
    fun snapshot(): String = synchronized(lock) {
        flushLocked(android.os.SystemClock.elapsedRealtime())
        if (flushScheduled) {
            flushScheduled = false
            flushHandler?.removeCallbacks(scheduledFlush)
        }
        buffer.joinToString("\n")
    }

    fun clear() {
        synchronized(lock) {
            if (flushScheduled) {
                flushScheduled = false
                flushHandler?.removeCallbacks(scheduledFlush)
            }
            buffer.clear()
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = try {
                val f = logFile
                f?.parentFile?.mkdirs()
                f?.writeText("")
                f?.let { java.io.FileOutputStream(it, false).bufferedWriter(Charsets.UTF_8) }
            } catch (_: Exception) {
                null
            }
            writtenBytes = 0L
            pendingFlushBytes = 0
            lastFlushAt = 0L
        }
    }

    /**
     * Build a shareable report: device/app summary + [headerLines] (settings
     * snapshot supplied by the UI) + the buffered log. Returns the text file.
     */
    fun buildReport(context: Context, headerLines: List<String>): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val out = File(dir, suggestedFileName())
        out.writeText(buildReportText(context, headerLines))
        return out
    }

    /** Suggested name for a saved/shared report file. */
    fun suggestedFileName(): String =
        "wallpaper_switcher_log_${fileFormat.format(Date())}.txt"

    /**
     * Report body (device/app summary + [headerLines] + buffered log). Used for
     * both the file export and "save to phone" (SAF), so the content is
     * identical either way.
     */
    fun buildReportText(context: Context, headerLines: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("WallpaperSwitcher runtime report")
        sb.appendLine("generated: ${Date()}")
        sb.appendLine("app: ${appVersion(context)}")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("build: ${Build.DISPLAY} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        val dm = context.resources.displayMetrics
        sb.appendLine("screen: ${dm.widthPixels}x${dm.heightPixels} @${dm.density}x")
        sb.appendLine()
        headerLines.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("---------- runtime log (${bufferSize()} lines) ----------")
        sb.appendLine(snapshot())
        val previous = previousLogTail(context)
        if (previous.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("---------- previous session (runtime.1.log tail) ----------")
            sb.appendLine(previous)
        }
        return sb.toString()
    }

    /**
     * Tail of the previous session's log (see [PREVIOUS_FILE]), or an empty
     * string when there is none. Appended to the report after the current log so
     * a problem that spans a restart (the wallpaper engine being recreated, the
     * app being killed in the background) is still visible.
     */
    private fun previousLogTail(context: Context): String {
        return try {
            val f = File(File(context.cacheDir, "logs"), PREVIOUS_FILE)
            if (!f.exists() || f.length() == 0L) return ""
            val tail = if (f.length() <= PREVIOUS_EXPORT_BYTES) {
                f.readText()
            } else {
                val bytes = ByteArray(PREVIOUS_EXPORT_BYTES)
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(f.length() - PREVIOUS_EXPORT_BYTES)
                    raf.readFully(bytes)
                }
                // The window starts mid-line; drop the first partial line.
                val text = String(bytes, Charsets.UTF_8)
                text.substringAfter('\n', text)
            }
            tail
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "previousLogTail failed", t)
            ""
        }
    }

    /**
     * Build the report and remember it for the SAF "保存到手机" flow.
     *
     * The report text is kept BOTH in memory and in a cache file: the system
     * file picker can outlive this composable/process (MIUI kills cached apps
     * while the picker is open), and the old code only kept it in Compose state
     * - the callback then saw null and returned early, leaving the freshly
     * created document at 0 bytes.
     *
     * @return the suggested file name for the picker.
     */
    fun prepareExport(context: Context, headerLines: List<String>): String {
        val text = buildReportText(context, headerLines)
        pendingExportText = text
        try {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            File(dir, PENDING_EXPORT_FILE).writeText(text)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "prepareExport: cache write failed", t)
        }
        return suggestedFileName()
    }

    /**
     * The report prepared by [prepareExport]. Falls back to the cache file so a
     * process restart between "prepare" and "save" still saves real content.
     * Returns null when there is nothing (the caller must then NOT save an
     * empty file).
     */
    fun readPendingExport(context: Context): String? {
        pendingExportText?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val f = File(File(context.cacheDir, "logs"), PENDING_EXPORT_FILE)
            if (f.exists() && f.length() > 0L) f.readText().takeIf { it.isNotBlank() } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clearPendingExport() {
        pendingExportText = null
        try {
            logFile?.parentFile?.let { File(it, PENDING_EXPORT_FILE).delete() }
        } catch (_: Throwable) {}
    }

    private fun bufferSize(): Int = synchronized(lock) { buffer.size }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // longVersionCode() only exists on API 28+: calling it on Android 8.0/8.1
        // throws NoSuchMethodError, which is an Error and therefore NOT caught by
        // the `catch (_: Exception)` below - exporting a log report crashed the app
        // on those devices (minSdk is 26).
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    } catch (_: Throwable) {
        "unknown"
    }

    /**
     * Roll the runtime file over once it reaches [MAX_FILE_BYTES], so a
     * long-running session cannot grow it without bound. The recent lines stay
     * available in the in-memory ring buffer. Caller holds [lock].
     */
    private fun rotateLogIfNeededLocked(nextLineBytes: Int) {
        if (writtenBytes + nextLineBytes <= MAX_FILE_BYTES) return
        try { writer?.close() } catch (_: Exception) {}
        val f = logFile
        writtenBytes = 0L
        writer = try {
            f?.parentFile?.mkdirs()
            f?.writeText("")
            f?.let { java.io.FileOutputStream(it, false).bufferedWriter(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
    }

    private fun appendLine(level: Char, tag: String, message: String, t: Throwable?) {
        // Mirror to logcat so adb capture keeps working exactly as before.
        when (level) {
            'D' -> android.util.Log.d(tag, message, t)
            'I' -> android.util.Log.i(tag, message, t)
            'W' -> android.util.Log.w(tag, message, t)
            else -> android.util.Log.e(tag, message, t)
        }

        synchronized(lock) {
            // The timestamp is formatted INSIDE the lock: SimpleDateFormat is not
            // thread-safe, and this is called concurrently from the engine's
            // decode/render threads, the foreground service and the UI. Racing
            // formatters produced garbled timestamps and could even throw, and
            // that exception would propagate out of AppLog.d() into the caller
            // (i.e. into a switch).
            val line = buildString {
                append(timeFormat.format(Date()))
                append(' ')
                append(level)
                append(' ')
                append(Thread.currentThread().name)
                append(' ')
                append(tag)
                append(": ")
                append(message)
                if (t != null) {
                    append(" | ")
                    append(t.javaClass.simpleName)
                    append(": ")
                    append(t.message)
                }
            }
            buffer.addLast(line)
            while (buffer.size > MAX_MEMORY_LINES) buffer.removeFirst()
            // UTF-8 bytes, not String.length: the file is UTF-8 and a Chinese
            // log line costs ~3 bytes per character, so counting characters made
            // the 2MB cap fire about three times late (and made the number
            // disagree with `f.length()` recorded at startup, which IS bytes).
            val lineBytes = lineBytes(line)
            rotateLogIfNeededLocked(lineBytes)
            val w = writer
            if (w != null) {
                try {
                    w.write(line)
                    w.newLine()
                    writtenBytes += lineBytes
                    pendingFlushBytes += lineBytes
                    // Debug lines are batched, everything else (W/E/I) is flushed
                    // immediately so a crash report can never lose a warning. The
                    // batch is bounded by both size and time: an abrupt kill costs
                    // at most FLUSH_MAX_INTERVAL_MS of debug chatter, while a hot
                    // loop (30-60 lines/s) no longer issues one write+flush per
                    // line - which was itself enough to make a rendering fault
                    // worse. snapshot()/buildReport() flush on demand.
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (level != 'D' || pendingFlushBytes >= FLUSH_THRESHOLD_BYTES ||
                        nowMs - lastFlushAt >= FLUSH_MAX_INTERVAL_MS
                    ) {
                        flushLocked(nowMs)
                    } else {
                        // Batched: make sure it really lands within
                        // FLUSH_MAX_INTERVAL_MS even if this was the last line for
                        // a while (see scheduledFlush).
                        scheduleFlush()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Arm the one-shot delayed flush; no-op while one is already pending. */
    private fun scheduleFlush() {
        val handler = flushHandler ?: return
        if (flushScheduled) return
        flushScheduled = true
        handler.postDelayed(scheduledFlush, FLUSH_MAX_INTERVAL_MS)
    }

    /**
     * Bytes one logged line costs in the UTF-8 file, INCLUDING its newline.
     *
     * The size budget is compared against [File.length] (bytes), so the counter
     * has to be bytes as well: `String.length` counts UTF-16 units, which made a
     * Chinese log line look ~1/3 of its real size and pushed the 2MB rotation
     * far past its bound. Pure so it is unit-tested.
     */
    internal fun lineBytes(line: String): Int = line.toByteArray(Charsets.UTF_8).size + 1

    private const val RUNTIME_FILE = "runtime.log"
    /** Log of the previous session, kept so a restart does not lose the context. */
    private const val PREVIOUS_FILE = "runtime.1.log"
    private const val PENDING_EXPORT_FILE = "pending_export.txt"
    /** How much of the previous session's log an export carries. */
    private const val PREVIOUS_EXPORT_BYTES = 256 * 1024
    /** Flush a batch of debug lines once it grows past this. */
    private const val FLUSH_THRESHOLD_BYTES = 4 * 1024
    /** ...or once this much time passed, so a crash loses at most this much. */
    private const val FLUSH_MAX_INTERVAL_MS = 250L
}
