package com.wallpaperswitcher.engine

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频色彩元数据的解读（见 VideoColor）。
 *
 * 这条只做"把事实写进日志 + 判断要不要告警"，所以测的是**判定不能反**：
 * 把 SDR 判成 HDR 会天天刷假告警，把 HDR 判成 SDR 就是"发灰了却查不出原因"。
 */
class VideoColorTest {

    @Test
    fun sdrBt709IsNotFlagged() {
        assertFalse(
            VideoColor.needsToneMapping(
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            )
        )
        assertTrue(
            VideoColor.describe(
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_LIMITED
            ).contains("hdr=false")
        )
    }

    @Test
    fun pqAndHlgAreFlaggedRegardlessOfStandard() {
        assertTrue(
            VideoColor.needsToneMapping(
                MediaFormat.COLOR_STANDARD_BT2020,
                MediaFormat.COLOR_TRANSFER_ST2084
            )
        )
        assertTrue(
            VideoColor.needsToneMapping(
                MediaFormat.COLOR_STANDARD_BT709, // 容器标错也不影响判断
                MediaFormat.COLOR_TRANSFER_HLG
            )
        )
    }

    @Test
    fun wideGamutAloneIsEnough() {
        // BT.2020 + SDR 传输：色域仍超出管线能表达的范围，值得告警。
        assertTrue(
            VideoColor.needsToneMapping(
                MediaFormat.COLOR_STANDARD_BT2020,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            )
        )
    }

    @Test
    fun missingMetadataIsNotHdr() {
        // 0 是"容器没写"，不能当成 HDR，否则大量老文件都会刷告警。
        assertFalse(VideoColor.needsToneMapping(standard = 0, transfer = 0))
        assertTrue(VideoColor.describe(0, 0, 0).contains("unknown(0)"))
    }

    @Test
    fun describeNamesTheActualValues() {
        assertEquals(
            "BT2020/PQ/ST2084" + "/FULL hdr=true",
            VideoColor.describe(
                MediaFormat.COLOR_STANDARD_BT2020,
                MediaFormat.COLOR_TRANSFER_ST2084,
                MediaFormat.COLOR_RANGE_FULL
            )
        )
    }
}
