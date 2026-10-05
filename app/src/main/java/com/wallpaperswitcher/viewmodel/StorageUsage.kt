package com.wallpaperswitcher.viewmodel

/**
 * 一个来源目录的占用：名字、文件数、字节数。
 *
 * 分开报是刻意的：`rss` / `online` 是应用私有目录里下载的媒体（**可以**清），
 * 相册/文件夹来源只登记 uri（清不动也不该动），用户需要知道钱花在哪。
 */
data class StorageDirUsage(
    val name: String,
    val files: Int,
    val bytes: Long,
)

/** 「存储与流量守门」页的实时统计（进页面时算一次，不常驻后台扫描）。 */
data class StorageUsage(
    val rss: StorageDirUsage = StorageDirUsage("rss", 0, 0L),
    val online: StorageDirUsage = StorageDirUsage("online", 0, 0L),
    /** Copies of media another app shared into us (ACTION_SEND). */
    val shared: StorageDirUsage = StorageDirUsage("shared", 0, 0L),
) {
    val totalFiles: Int get() = rss.files + online.files + shared.files
    val totalBytes: Long get() = rss.bytes + online.bytes + shared.bytes
}

/** 一次清理的结果：删掉了多少孤儿文件、释放了多少字节。 */
data class StorageCleanResult(val files: Int, val bytes: Long)
