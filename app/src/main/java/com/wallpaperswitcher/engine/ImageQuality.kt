package com.wallpaperswitcher.engine

import android.graphics.Bitmap

/**
 * 低画质检测（降噪分支）：判断一张静态图"脏不脏"，给渲染端一个 0..0.6 的降噪
 * 强度。
 *
 * 思路：看每个像素与四邻的最大亮度差，取整幅小样的**中位数**作为"像素级噪声
 * 地板"：
 *  - 干净图：绝大多数像素与邻居几乎相同（中位数 ≈ 0），即使有边缘也不影响中位数；
 *  - 噪点 / JPEG 块效应：几乎每个像素都被抬高，中位数随噪声幅度上升；
 *  - 平滑渐变：每像素差只有几级，落在"地板"以下。
 *
 * 这个值只决定"要不要降噪、降多少"，**是否真的磨掉某个像素由 shader 端决定**：
 * 双边权重按颜色距离陡峭衰减，所以纹理/边缘（大色差）基本不动，只有小色差的
 * 像素级杂讯被平均掉——即使检测把细密纹理判成"有噪声"也不会糊内容。
 *
 * 只在静态图解码后跑一次（4 个 16x16 角落patch，最多 1024 个采样点），视频不做
 * 逐帧分析——视频的降噪强度按放大倍数给一个固定的小值。
 */
object ImageQuality {

    /** 中位邻居差低于这个亮度级（0..255）视为干净：不降噪。 */
    internal const val NOISE_FLOOR = 8.0f

    /** 中位邻居差达到这个亮度级时用满强度。 */
    internal const val NOISE_FULL = 32.0f

    /** 强度上限：再多就该叫磨皮了。 */
    private const val MAX_STRENGTH = 0.6f

    /** 纯逻辑核心：ARGB 像素、[width] x [height]（至少 3x3）。 */
    internal fun denoiseStrength(pixels: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || pixels.size < width * height) return 0f
        val diffs = ArrayList<Int>((width - 2) * (height - 2))
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val c = luma(pixels[y * width + x])
                val n = luma(pixels[(y - 1) * width + x])
                val s = luma(pixels[(y + 1) * width + x])
                val w = luma(pixels[y * width + x - 1])
                val e = luma(pixels[y * width + x + 1])
                diffs.add(
                    maxOf(
                    kotlin.math.abs(c - n),
                    kotlin.math.abs(c - s),
                    kotlin.math.abs(c - w),
                    kotlin.math.abs(c - e),
                    )
                )
            }
        }
        if (diffs.isEmpty()) return 0f
        diffs.sort()
        val median = diffs[diffs.size / 2].toFloat()
        return (((median - NOISE_FLOOR) / (NOISE_FULL - NOISE_FLOOR))
            .coerceIn(0f, 1f) * MAX_STRENGTH)
    }

    /**
     * 静态图的降噪强度：取四个角的 16x16 patch 平均（整图取一个 patch 会被
     * 局部内容带偏）。硬件位图 / 解不开的图返回 0（不降噪）。
     */
    fun denoiseStrength(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val size = 16
        if (w < size || h < size) return 0f
        val positions = arrayOf(
            0 to 0,
            (w - size) to 0,
            0 to (h - size),
            (w - size) to (h - size),
        )
        var total = 0f
        var count = 0
        for ((x, y) in positions) {
            try {
                val pixels = IntArray(size * size)
                bitmap.getPixels(pixels, 0, size, x, y, size, size)
                total += denoiseStrength(pixels, size, size)
                count++
            } catch (_: Throwable) {
                // Hardware bitmap / recycled bitmap: no analysis, no denoise.
                return 0f
            }
        }
        return if (count == 0) 0f else total / count
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
