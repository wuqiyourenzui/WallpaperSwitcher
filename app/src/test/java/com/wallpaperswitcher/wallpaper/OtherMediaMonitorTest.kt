package com.wallpaperswitcher.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「让位静音」的判定（见 OtherMediaMonitor）。
 *
 * 公开 SDK 的 `AudioPlaybackConfiguration` 不暴露 uid / isActive，所以只能拿
 * "媒体类条目数" 减去 "自己那一条" 来判断别人在不在放。这条减法最容易出两类错：
 * 把自己的播放当成别人（壁纸永久静音），或者把别人当成自己（该静音时还在响）。
 */
class OtherMediaMonitorTest {

    @Test
    fun onlyOurOwnPlaybackIsNotSomebodyElse() {
        // 名单里就自己一条：不该静音。
        assertFalse(othersMediaActive(mediaLike = 1, ours = 1))
        // 什么都没在放。
        assertFalse(othersMediaActive(mediaLike = 0, ours = 0))
        // 我们没在放、名单也空：不是别人。
        assertFalse(othersMediaActive(mediaLike = 0, ours = 1))
    }

    @Test
    fun anotherAppInTheListMeansDuck() {
        // 自己 + 别人：让位。
        assertTrue(othersMediaActive(mediaLike = 2, ours = 1))
        // 我们被静音后自己那条从名单消失，只剩别人：仍然让位（不能因为"没减到"
        // 就把别人的播放当成自己的）。
        assertTrue(othersMediaActive(mediaLike = 1, ours = 0))
    }

    @Test
    fun moreThanOneOtherKeepsDucking() {
        assertTrue(othersMediaActive(mediaLike = 3, ours = 1))
        assertTrue(othersMediaActive(mediaLike = 3, ours = 0))
    }
}
