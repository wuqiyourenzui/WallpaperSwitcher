package com.wallpaperswitcher.ui.theme

import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.ui.screens.CLARITY_KEY_AUTO
import com.wallpaperswitcher.ui.screens.CLARITY_KEY_OFF
import com.wallpaperswitcher.ui.screens.CLARITY_KEY_STRONG
import com.wallpaperswitcher.ui.screens.ROTATE_KEY_CCW
import com.wallpaperswitcher.ui.screens.ROTATE_KEY_CW
import com.wallpaperswitcher.ui.screens.clarityKeyOf
import com.wallpaperswitcher.ui.screens.clarityOptions
import com.wallpaperswitcher.ui.screens.rotateDirectionClockwise
import com.wallpaperswitcher.ui.screens.rotateDirectionKey
import com.wallpaperswitcher.ui.screens.rotateDirectionOptions
import com.wallpaperswitcher.ui.screens.scaleModeOf
import com.wallpaperswitcher.ui.screens.scaleModeOptions
import com.wallpaperswitcher.ui.screens.switchModeOf
import com.wallpaperswitcher.ui.screens.switchModeOptions
import com.wallpaperswitcher.ui.screens.themeModeKeyOf
import com.wallpaperswitcher.ui.screens.themeModeOptions
import com.wallpaperswitcher.ui.screens.transitionKeyOf
import com.wallpaperswitcher.ui.screens.transitionOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页的每个「当前值 + 下拉 → 选项面板」行（图 1 的形态）都由两张东西拼出来：
 * 一张纯数据的选项表（`xxxOptions()`）和一条"存的值 → 面板认得的 key"的归一化规则
 * （`xxxKeyOf()`）。这两样一起决定用户看到什么、能不能选回原来的值，而它们都不碰
 * Compose / Room，所以在这里钉住：
 *
 *  1. **不丢选项**：枚举里的每一档都在面板里（漏一档 = 用户选不回自己原来的设置）；
 *  2. **key 能回头**：选项 key 能映射回原来的枚举 / 布尔，否则写库时要么崩
 *     （`valueOf` 抛异常）要么写错值；
 *  3. **未知值有归宿**：老版本、手改过的库、备份恢复进来的陌生值，归一化之后必须
 *     落在面板里，而且要和渲染端的处理一致（否则行右侧会显示一个用户根本没选过的值）；
 *  4. **文案不串行**：两个选项指向同一个字符串（经典复制粘贴事故）会在这里失败。
 */
class HiOptionLogicTest {

    @Test
    fun `switch mode panel offers every SwitchMode and every key maps back`() {
        val options = switchModeOptions()
        assertEquals(SwitchMode.entries.map { it.name }, options.map { it.key })
        SwitchMode.entries.forEach { mode ->
            assertEquals(mode, switchModeOf(mode.name))
        }
        // 未知 key 返回 null，而不是让 valueOf 在 UI 线程抛异常。
        assertNull(switchModeOf("FLIP"))
    }

    @Test
    fun `scale mode panel offers every ScaleMode and every key maps back`() {
        val options = scaleModeOptions()
        assertEquals(ScaleMode.entries.map { it.name }, options.map { it.key })
        ScaleMode.entries.forEach { mode ->
            assertEquals(mode, scaleModeOf(mode.name))
        }
        assertNull(scaleModeOf("COVER"))
    }

    @Test
    fun `clarity panel offers the three stored values`() {
        assertEquals(
            listOf(CLARITY_KEY_AUTO, CLARITY_KEY_OFF, CLARITY_KEY_STRONG),
            clarityOptions().map { it.key }
        )
    }

    @Test
    fun `clarity normalises anything unknown to auto`() {
        // 渲染端 clarityStrength(): "off" -> 0f, "strong" -> 1.6f, 其余 -> 1.25f(自动)。
        assertEquals(CLARITY_KEY_OFF, clarityKeyOf(CLARITY_KEY_OFF))
        assertEquals(CLARITY_KEY_STRONG, clarityKeyOf(CLARITY_KEY_STRONG))
        assertEquals(CLARITY_KEY_AUTO, clarityKeyOf(CLARITY_KEY_AUTO))
        assertEquals(CLARITY_KEY_AUTO, clarityKeyOf(""))
        assertEquals(CLARITY_KEY_AUTO, clarityKeyOf("AUTO"))
        assertEquals(CLARITY_KEY_AUTO, clarityKeyOf("high"))
    }

