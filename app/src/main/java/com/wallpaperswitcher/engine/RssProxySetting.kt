package com.wallpaperswitcher.engine

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setString

/**
 * 阅读 lets a source reach sites that are not directly accessible through a
 * user-configured proxy; we keep the same escape hatch for subscription
 * traffic. The value is `host:port` (optionally `socks5://…` / `http://…`).
 */
object RssProxySetting {

    const val KEY = "rss_proxy"

    suspend fun load(context: Context): String = try {
        AppDatabase.getInstance(context).settingsDao().getString(KEY, "")
    } catch (_: Throwable) {
        ""
    }

    /** Reads the stored proxy into the HTTP layer (call at app start / after save). */
    suspend fun apply(context: Context) {
        RssHttp.proxySpec = load(context).takeIf { it.isNotBlank() }
    }

    suspend fun save(context: Context, spec: String) {
        val value = spec.trim()
        try {
            AppDatabase.getInstance(context).settingsDao().setString(KEY, value)
        } catch (_: Throwable) {
        }
        RssHttp.proxySpec = value.takeIf { it.isNotBlank() }
    }
}
