package com.wallpaperswitcher.util

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Handler that turns an uncaught coroutine failure into a log line instead of a
 * process death.
 *
 * On Android an exception thrown inside `scope.launch { ... }` without a
 * [CoroutineExceptionHandler] is delivered to the thread's default uncaught
 * exception handler, which **kills the process**. The live wallpaper engine and
 * the foreground switch service run in the same process as the UI, so a single
 * stray throw in a background coroutine (health monitor, settings collector,
 * font/DB callback) took the whole app down - the system then restarted the
 * wallpaper a moment later, which is why it looked like "the wallpaper engine
 * randomly restarts" rather than a crash.
 *
 * Attach it to every long-lived scope: `CoroutineScope(Dispatchers.IO +
 * SupervisorJob() + logCoroutineFailures(TAG))`.
 */
fun logCoroutineFailures(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        AppLog.e(tag, "Uncaught coroutine failure (process kept alive)", throwable)
    }
