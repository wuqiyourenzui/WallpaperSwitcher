package com.wallpaperswitcher.engine

/**
 * 画质增强的放大算法（渲染端 `uEnhanceMode`）。
 *
 * 设置里现在是一个二选一：`enhance_algo` = "fsr1" / "anime4k"；只有
 * 「清晰度增强」开着且素材被放大时才会走这条增强分支。
 */
object EnhanceMode {
    /** 内置：4-tap 双三次（系统选择器预览引擎等内部使用）。 */
    const val BUILT_IN = 0
    /** FSR1 EASU + RCAS。 */
    const val FSR1 = 1
    /** Anime4K（Original x2 线稿算法，单 pass 移植）。 */
    const val ANIME4K = 2

    /** 设置里存的值。 */
    const val FSR1_KEY = "fsr1"
    const val ANIME4K_KEY = "anime4k"

    /** 存的算法 key → 渲染模式；缺失/未知默认 FSR1（通用素材更稳）。 */
    fun fromKey(key: String?): Int =
        if (key == ANIME4K_KEY) ANIME4K else FSR1

    /**
     * 迁移用：4.9.154 的两个互斥开关 → 新 key。两个都关时默认 FSR1。
     */
    fun legacyKey(fsr1: Boolean, anime4k: Boolean): String =
        if (anime4k) ANIME4K_KEY else FSR1_KEY
}
