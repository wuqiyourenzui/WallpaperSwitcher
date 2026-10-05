package com.wallpaperswitcher.engine

/**
 * 清晰度增强 (SettingsKeys.CLARITY_MODE)：用户要求"只有开启和关闭"，算法在打开后
 * 用 `enhance_algo` 二选一（FSR1 EASU/RCAS 或 Anime4K）。
 *
 * 历史值的映射：只有 `"off"` 算关闭；`"auto"` / `"super"` / 旧的 `"strong"` /
 * 键缺失都算开启（老安装的默认行为因此保留），开启时统一用超分管线的锐化档
 * [ENHANCE_SCALE]。
 */
object ClarityMode {

    const val ON = "on"
    const val OFF = "off"

    /** 开启时的锐化档（见 WallpaperRenderer.sharpnessFor）。 */
    private const val ENHANCE_SCALE = 1.5f

    /** 只有 `"off"` 是关；其余（含缺失/历史值）都是开。 */
    fun normalize(stored: String?): String = when (stored) {
        OFF -> OFF
        else -> ON
    }

    fun isEnabled(stored: String?): Boolean = normalize(stored) == ON

    fun sharpnessScale(stored: String?): Float = if (isEnabled(stored)) ENHANCE_SCALE else 0f

    /** 开启时走超分增强分支（shader 里还会按放大倍数决定实际强度）。 */
    fun boostsQuality(stored: String?): Boolean = isEnabled(stored)
}