    @Test
    fun `rotate direction panel round-trips the stored boolean`() {
        assertEquals(listOf(ROTATE_KEY_CW, ROTATE_KEY_CCW), rotateDirectionOptions().map { it.key })
        assertEquals(ROTATE_KEY_CW, rotateDirectionKey(clockwise = true))
        assertEquals(ROTATE_KEY_CCW, rotateDirectionKey(clockwise = false))
        assertTrue(rotateDirectionClockwise(ROTATE_KEY_CW))
        assertFalse(rotateDirectionClockwise(ROTATE_KEY_CCW))
        // 面板里点回来的 key 必须还原成它当初代表的方向。
        assertTrue(rotateDirectionClockwise(rotateDirectionKey(true)))
        assertFalse(rotateDirectionClockwise(rotateDirectionKey(false)))
    }

    @Test
    fun `transition panel offers every value the ViewModel accepts`() {
        assertEquals(
            listOf(
                SettingsKeys.SWITCH_TRANSITION_FADE,
                SettingsKeys.SWITCH_TRANSITION_SLIDE,
                SettingsKeys.SWITCH_TRANSITION_ZOOM,
                SettingsKeys.SWITCH_TRANSITION_NONE
            ),
            transitionOptions().map { it.key }
        )
        // 每个能写进去的值都必须原样回显（setSwitchTransition 接受这四个）。
        transitionOptions().forEach { option ->
            assertEquals(option.key, transitionKeyOf(option.key))
        }
        // 默认值（"无"）一定要能选中，否则用户没法从别的动画回到"取消过渡"。
        assertTrue(hasHiOption(transitionOptions(), SettingsKeys.SWITCH_TRANSITION_DEFAULT))
    }

    @Test
    fun `transition treats an unknown stored value as no transition`() {
        // 渲染端只对 fade/slide/zoom 做特殊处理，其余值都是"没有过渡"。
        assertEquals(SettingsKeys.SWITCH_TRANSITION_NONE, transitionKeyOf("wipe"))
        assertEquals(SettingsKeys.SWITCH_TRANSITION_NONE, transitionKeyOf(""))
        // 大小写不同 = 另一个值（渲染端也是逐字符比较的）。
        assertEquals(SettingsKeys.SWITCH_TRANSITION_NONE, transitionKeyOf("FADE"))
        assertEquals(SettingsKeys.SWITCH_TRANSITION_FADE, transitionKeyOf(SettingsKeys.SWITCH_TRANSITION_FADE))
    }

