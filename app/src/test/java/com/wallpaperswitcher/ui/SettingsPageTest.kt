package com.wallpaperswitcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏 tab: 设置主界面和它的所有子页都必须保持「设置」高亮。用户反馈过
 * "从设置点进下一个界面，底栏会跳回首页"——就是这份集合漏了子页。
 */
class SettingsPageTest {

    @Test
    fun `the settings hub and every sub-page keep the settings tab`() {
        listOf(
            Screen.Settings,
            Screen.WallpaperSettings,
            Screen.SwitchMethods,
            Screen.FolderScan,
            Screen.ButtonAppearance,
            Screen.Appearance,
            Screen.Favorites,
            Screen.Recent,
            Screen.Storage,
        ).forEach { screen ->
            assertTrue("$screen must keep the settings tab", screen.isSettingsPage())
        }
    }

    @Test
    fun `home and subscription pages do not claim the settings tab`() {
        listOf(
            Screen.Home,
            Screen.Subscriptions,
            Screen.GroupDetail(1L),
            Screen.Browse(1L),
            Screen.SubscriptionArticles(1L),
            Screen.RssLogin(1L),
            Screen.RssSourceEdit(1L),
        ).forEach { screen ->
            assertFalse("$screen must not claim the settings tab", screen.isSettingsPage())
        }
    }
}
