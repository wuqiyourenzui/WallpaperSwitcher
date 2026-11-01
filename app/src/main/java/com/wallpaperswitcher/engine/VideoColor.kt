package com.wallpaperswitcher.engine

import android.media.MediaFormat

/**
 * 视频色彩元数据的解读（纯计算，单测见 VideoColorTest）。
 *
 * 为什么需要它：这条 GL 管线把视频解到 RGBA Surface 后按 **SDR** 采样，没有任何
 * tonemap / 色彩管理代码。片源是 SDR（BT.709）时完全没问题；一旦是 HDR10（PQ）
 * 或 HLG，画面会发灰/偏暗，而日志里一个字都看不出来 —— 排查只能靠猜。
 *
 * 这里只做"把事实打出来 + 判断要不要告警"，不改渲染行为。
 */
internal object VideoColor {

    fun standardName(value: Int): String = when (value) {
        MediaFormat.COLOR_STANDARD_BT709 -> "BT709"
        MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT601_PAL"
        MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT601_NTSC"
        MediaFormat.COLOR_STANDARD_BT2020 -> "BT2020"
        else -> "unknown($value)"
    }

    fun transferName(value: Int): String = when (value) {
        MediaFormat.COLOR_TRANSFER_LINEAR -> "LINEAR"
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
        MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ/ST2084"
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
        else -> "unknown($value)"
    }

    fun rangeName(value: Int): String = when (value) {
        MediaFormat.COLOR_RANGE_FULL -> "FULL"
        MediaFormat.COLOR_RANGE_LIMITED -> "LIMITED"
        else -> "unknown($value)"
    }

    /**
     * 是否需要 tonemap 才不会发灰/偏暗：PQ / HLG 传输，或 BT.2020 宽色域。
     *
     * @param standard [MediaFormat.KEY_COLOR_STANDARD]（缺失时 0 → unknown，不算 HDR）
     * @param transfer [MediaFormat.KEY_COLOR_TRANSFER]（同上）
     */
    fun needsToneMapping(standard: Int, transfer: Int): Boolean =
        transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            transfer == MediaFormat.COLOR_TRANSFER_HLG ||
            standard == MediaFormat.COLOR_STANDARD_BT2020

    /** 一行可读描述，直接进日志：`BT709/SDR/LIMITED hdr=false`。 */
    fun describe(standard: Int, transfer: Int, range: Int): String =
        "${standardName(standard)}/${transferName(transfer)}/${rangeName(range)} " +
            "hdr=${needsToneMapping(standard, transfer)}"
}
