package com.wallpaperswitcher.engine

/**
 * Stable, language-neutral reason code for a failed fetch. The UI localizes
 * `reason` (see `online_error_*` strings), so the stored/logged text never
 * depends on the language at fetch time.
 */
class FetchException(
    val reason: String,
    val httpStatus: Int = 0,
    cause: Throwable? = null,
) : Exception(reason, cause)