    @Test
    fun `theme mode panel offers every ThemeMode with its own label`() {
        assertEquals(ThemeMode.entries.map { it.value }, themeModeOptions().map { it.key })
        assertEquals(ThemeMode.entries.map { it.labelRes }, themeModeOptions().map { it.labelRes })
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode.value, themeModeKeyOf(mode.value))
        }
        // 未知值 = 跟随系统（ThemeMode.from 的约定），面板里必须能选中它。
        assertEquals(ThemeMode.SYSTEM.value, themeModeKeyOf("nonsense"))
        assertEquals(ThemeMode.SYSTEM.value, themeModeKeyOf(""))
    }

    @Test
    fun `the current value of every setting is present in its panel`() {
        // 验收要求「当前值必须出现在面板里」：任何可能被存下来的值，归一化之后都
        // 要能在表里找到，否则用户看不到自己现在是哪一档。
        SwitchMode.entries.forEach { assertTrue(hasHiOption(switchModeOptions(), it.name)) }
        ScaleMode.entries.forEach { assertTrue(hasHiOption(scaleModeOptions(), it.name)) }

        listOf(CLARITY_KEY_AUTO, CLARITY_KEY_OFF, CLARITY_KEY_STRONG, "AUTO", "", "high").forEach {
            assertTrue("clarity: $it", hasHiOption(clarityOptions(), clarityKeyOf(it)))
        }
        listOf(
            SettingsKeys.SWITCH_TRANSITION_FADE,
            SettingsKeys.SWITCH_TRANSITION_SLIDE,
            SettingsKeys.SWITCH_TRANSITION_ZOOM,
            SettingsKeys.SWITCH_TRANSITION_NONE,
            "wipe",
            ""
        ).forEach {
            assertTrue("transition: $it", hasHiOption(transitionOptions(), transitionKeyOf(it)))
        }
        (ThemeMode.entries.map { it.value } + listOf("nonsense", "LIGHT")).forEach {
            assertTrue("theme mode: $it", hasHiOption(themeModeOptions(), themeModeKeyOf(it)))
        }
        listOf(rotateDirectionKey(true), rotateDirectionKey(false)).forEach {
            assertTrue("rotate: $it", hasHiOption(rotateDirectionOptions(), it))
        }
    }

    @Test
    fun `every option in every panel carries its own label resource`() {
        val panels = listOf(
            "switch mode" to switchModeOptions(),
            "scale mode" to scaleModeOptions(),
            "clarity" to clarityOptions(),
            "rotate direction" to rotateDirectionOptions(),
            "transition" to transitionOptions(),
            "theme mode" to themeModeOptions()
        )
        panels.forEach { (name, options) ->
            // 两个选项指向同一个字符串 = 复制粘贴事故（"滑动"和"缩放"显示成一样）。
            assertEquals(name, options.size, options.map { it.labelRes }.toSet().size)
            options.forEach { option ->
                assertTrue("$name: 0 不是合法文案资源", option.labelRes != 0)
                assertTrue("$name: key 不能为空", option.key.isNotEmpty())
            }
            assertEquals(name, options.size, options.map { it.key }.toSet().size)
        }
    }

    @Test
    fun `each option is wired to the matching string resource`() {
        // 顺序写反（fade 指到 transition_slide 之类）不会崩，只会一直显示错的效果名，
        // 所以这里逐个钉死。
        assertEquals(
            listOf(
                R.string.transition_fade,
                R.string.transition_slide,
                R.string.transition_zoom,
                R.string.transition_none
            ),
            transitionOptions().map { it.labelRes }
        )
        assertEquals(R.string.switch_mode_random, hiOptionLabelRes(switchModeOptions(), "RANDOM"))
        assertEquals(R.string.switch_mode_sequential, hiOptionLabelRes(switchModeOptions(), "SEQUENTIAL"))
        assertEquals(R.string.switch_mode_shuffle, hiOptionLabelRes(switchModeOptions(), "SHUFFLE"))
        assertEquals(R.string.scale_mode_fill, hiOptionLabelRes(scaleModeOptions(), "FILL"))
        assertEquals(R.string.scale_mode_fit, hiOptionLabelRes(scaleModeOptions(), "FIT"))
        assertEquals(R.string.scale_mode_stretch, hiOptionLabelRes(scaleModeOptions(), "STRETCH"))
        assertEquals(R.string.clarity_auto, hiOptionLabelRes(clarityOptions(), CLARITY_KEY_AUTO))
        assertEquals(R.string.clarity_off, hiOptionLabelRes(clarityOptions(), CLARITY_KEY_OFF))
        assertEquals(R.string.clarity_strong, hiOptionLabelRes(clarityOptions(), CLARITY_KEY_STRONG))
        assertEquals(R.string.rotate_clockwise, hiOptionLabelRes(rotateDirectionOptions(), ROTATE_KEY_CW))
        assertEquals(
            R.string.rotate_counter_clockwise,
            hiOptionLabelRes(rotateDirectionOptions(), ROTATE_KEY_CCW)
        )
        assertEquals(R.string.theme_mode_system, hiOptionLabelRes(themeModeOptions(), ThemeMode.SYSTEM.value))
        assertEquals(R.string.theme_mode_light, hiOptionLabelRes(themeModeOptions(), ThemeMode.LIGHT.value))
        assertEquals(R.string.theme_mode_dark, hiOptionLabelRes(themeModeOptions(), ThemeMode.DARK.value))
    }

    @Test
    fun `label lookup returns null for a key that is not in the panel`() {
        // 行右侧的"当前值"靠它取文案；取不到时调用方显示空字符串，而不是崩掉
        // （HiOptionPickerRow：`hiOptionLabelRes(...)?.let { stringResource(it) }.orEmpty()`）。
        assertNull(hiOptionLabelRes(switchModeOptions(), "FLIP"))
        assertNull(hiOptionLabelRes(clarityOptions(), "high"))
        assertNull(hiOptionLabelRes(emptyList(), "RANDOM"))
    }
}
