package com.wallpaperswitcher.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 压缩包导入：只把能显示的图片 / 视频解出来，噪音条目跳过，多个包拉平后重名让开。
 *
 * 用真实 zip 字节（`ZipOutputStream`）而不是 mock：`ZipInputStream` 的条目名与
 * 流式读取行为正是这里要验证的东西，mock 掉就什么都没测到。
 */
class ArchiveImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一个 zip：`路径 to 内容`。 */
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun bytes(vararg text: String) = text.joinToString(" ").toByteArray()

    @Test
    fun `extracts images and videos and skips everything else`() {
        val root = tmp.newFolder("archives")
        val data = zip(
            "set/001.jpg" to bytes("jpeg-one"),
            "set/002.PNG" to bytes("png-two"),
            "set/clip.mp4" to bytes("video-three"),
            "set/notes.txt" to bytes("not media"),
            "set/readme.md" to bytes("not media"),
            "__MACOSX/set/._001.jpg" to bytes("mac resource fork"),
            "set/.DS_Store" to bytes("mac junk"),
            "set/Thumbs.db" to bytes("windows junk"),
        )

        val result = ArchiveImport.extract({ data.inputStream() }, root)

        assertEquals(3, result.added)
        assertEquals(2, result.images)
        assertEquals(1, result.videos)
        // 文本 / md / 资源分支 / DS_Store / Thumbs.db / 目录条目
        assertEquals(5, result.skipped)
        assertEquals(0, result.failed)
        assertEquals(null, result.openError)
        assertEquals(
            listOf("001.jpg", "002.PNG", "clip.mp4"),
            result.names,
        )
        // 每个 uri 都真的写出来了，而且内容对得上
        for (uri in result.uris) {
            val f = File(uri.removePrefix("file://"))
            assertTrue("missing $uri", f.isFile)
            assertTrue("empty $uri", f.length() > 0L)
        }
        assertEquals("jpeg-one", File(root, "001.jpg").readText())
    }

    @Test
    fun `readme-style archives are reported as empty instead of failing`() {
        val root = tmp.newFolder("archives")
        val data = zip(
            "readme.txt" to bytes("nothing to see"),
            "cover.url" to bytes("[InternetShortcut]"),
        )
        val result = ArchiveImport.extract({ data.inputStream() }, root)
        assertTrue(result.isEmpty)
        assertEquals(2, result.skipped)
        assertEquals(null, result.openError)
    }

    @Test
    fun `a second archive is flattened into the same folder with renamed collisions`() {
        val root = tmp.newFolder("archives")
        // 两个包都带 001.jpg：拉平到同一目录后必须重名让开，不能互相覆盖。
        val first = zip("a/001.jpg" to bytes("from-first"))
        val second = zip("b/001.jpg" to bytes("from-second"))

        val one = ArchiveImport.extract({ first.inputStream() }, root)
        val two = ArchiveImport.extract({ second.inputStream() }, root)

        assertEquals(listOf("001.jpg"), one.names)
        assertEquals(listOf("001 (2).jpg"), two.names)
        assertEquals("from-first", File(root, "001.jpg").readText())
        assertEquals("from-second", File(root, "001 (2).jpg").readText())
    }

    @Test
    fun `duplicate names inside one archive are also kept apart`() {
        val root = tmp.newFolder("archives")
        // 不同子目录下的同名文件：同一个包里就撞了。
        val data = zip(
            "day1/001.jpg" to bytes("one"),
            "day2/001.jpg" to bytes("two"),
            "day3/001.jpg" to bytes("three"),
        )
        val result = ArchiveImport.extract({ data.inputStream() }, root)
        assertEquals(listOf("001.jpg", "001 (2).jpg", "001 (3).jpg"), result.names)
        assertEquals(3, result.added)
    }

    @Test
    fun `empty entries are dropped instead of becoming broken rows`() {
        val root = tmp.newFolder("archives")
        val data = zip(
            "empty.jpg" to ByteArray(0),
            "good.jpg" to bytes("real"),
        )
        val result = ArchiveImport.extract({ data.inputStream() }, root)
        assertEquals(listOf("good.jpg"), result.names)
        assertEquals(1, result.failed)
        // 空文件不留在盘上
        assertEquals(false, File(root, "empty.jpg").exists())
    }

    @Test
    fun `trailing garbage after the last entry is tolerated`() {
        val root = tmp.newFolder("archives")
        val good = zip("001.jpg" to bytes("readable")).toMutableList()
        // 尾部追加垃圾字节：ZipInputStream 读到没有下一个条目就正常结束，
        // 不该因此报错，也不该丢掉已经解出来的文件。
        good.addAll(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02).toList())

        val result = ArchiveImport.extract({ good.toByteArray().inputStream() }, root)

        assertEquals(listOf("001.jpg"), result.names)
        assertEquals("readable", File(root, "001.jpg").readText())
    }

    @Test
    fun `a stream truncated mid-entry is reported and keeps the earlier files`() {
        val root = tmp.newFolder("archives")
        // 真随机字节不可压缩，压缩包才会有实打实的体积可以从中截断。
        val noise = ByteArray(64 * 1024).also { java.util.Random(42L).nextBytes(it) }
        val full = zip(
            "001.jpg" to bytes("first-entry"),
            "002.jpg" to noise,
        )
        // 砍掉尾部：第二条记录只写了一半，读它必然抛异常。
        val cut = full.size / 3
        val truncated = full.copyOf(full.size - cut)
        assertTrue("fixture must stay bigger than the cut", truncated.size > 100)

        val result = ArchiveImport.extract({ truncated.inputStream() }, root)

        assertTrue("expected the first entry to survive", result.added >= 1)
        assertEquals("first-entry", File(root, "001.jpg").readText())
        assertNotNull("a truncated archive must be reported", result.openError)
    }

    @Test
    fun `a folder that cannot be created is reported, not thrown`() {
        val file = tmp.newFile("not-a-dir")
        val result = ArchiveImport.extract(
            { zip("a.jpg" to bytes("x")).inputStream() },
            File(file, "nested"),
        )
        assertTrue(result.isEmpty)
        assertNotNull(result.openError)
    }

    @Test
    fun `progress reports the bytes written so far`() {
        val root = tmp.newFolder("archives")
        val data = zip(
            "a.jpg" to ByteArray(1000) { 1 },
            "b.jpg" to ByteArray(2000) { 2 },
        )
        val seen = ArrayList<Long>()
        ArchiveImport.extract({ data.inputStream() }, root) { seen += it }
        assertTrue("progress never reported", seen.isNotEmpty())
        assertEquals("progress must be monotonic", seen.sorted(), seen)
        assertTrue("final progress too small: ${seen.last()}", seen.last() >= 3000L)
    }
}
