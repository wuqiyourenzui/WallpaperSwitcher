package com.wallpaperswitcher.engine

/**
 * 画质增强的放大算法（渲染端 `uEnhanceMode`）。
 *
 * 两种算法互斥：同时打开只会走 Anime4K（设置界面也会把另一个自动关掉）。
 * 它们只在「清晰度增强 = 画质增强（超分）」且素材被放大时生效。
 */
object EnhanceMode {
    /** 内置：4-tap 双三次（4.9.151 起的默认超分）。 */
    const val BUILT_IN = 0
    /** FSR1 EASU + RCAS。 */
    const val FSR1 = 1
    /** Anime4K（Original x2 线稿算法，单 pass 移植）。 */
    const val ANIME4K = 2

    fun of(fsr1: Boolean, anime4k: Boolean): Int = when {
        anime4k -> ANIME4K
        fsr1 -> FSR1
        else -> BUILT_IN
    }
}
