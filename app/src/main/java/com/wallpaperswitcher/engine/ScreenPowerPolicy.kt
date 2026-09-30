package com.wallpaperswitcher.engine

/**
 * Pure policy for the renderer's "wallpaper is not visible" power-save state.
 *
 * The engine gets three independent signals - the system's `onVisibilityChanged`,
 * our own activity's foreground state and the screen on/off state - and they do
 * not always agree. Two rules make the state well defined:
 *
 * - **The state is DERIVED from the inputs, never "the last callback wins".**
 *   With last-writer-wins, a `visible=true` callback arriving while our app is in
 *   the foreground resumed the engine and the next app-foreground signal paused
 *   it again: the tablet log had up to ~540 state flips per hour during picker
 *   interaction (each one a pause/resume of decode + audio). Deriving the state
 *   means a burst of disagreeing callbacks can only ever produce ONE transition.
 * - **The screen being off always wins.** ROMs deliver a "wallpaper is visible
 *   again" callback spuriously while the screen is off (the tablet log had one
 *   13s after the screen-off event). Acting on it would resume video/GIF decoding
 *   and rendering into an invisible surface, which is exactly what power save is
 *   there to prevent. Re-checking the real interactive state when the decision is
 *   applied (not only when it is requested) makes this immune to callback order.
 *
 * A pause is never softened; only the RESUME direction is debounced by the caller
 * (`LiveWallpaperService.refreshPowerSave`), because the system delivers
 * visibility callbacks in bursts during window/app transitions.
 *
 * Kept pure and Android-free so all of the above is unit-tested.
 */
object ScreenPowerPolicy {

    /**
     * The inputs that want the renderer paused, most fundamental first. An empty
     * list means "visible and interactive": full speed.
     *
     * @param wallpaperVisible the wallpaper surface is visible (system callback,
     *   or the engine's own "back on the desktop" optimistic read).
     * @param appInForeground our own UI is covering the wallpaper: the audio must
     *   be muted and decoding parked while the user is in the app.
     * @param screenInteractive the REAL screen state at the moment the decision
     *   is applied (`PowerManager.isInteractive()`).
     */
    fun pauseReasons(
        wallpaperVisible: Boolean,
        appInForeground: Boolean,
        screenInteractive: Boolean
    ): List<String> = buildList {
        if (!screenInteractive) add("screen-off")
        if (!wallpaperVisible) add("visibility")
        if (appInForeground) add("app-foreground")
    }

    /**
     * True when the renderer must be in power save. Callers log
     * [pauseReasons] so a log line shows WHY (including two inputs that disagree).
     */
    fun shouldPause(
        wallpaperVisible: Boolean,
        appInForeground: Boolean,
        screenInteractive: Boolean
    ): Boolean = pauseReasons(wallpaperVisible, appInForeground, screenInteractive).isNotEmpty()

    /**
     * True when this pause is a *visibility blip* and may be delayed a moment
     * before it is applied.
     *
     * The floating switch button is an `APPLICATION_OVERLAY` window: every
     * add/remove of it (and any interaction that changes the window stack) can
     * make the ROM report the wallpaper as covered for a few hundred ms and then
     * visible again. The phone log shows that oscillation with an exact trace
     * (~250-400ms covers, repeating every ~2s while the desktop is used):
     *
     * ```
     * 11:07:04.504 Wallpaper covered: pausing decode/audio
     * 11:07:04.504 Power save ON (visibility)
     * 11:07:04.907 Floating button hidden      <- removeView
     * 11:07:05.259 Floating button shown       <- addView, 352ms later
     * 11:07:05.482 Power save OFF (visibility)
     * ```
     *
     * Each of those costs a decoder pause/resume plus an audio re-anchor for a
     * video wallpaper; 53 such blips were counted in 11h of real use. Delaying
     * ONLY this kind of pause by the caller's coalescing window absorbs a blip
     * completely: the inputs turn visible again before the pause fires, so nothing
     * happens at all.
     *
     * Screen-off and our own UI in front are NOT blips: they must stop the decode
     * and mute the audio at once (the "声音没立刻关" report), so they stay
     * immediate.
     */
    fun isVisibilityBlipOnly(reasons: List<String>): Boolean = reasons == listOf("visibility")
}
