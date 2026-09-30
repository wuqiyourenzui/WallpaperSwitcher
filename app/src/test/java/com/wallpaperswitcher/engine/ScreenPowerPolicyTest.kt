package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Power-save decision of the live wallpaper engine (see [ScreenPowerPolicy]).
 *
 * The engine gets three independent signals - the system's visibility callback,
 * our own activity's foreground state and the screen on/off state - and they
 * contradict each other in real use:
 *
 * - a "wallpaper is visible again" callback arrived 13s after the screen-off
 *   event (tablet log), which must not resume decoding into an invisible surface;
 * - a "visible" callback arriving while our app is in the foreground used to
 *   resume the video/audio and be paused again by the next app-foreground signal:
 *   up to ~540 ON/OFF flips per hour during picker interaction, every flip a
 *   decoder pause/resume plus an audio restart.
 *
 * The state is therefore DERIVED from the inputs instead of remembering the last
 * request, which is what these tests pin down.
 */
class ScreenPowerPolicyTest {

    @Test
    fun theScreenBeingOffAlwaysPauses() {
        // Even a "visible" callback (the spurious one) cannot resume while the
        // screen is off.
        assertTrue(
            ScreenPowerPolicy.shouldPause(
                wallpaperVisible = true, appInForeground = false, screenInteractive = false
            )
        )
        assertEquals(
            listOf("screen-off"),
            ScreenPowerPolicy.pauseReasons(
                wallpaperVisible = true, appInForeground = false, screenInteractive = false
            )
        )
    }

    @Test
    fun aCoveredWallpaperIsPaused() {
        assertTrue(
            ScreenPowerPolicy.shouldPause(
                wallpaperVisible = false, appInForeground = false, screenInteractive = true
            )
        )
    }

    @Test
    fun ourOwnUiInFrontPausesEvenWhileTheWallpaperLooksVisible() {
        // The flip-flop case: the visibility callback and the app-foreground
        // signal disagree. The derived state must be PAUSE - the old
        // "last request wins" model resumed here (audio restarted behind our own
        // UI) and paused again on the next app signal.
        assertTrue(
            ScreenPowerPolicy.shouldPause(
                wallpaperVisible = true, appInForeground = true, screenInteractive = true
            )
        )
        assertEquals(
            listOf("app-foreground"),
            ScreenPowerPolicy.pauseReasons(
                wallpaperVisible = true, appInForeground = true, screenInteractive = true
            )
        )
    }

    @Test
    fun onlyVisibleAndInteractiveAndAppHiddenRunsAtFullSpeed() {
        assertFalse(
            ScreenPowerPolicy.shouldPause(
                wallpaperVisible = true, appInForeground = false, screenInteractive = true
            )
        )
        assertTrue(
            ScreenPowerPolicy.pauseReasons(
                wallpaperVisible = true, appInForeground = false, screenInteractive = true
            ).isEmpty()
        )
    }

    @Test
    fun everyDisagreeingInputIsReportedInTheLogReason() {
        assertEquals(
            listOf("screen-off", "visibility", "app-foreground"),
            ScreenPowerPolicy.pauseReasons(
                wallpaperVisible = false, appInForeground = true, screenInteractive = false
            )
        )
    }

    @Test
    fun onlyAPureVisibilityCoverCountsAsABlip() {
        // A short "covered" report from the ROM (the floating button's overlay
        // remove/add cycle) is the only case that may be delayed; it is what the
        // engine coalesces so a 250-400ms blip never pauses the decoder.
        assertTrue(ScreenPowerPolicy.isVisibilityBlipOnly(listOf("visibility")))
        // Everything else must apply at once: muting has to happen now.
        assertFalse(ScreenPowerPolicy.isVisibilityBlipOnly(listOf("app-foreground")))
        assertFalse(ScreenPowerPolicy.isVisibilityBlipOnly(listOf("screen-off", "visibility")))
        assertFalse(
            ScreenPowerPolicy.isVisibilityBlipOnly(listOf("visibility", "app-foreground"))
        )
        assertFalse(ScreenPowerPolicy.isVisibilityBlipOnly(emptyList()))
    }
}
