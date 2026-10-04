package com.wallpaperswitcher.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setString

/**
 * 订阅导入的图片/视频默认落在应用私有目录（`files/rss/<源 id>/`，不占相册）。
 * 这里保存用户自选的下载目录（SAF 树 URI）：选了之后新下载的媒体直接写进那个
 * 目录，在文件管理器/相册里可见。
 */
object RssDownloadDir {

    const val KEY = "rss_download_dir"

    /** Persisted tree URI, or "" for the app-private default. */
    suspend fun load(context: Context): String = try {
        AppDatabase.getInstance(context).settingsDao().getString(KEY, "")
    } catch (_: Throwable) {
        ""
    }

    suspend fun save(context: Context, treeUri: String) {
        try {
            AppDatabase.getInstance(context).settingsDao().setString(KEY, treeUri.trim())
        } catch (_: Throwable) {
        }
    }

    /** Keeps the read/write grant across restarts (must be called from the picker). */
    fun persist(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Throwable) {
        }
    }

    /** Folder name shown in settings ("Download" / "壁纸" …). */
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

    /**
     * True when [childUri] is a document inside the [treeUri] tree. Used before
     * deleting: only files in the folder the user handed us are touched.
     */
    fun isInside(treeUri: String, childUri: String): Boolean {
        if (treeUri.isBlank() || !childUri.startsWith("content://")) return false
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            val childId = DocumentsContract.getDocumentId(Uri.parse(childUri))
            treeId.isNotBlank() && (childId == treeId || childId.startsWith("$treeId/"))
        } catch (_: Throwable) {
            false
        }
    }
}
