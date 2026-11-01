package com.wallpaperswitcher.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 订阅导入的图片/视频默认落在应用私有目录（`files/rss/<源 id>/`，不占相册）。
 * 这里保存用户自选的下载目录（SAF 树 URI）：选了之后新下载的媒体直接写进那个
 * 目录，在文件管理器/相册里可见。
 *
 * 读/存/授权/取显示名都委托给 [SafTreeDir]（与网盘下载目录共用同一份实现，
 * 只是键不同）。
 */
object RssDownloadDir {

    const val KEY = "rss_download_dir"

    /** Persisted tree URI, or "" for the app-private default. */
    suspend fun load(context: Context): String = SafTreeDir.load(context, KEY)

    suspend fun save(context: Context, treeUri: String) = SafTreeDir.save(context, KEY, treeUri)

    /** Keeps the read/write grant across restarts (must be called from the picker). */
    fun persist(context: Context, treeUri: Uri) = SafTreeDir.persist(context, treeUri)

    /** Folder name shown in settings ("Download" / "壁纸" …). */
    fun displayName(treeUri: String): String = SafTreeDir.displayName(treeUri)

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
