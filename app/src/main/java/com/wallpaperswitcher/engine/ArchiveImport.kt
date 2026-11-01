package com.wallpaperswitcher.engine

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 「添加壁纸」里的**压缩包导入**：把一个（或多个）压缩包里的图片 / 视频解出来，
 * 逐个当成普通文件加进分组。
 *
 * 几个刻意的决定：
 *  - 解出来的文件放**应用私有目录** `files/archives/`，不是相册。这样卸载即清、
 *    也不会把用户的相册塞满；[OwnedMediaCleaner] 已经在扫这个目录，删行之后
 *    文件会被自动回收（所以这里不需要自己记引用）。
 *  - **多选压缩包全部拉平**到同一个目录，重名靠 `名字 (2).ext` 让开。
 *  - 只认 [MediaTypes.isSupportedName] 的条目：`__MACOSX/`、`.DS_Store`、
 *    说明文本这些一律跳过，不写盘。
 *  - 解压前不把整个包读进内存（用流式 `ZipInputStream`）：一个几十 GB 的写真集
 *    也不该把进程打爆。
 *
 * 纯 JVM 逻辑（只用 java.io / java.util.zip，不碰 Android），所以规则都能单测。
 */
object ArchiveImport {

    /** 解压结果：可以直接交给 `MediaLibraryController.addImages` 的那些 uri。 */
    data class Result(
        /** `file://` 形式的绝对路径，顺序与包内顺序一致。 */
        val uris: List<String>,
        /** 与 [uris] 一一对应的显示名（已处理重名）。 */
        val names: List<String>,
        val images: Int,
        val videos: Int,
        /** 包内不是可显示媒体的条目数（文本 / 元数据 / 不支持的格式）。 */
        val skipped: Int,
        /** 写盘失败的条目数（单个条目失败不影响其余）。 */
        val failed: Int,
        /** 单个包打不开时的原因；为 null 表示至少读进去了一部分。 */
        val openError: String? = null,
    ) {
        val added: Int get() = uris.size
        val isEmpty: Boolean get() = uris.isEmpty()
    }

    /**
     * 单个条目的体积上限。压缩包里的条目声明了原始大小，超限的直接跳过而不是
     * 先写盘再发现 —— 一个被恶意构造 / 损坏的包头可以声明 TB 级的膨胀体积。
     */
    private const val MAX_ENTRY_BYTES = 4L * 1024 * 1024 * 1024

    /** 包内路径里不该被当成媒体的噪音。 */
    private fun isJunkEntry(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (p.startsWith("__MACOSX/") || p.contains("/__MACOSX/")) return true
        if (p.endsWith("/")) return true
        val leaf = p.substringAfterLast('/')
        // ._foo 是 macOS 的资源分支文件，不是真媒体。
        if (leaf.startsWith("._") || leaf == ".DS_Store" || leaf == "Thumbs.db") return true
        return false
    }

    /**
     * 解压 [open] 提供的 zip 流到 [rootDir]，返回可入库的媒体列表。
     *
     * [rootDir] 必须已经存在；调用方负责决定它是不是每次导入都清空
     * （见 `MediaLibraryController.importArchives`——不清，因为分组可能引用着
     * 上一次解出来的文件）。
     *
     * @param onProgress 已写出的字节数（用于进度提示；解压大包时界面不至于僵住）
     */
    fun extract(
        open: () -> InputStream,
        rootDir: File,
        onProgress: (bytesWritten: Long) -> Unit = {},
    ): Result {
        if (!rootDir.isDirectory && !rootDir.mkdirs()) {
            return Result(emptyList(), emptyList(), 0, 0, 0, 0, "cannot create ${rootDir.path}")
        }
        val uris = ArrayList<String>()
        val names = ArrayList<String>()
        var images = 0
        var videos = 0
        var skipped = 0
        var failed = 0
        var written = 0L
        var openError: String? = null
        // 同一个包内也可能有两个同名文件（不同目录下的 001.jpg），而且多个包会
        // 拉平到同一目录，所以重名要在这**一层**统一处理（见 uniqueName）。
        val used = HashSet<String>()
        try {
            ZipInputStream(open().buffered()).use { zip ->
                var zipEntry = zip.nextEntry
                while (zipEntry != null) {
                    val path = zipEntry.name.orEmpty()
                    val leaf = path.substringAfterLast('/')
                    val declared = zipEntry.size
                    val usable = !isJunkEntry(path) &&
                        MediaTypes.isSupportedName(path) &&
                        declared <= MAX_ENTRY_BYTES
                    if (!usable) {
                        skipped++
                    } else {
                        val target = uniqueName(rootDir, leaf, used)
                        try {
                            target.outputStream().buffered().use { out ->
                                val copied = zip.copyTo(out)
                                written += copied
                                onProgress(written)
                            }
                            // 空文件 / 写坏的文件不入库：入库后只会变成一张破图。
                            if (target.length() <= 0L) {
                                target.delete()
                                failed++
                            } else {
                                used += target.name
                                uris += "file://${target.absolutePath}"
                                names += target.name
                                if (MediaTypes.isMotion(MediaTypes.fromName(target.name))) videos++
                                else images++
                            }
                        } catch (_: Throwable) {
                            target.delete()
                            failed++
                        }
                    }
                    zip.closeEntry()
                    zipEntry = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            // 打不开 / 中途损坏：已经解出来的部分照样返回（大包里前 90% 是好图，
            // 不该因为最后一条坏记录全部丢掉）。
            openError = t.javaClass.simpleName + ": " + (t.message ?: "")
        }
        return Result(uris, names, images, videos, skipped, failed, openError)
    }

    /**
     * 在 [rootDir] 里给 [leaf] 找一个不冲突的名字，并在 [used] 里占位。
     *
     * `a.jpg` → `a (2).jpg` → `a (3).jpg`。用 `名字 (n).ext` 而不是加哈希前缀，
     * 是因为这些名字会直接显示在分组里。
     */
    private fun uniqueName(rootDir: File, leaf: String, used: MutableSet<String>): File {
        val base = leaf.substringBeforeLast('.', leaf).ifBlank { "media" }
        val ext = leaf.substringAfterLast('.', "")
        val suffix = if (ext.isBlank()) "" else ".$ext"
        var candidate = "$base$suffix"
        var n = 2
        while (candidate in used || File(rootDir, candidate).exists()) {
            candidate = "$base ($n)$suffix"
            n++
            if (n > 10_000) {
                // 病态输入（同一个包里上万个同名条目）：退化成序号，保证能停。
                candidate = "$base-${System.nanoTime()}$suffix"
                break
            }
        }
        return File(rootDir, candidate)
    }
}
