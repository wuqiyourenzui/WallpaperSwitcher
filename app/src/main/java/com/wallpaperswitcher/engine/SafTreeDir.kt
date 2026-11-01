package com.wallpaperswitcher.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setString
import com.wallpaperswitcher.util.AppLog

/**
 * 用户自选的 SAF 目录（`content://…/tree/…`）的读写公共实现。
 *
 * 订阅导入（[RssDownloadDir]）和网盘下载（[OnlineDownloadDir]）各自持有一个键，
 * 但"读/存/持久化授权/取显示名"的代码完全一样 —— 以前是两份逐字重复的实现。
 */
internal object SafTreeDir {

    private const val TAG = "SafTreeDir"

    suspend fun load(context: Context, key: String): String = try {
        AppDatabase.getInstance(context).settingsDao().getString(key, "")
    } catch (t: Throwable) {
        AppLog.w(TAG, "load $key failed: ${t.javaClass.simpleName}: ${t.message}")
        ""
    }

    suspend fun save(context: Context, key: String, treeUri: String) {
        try {
            AppDatabase.getInstance(context).settingsDao().setString(key, treeUri.trim())
        } catch (t: Throwable) {
            // 存不下去 = 下次导入还落在默认目录；静默会让"文件没进目录"无从查起。
            AppLog.w(TAG, "save $key failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun persist(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (t: Throwable) {
            // 没拿到持久权限 → 重启后写入会失败，必须留痕。
            AppLog.w(TAG, "persist failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 设置页显示的目录名（`Download` / `壁纸` …）；空串表示用默认目录。 */
    fun displayName(treeUri: String): String {
        if (treeUri.isBlank()) return ""
        val decoded = try {
            Uri.decode(treeUri)
        } catch (_: Throwable) {
            treeUri
        }
        val primary = decoded.substringAfter("tree/primary:", "")
        if (primary.isNotBlank()) return primary.trim('/')
        return decoded.substringAfterLast('/').substringAfterLast(':').trim('/')
    }
}
