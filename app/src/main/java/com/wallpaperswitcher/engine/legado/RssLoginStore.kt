package com.wallpaperswitcher.engine.legado

import android.content.Context
import android.content.SharedPreferences

/**
 * 阅读's `source.putLoginInfo` / `source.getLoginInfo` and
 * `source.putLoginHeader`: the values typed into a `loginUi` form and the
 * header map produced by a `@js:` login script.
 *
 * Legado keeps them in its disk cache; we persist them per source so a login
 * survives an app restart. The values are stored verbatim - the same privacy
 * level as Legado's cache (app-private storage, not exported).
 */
internal object RssLoginStore {

    private const val PREFS = "rss_login"
    private const val KEY_INFO = "userInfo_"
    private const val KEY_HEADER = "loginHeader_"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun putUserInfo(sourceId: Long, json: String?) {
        val store = prefs ?: return
        if (sourceId <= 0L) return
        if (json.isNullOrBlank()) {
            store.edit().remove(KEY_INFO + sourceId).apply()
        } else {
            // Credentials are Keystore-encrypted (see RssCrypto).
            store.edit().putString(KEY_INFO + sourceId, RssCrypto.encrypt(json)).apply()
        }
    }

    fun getUserInfo(sourceId: Long): String? =
        RssCrypto.decrypt(prefs?.getString(KEY_INFO + sourceId, null))

    fun putLoginHeader(sourceId: Long, json: String?) {
        val store = prefs ?: return
        if (sourceId <= 0L) return
        if (json.isNullOrBlank()) {
            store.edit().remove(KEY_HEADER + sourceId).apply()
        } else {
            store.edit().putString(KEY_HEADER + sourceId, RssCrypto.encrypt(json)).apply()
        }
    }

    fun getLoginHeader(sourceId: Long): String? =
        RssCrypto.decrypt(prefs?.getString(KEY_HEADER + sourceId, null))

    /** Timestamp of the last silent `loginUrl` fetch (survives a restart). */
    fun putLoginAt(sourceId: Long, at: Long) {
        val store = prefs ?: return
        if (sourceId <= 0L) return
        store.edit().putLong("loginAt_$sourceId", at).apply()
    }

    fun getLoginAt(sourceId: Long): Long =
        prefs?.getLong("loginAt_$sourceId", 0L) ?: 0L
}
