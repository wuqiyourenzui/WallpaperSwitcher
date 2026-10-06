package com.wallpaperswitcher.engine

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 网络失败 → 稳定 reason code 的唯一映射（UI 用 `online_error_*` 本地化）。
 *
 * 订阅源(RSS/Legado)与在线源原本各自维护一份，已经漂移（RSS 把
 * `InterruptedIOException` 当 timeout，在线源当成 network）；这里收敛成一份，
 * 两边调用同一个实现。
 */
internal object HttpFailureReason {

    fun of(status: Int): String = when (status) {
        401 -> "auth"
        403 -> "forbidden"
        404 -> "not_found"
        429 -> "rate_limited"
        in 500..599 -> "server"
        else -> "http_$status"
    }

    fun classify(t: Throwable): String = when (t) {
        is FetchException -> t.reason
        is UnknownHostException -> "network"
        is SocketTimeoutException -> "timeout"
        is SSLException -> "ssl"
        // InterruptedIOException covers SocketTimeoutException (checked above)
        // and plain timeouts, which users read as "网络不可用" otherwise.
        is InterruptedIOException -> "timeout"
        is IOException -> "network"
        else -> "unknown"
    }
}
