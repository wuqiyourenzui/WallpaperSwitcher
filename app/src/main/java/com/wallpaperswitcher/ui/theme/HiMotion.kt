package com.wallpaperswitcher.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * HyperOS / Miuix 动效令牌。
 *
 * 和 [HiDims] 一样，把「时长 / 曲线」收敛成一处：页面切换、底栏选中、卡片按压、
 * 展开收起都从这里取参数，避免每个页面各写一个 200ms、各配一条曲线。
 *
 * 取向是 Miuix 的「快而稳」：位移短、时长短（150–360ms），进场强调减速
 * （EmphasizedDecelerate：起步快、收尾慢），退场用加速曲线快速让位。
 * 这也是对历史的回应 —— 早期版本用长时长的整屏 crossfade，两个重页面同时
 * 合成导致部分机型掉帧（见 WallpaperSwitcherApp 的注释），所以这里的动效
 * 都是「只动一层、只动轻元素」。
 */
object HiMotion {

    /** 小范围状态变化（颜色、透明度、图标）。 */
    const val ShortMs = 150

    /** 页面进场、展开收起。 */
    const val MediumMs = 240

    /** 大范围移动（整页位移）。 */
    const val LongMs = 360

    /** 强调减速：起步快、收尾慢，Miuix 的进入曲线。 */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 强调加速：用于退场让位。 */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 标准曲线：进出一致的轻量变化。 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 进场动画参数（[T] 取 `Float` 或 `IntOffset` 等被动画的值类型）。 */
    fun <T> enter(durationMs: Int = MediumMs, delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMs, delayMs, EmphasizedDecelerate)

    /** 退场动画参数。 */
    fun <T> exit(durationMs: Int = ShortMs, delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMs, delayMs, EmphasizedAccelerate)

    /** 轻量标准动画参数。 */
    fun <T> standard(durationMs: Int = ShortMs, delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMs, delayMs, Standard)

    /** 底栏选中、状态迁移：收尾略带一点回弹。 */
    fun <T> selection(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    /** 按压反馈：快速、有回弹。 */
    fun <T> press(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh)

    // --- Material 3 Expressive 风格的空间动效（弹簧令牌）----------------------------
    // 参考 M3 2024/2025 的 motion 规范：空间（位置/尺寸/缩放）用弹簧而不是 tween，
    // 因为弹簧在被打断（快速连点、切 Tab）时天然连续，不会出现"重头再来"的顿挫。
    // 这里的 damping/stiffness 取规范里 fast/default/slow 三档的近似值。
    // https://m3.material.io/styles/motion/overview/specs

    /** 空间·快（小面积位移、按压回弹、Tab 内元素）。 */
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 800f)

    /** 空间·默认（页面位移、卡片展开）。 */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 600f)

    /** 空间·慢（大面积/整页）。 */
    fun <T> spatialSlow(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = 320f)
}
