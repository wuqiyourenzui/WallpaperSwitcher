package com.wallpaperswitcher.engine

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 摘要与十六进制转换的唯一入口。
 *
 * 以前同样的"建 digest → update → 拼 hex"在 4 个文件里各写一遍
 * （`OnlineFetcher`、`OnlineVideoThumbs`、`RssMediaImporter`、`SharedMediaImporter`），
 * 其中两处拼 hex 的循环还不一样。这里统一掉，避免哪天算法/大小写不一致。
 */
internal object Hashes {

    /** SHA-1（短、只用于缓存文件名，不做安全用途）。 */
    fun sha1Hex(value: String): String =
        hex(MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)))

    /** SHA-256（内容去重键）。 */
    fun sha256Hex(value: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    /** 流式读取用：调用方自己 update 完再调用 [finishHex]。 */
    fun sha256Digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun finishHex(digest: MessageDigest): String = hex(digest.digest())

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b))
        }
        return out.toString()
    }
}
