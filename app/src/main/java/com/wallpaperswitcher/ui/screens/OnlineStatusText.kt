package com.wallpaperswitcher.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wallpaperswitcher.R

/**
 * Localized text of a fetch-result reason code
 * (see [com.wallpaperswitcher.engine.OnlineSourceRules.decodeResult]).
 *
 * Shared by the subscription screens; the online-wallpaper-source screen that
 * used to host it was removed.
 */
@Composable
internal fun onlineErrorText(reason: String): String = stringResource(
    when {
        reason == "network" -> R.string.online_error_network
        reason == "timeout" -> R.string.online_error_timeout
        reason == "ssl" -> R.string.online_error_ssl
        reason == "auth" -> R.string.online_error_auth
        reason == "forbidden" -> R.string.online_error_forbidden
        reason == "not_found" -> R.string.online_error_not_found
        reason == "rate_limited" -> R.string.online_error_rate_limited
        reason == "server" -> R.string.online_error_server
        reason == "https_required" -> R.string.online_error_https_required
        reason == "bad_url" -> R.string.online_error_bad_url
        reason == "parse" -> R.string.online_error_parse
        reason == "unsupported_js" -> R.string.online_error_unsupported_js
        reason == "not_image" -> R.string.online_error_not_image
        reason == "too_large" -> R.string.online_error_too_large
        reason == "empty" -> R.string.online_error_empty
        reason == "storage" -> R.string.online_error_storage
        reason == "missing" -> R.string.online_error_missing
        reason == "disabled" -> R.string.online_error_disabled
        reason.startsWith("http_") -> R.string.online_error_http
        else -> R.string.online_error_unknown
    }
)
