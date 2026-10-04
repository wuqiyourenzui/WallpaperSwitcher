package com.wallpaperswitcher.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Log shortening (see [LogText]).
 *
 * The runtime log is written to a file that the user can export and share, and
 * full content URIs are both long (a SAF path with Chinese folder names is ~400
 * characters once percent-encoded) and personal (they carry the folder names).
 */
class LogTextTest {

    /**
     * The runtime-log size budget is a BYTE budget (it is compared with
     * `File.length()`), and a Chinese line costs three bytes per character.
     * Counting `String.length` made the 2MB rotation fire about three times late.
     */
    @Test
    fun `a logged line is measured in utf8 bytes plus its newline`() {
        assertEquals(1, AppLog.lineBytes(""))
        assertEquals("ascii: 1 byte per char", 5, AppLog.lineBytes("abcd"))
        // 2 CJK characters = 6 bytes, not 2.
        assertEquals(7, AppLog.lineBytes("壁纸"))
        assertTrue(
            "CJK must count more than its character count",
            AppLog.lineBytes("壁纸切换") > "壁纸切换".length
        )
    }

    @Test
    fun emptyValuesBecomeADash() {
        assertEquals("-", LogText.short(null))
        assertEquals("-", LogText.short(""))
        assertEquals("-", LogText.short("   "))
        assertEquals("-", LogText.folder(null))
    }

    @Test
    fun contentUriKeepsItsAuthorityAndTheLastSegment() {
        assertEquals(
            "content://media/…/1230",
            LogText.short("content://media/external/images/media/1230")
        )
    }

    @Test
    fun longSafUriIsCapped() {
        val saf = "content://com.android.fileexplorer.documents/document/primary%3A%2Fstorage" +
            "%2Femulated%2F0%2F%E9%9B%A8%E6%B3%A2%2FSELF_S%20(7).jpg"
        val short = LogText.short(saf)
        assertTrue("capped: ${short.length}", short.length <= LogText.MAX)
        assertTrue(short.startsWith("content://com.android.fileexplorer.documents"))
        assertTrue(short.endsWith("…"))
        // The personal folder names never reach the log line.
        assertTrue(!short.contains("雨波"))
    }

    @Test
    fun plainPathKeepsOnlyItsFileName() {
        assertEquals("a.jpg", LogText.short("/storage/emulated/0/DCIM/a.jpg"))
        // A file:// URI keeps its scheme so the two forms stay distinguishable.
        assertEquals("file:///…/cover.png", LogText.short("file:///storage/emulated/0/Pictures/cover.png"))
    }

    @Test
    fun folderKeepsTheLastTwoSegments() {
        assertEquals(
            "…/雨波HaneAme - 剑星/a.jpg",
            LogText.folder("/storage/emulated/0/DCIM/雨波HaneAme - 剑星/a.jpg")
        )
        assertEquals("…/Download/MiShare", LogText.folder("/storage/emulated/0/Download/MiShare/"))
        assertEquals(
            "…/document/primary%3APictures",
            LogText.folder("content://x/document/primary%3APictures")
        )
    }

    @Test
    fun folderIsCappedToo() {
        val deep = "/storage/emulated/0/" + "x".repeat(200) + "/" + "y".repeat(200)
        assertTrue(LogText.folder(deep).length <= LogText.MAX)
    }
}
