package com.wallpaperswitcher.ui.screens

import com.wallpaperswitcher.viewmodel.StorageCleanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「存储与流量守门」页的纯逻辑：字节数怎么变成人话，以及"清理完该说哪句话"。
 *
 * 这一页最容易被用户当成 bug 的两件事都在这里钉住：`1024.0 KB` 这种该进位却没进位的
 * 大小，和"一个文件都没清掉却报释放了 3MB"。两者都不需要 Compose / Android 框架
 * 就能测（见 StorageScreen.kt 的纯逻辑段）。
 */
class StorageFormatTest {

    private val kb = 1024L
    private val mb = kb * 1024
    private val gb = mb * 1024

    // --- 字节格式化 ----------------------------------------------------------

    @Test
    fun zeroAndNegativeBytesReadAsZero() {
        assertEquals("0 B", formatStorageBytes(0L))
        // 负数只可能是脏数据（统计的是文件长度之和），不能显示成 "-1 B"。
        assertEquals("0 B", formatStorageBytes(-1L))
    }

    @Test
    fun underOneKilobyteIsShownInWholeBytes() {
        assertEquals("1 B", formatStorageBytes(1L))
        assertEquals("1023 B", formatStorageBytes(1023L))
        // B 档不带小数：1023.0 B 既啰嗦又暗示了不存在的精度。
        assertFalse(formatStorageBytes(1023L).contains('.'))
    }

    @Test
    fun oneKilobyteIsAlreadyTheFirstDecimalStep() {
        assertEquals("1.0 KB", formatStorageBytes(kb))
    }

    @Test
    fun justUnderTheNextUnitKeepsItsOwnUnit() {
        // 1048474 B = 1023.9 KB 左右：还差一格才到 MB，必须留在 KB 档。
        assertEquals("1023.9 KB", formatStorageBytes(1_048_474L))
    }

    @Test
    fun roundingUpTo1024PromotesTheUnitInsteadOfPrinting1024() {
        // 这正是不做进位检查时会出现的那种输出：1048575 B 是 1023.999 KB，
        // 四舍五入到一位小数就成了 "1024.0 KB"，看着像 bug（该显示 1.0 MB）。
        assertEquals("1.0 MB", formatStorageBytes(mb - 1))
        assertEquals("1.0 MB", formatStorageBytes(mb))
    }

    @Test
    fun megabytesAndGigabytes() {
        assertEquals("1.5 MB", formatStorageBytes(mb + mb / 2))
        // 真机上积过的那批残留：280MB。
        assertEquals("280.0 MB", formatStorageBytes(280L * mb))
        assertEquals("1.0 GB", formatStorageBytes(gb))
        assertEquals("1.5 GB", formatStorageBytes(gb + gb / 2))
    }

    @Test
    fun theLargestPossibleValueStaysInsideTheLastUnit() {
        // Long 的最大值约等于 8192 PB：不能溢出，也不能滑出单位表。
        assertEquals("8192.0 PB", formatStorageBytes(Long.MAX_VALUE))
    }

    @Test
    fun noValueEverFormatsAsANumberThatShouldHaveCarried() {
        // 不变式：除了封顶的 PB，数字部分必须落在 [0, 1024)，小数位固定一位。
        val samples = buildList {
            addAll(0L..2050L)                 // B -> KB 的边界
            addAll(1_048_000L..1_049_000L)    // KB -> MB 的进位边界
            addAll(1_073_740_000L..1_073_742_000L) // MB -> GB 的进位边界
            addAll(listOf(mb - 1, mb, gb - 1, gb, Long.MAX_VALUE))
        }
        val units = setOf("B", "KB", "MB", "GB", "TB", "PB")
        samples.forEach { bytes ->
            val text = formatStorageBytes(bytes)
            val unit = text.substringAfter(' ')
            val number = text.substringBefore(' ')

            assertTrue("$bytes -> $text（单位不在单位表里）", unit in units)
            if (unit != "PB") {
                assertTrue("$bytes -> $text（该进位了）", number.toDouble() < 1024.0)
            }
            if (unit == "B") {
                assertFalse("$bytes -> $text（B 档不该有小数）", number.contains('.'))
            } else {
                assertEquals("$bytes -> $text（小数位不是一位）", 1, number.substringAfter('.').length)
            }
        }
    }

    // --- 清理完说哪句话 -------------------------------------------------------

    @Test
    fun aCleanThatRemovedFilesReportsWhatWasFreed() {
        assertEquals(
            StorageCleanResult(files = 3, bytes = 12_345L),
            cleanedStorageNote(StorageCleanResult(files = 3, bytes = 12_345L)),
        )
    }

    @Test
    fun aCleanThatRemovedNothingSaysNothing() {
        // 0 个文件 -> 不说"已清理 0 个文件"，页面走 storage_nothing_orphan。
        assertNull(cleanedStorageNote(StorageCleanResult(files = 0, bytes = 0L)))
    }

    @Test
    fun aByteCountWithoutFilesIsNotReportedAsFreedSpace() {
        // 脏数据：字节数是文件长度之和，一个文件都没删却报"释放了 5.0 MB"，
        // 用户会当成页面在骗人（所以这条规则只看 files，不看 bytes）。
        assertNull(cleanedStorageNote(StorageCleanResult(files = 0, bytes = 5L * mb)))
    }

    @Test
    fun filesWithZeroBytesStillCountAsCleaned() {
        // 真删掉了文件（0 长度的占位/空文件）就该说清掉了，哪怕释放量是 0 B。
        assertEquals(
            StorageCleanResult(files = 2, bytes = 0L),
            cleanedStorageNote(StorageCleanResult(files = 2, bytes = 0L)),
        )
    }
}
