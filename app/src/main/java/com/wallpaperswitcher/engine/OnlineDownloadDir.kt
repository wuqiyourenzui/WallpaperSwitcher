package com.wallpaperswitcher.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.wallpaperswitcher.util.AppLog
import java.io.File

/**
 * 网盘（WebDAV）下载文件的落点：应用私有目录 `files/online/<源 id>/`，或用户自选的
 * SAF 目录（**与订阅源的下载目录分开**，各有各的设置项）。
 *
 * 为什么单独一个键：订阅目录是给订阅导入用的，网盘下载可能是几百 MB 的视频，用户往往
 * 想让它们落在别的盘/别的相册目录里；混用一个键会让"我只想改网盘的位置"变成"连订阅也改"。
 */
object OnlineDownloadDir {

    const val KEY = "webdav_download_dir"

    suspend fun load(context: Context): String = SafTreeDir.load(context, KEY)

    suspend fun save(context: Context, treeUri: String) = SafTreeDir.save(context, KEY, treeUri)

    fun persist(context: Context, treeUri: Uri) = SafTreeDir.persist(context, treeUri)

    /** 设置页显示的目录名（空串 = 用应用私有目录）。 */
    fun displayName(treeUri: String): String = SafTreeDir.displayName(treeUri)

    /**
     * 把已下载到私有目录的 [file] 复制进用户选的目录，返回 `content://` 地址。
     *
     * 失败（没权限/目录被删）返回 null，调用方保留私有文件即可 —— 不因为"挪不过去"
     * 而丢掉已经下好的几百 MB。
     */
    fun copyIntoTree(
        context: Context,
        treeUri: String,
        file: File,
        displayName: String,
        mime: String,
    ): Uri? {
        if (treeUri.isBlank() || !file.exists()) {
            AppLog.d(TAG, "skip copy: treeBlank=${treeUri.isBlank()} fileExists=${file.exists()}")
            return null
        }
        // createDocument 只接受**具体** MIME：`image/*` / `video/*` 这种通配符在多数
        // DocumentsProvider 上直接抛 IllegalArgumentException —— 之前就是这么静默失败、
        // 用户看到"文件没进目录"。
        val concreteMime = concreteMimeOf(file, displayName, mime)
        return try {
            val tree = Uri.parse(treeUri)
            // createDocument 要的是**文档** URI，而设置里存的是**树** URI
            // （content://…/tree/primary%3A解压）。直接传树 URI 会抛
            // "IllegalArgumentException: Invalid URI"（真机日志原话），文件就永远进不去。
            // 先把树 ID 转成"该树根目录"的文档 URI。
            val rootDocId = DocumentsContract.getTreeDocumentId(tree)
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)
            val doc = DocumentsContract.createDocument(
                context.contentResolver,
                parentDoc,
                concreteMime,
                displayName,
            ) ?: run {
                AppLog.w(TAG, "createDocument returned null (mime=$concreteMime)")
                return null
            }
            context.contentResolver.openOutputStream(doc)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: run {
                AppLog.w(TAG, "openOutputStream returned null for $doc")
                return null
            }
            AppLog.d(TAG, "copied ${file.length() / 1024}KB -> $doc (mime=$concreteMime)")
            doc
        } catch (t: Throwable) {
            AppLog.w(TAG, "copyIntoTree failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 从文件名/给定 MIME 推一个**具体**类型；推不出用 application/octet-stream。 */
    private fun concreteMimeOf(file: File, displayName: String, hint: String): String {
        if (hint.isNotBlank() && !hint.contains('*')) return hint
        val ext = displayName.substringAfterLast('.', "")
            .ifBlank { file.extension }
            .lowercase()
        val byMap = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!byMap.isNullOrBlank()) return byMap
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "m4v" -> "video/x-m4v"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
    }

    private const val TAG = "OnlineDownloadDir"
}
