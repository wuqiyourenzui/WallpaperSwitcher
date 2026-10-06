package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.os.Handler
import com.wallpaperswitcher.data.SettingsKeys
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Launcher-independent floating double-tap button, split out of
 * `LiveWallpaperService` verbatim.
 *
 * Owns the single overlay window ([FloatingSwitchButton]) and the debounced
 * hide. WindowManager calls must run on the main thread, so the settings read
 * happens off-main and the window ops are posted to the main handler. The
 * engine-side state (visibility, app foreground, whether this is the preview
 * engine) arrives through [Host].
 */
internal class FloatingButtonController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val host: Host,
) {

    internal interface Host {
        fun isPreview(): Boolean
        fun engineDestroyed(): Boolean
        fun wallpaperVisible(): Boolean
        fun appInForeground(): Boolean
        /** The engine's `noteFloatingWindowChange` (power-save coalescing). */
        fun onWindowChanged()

        suspend fun buttonEnabled(): Boolean
        suspend fun canDrawOverlays(): Boolean
        suspend fun colorHex(): String
        suspend fun alphaValue(): Long
        suspend fun buttonText(): String
        suspend fun buttonImageUri(): String
    }

    /**
     * Delay before the floating button is actually removed when the wallpaper
     * stops being visible. Visibility flips back within a few tens of
     * milliseconds during app switches / picker transitions, and every flip
     * used to removeView + addView the overlay twice. A settle removes that
     * churn; showing is still immediate.
     */
    private val hideSettleMs = 150L

    private var floatingButton: FloatingSwitchButton? = null
    /** Pending debounced hide of the floating button (see [hideSettleMs]). */
    private var floatingButtonHideRunnable: Runnable? = null
    // Serialize floating-button state updates (DB read is async, so two
    // overlapping calls could apply stale state in the wrong order).
    private val floatingButtonUpdateLock = AtomicBoolean(false)
    // An update that arrived while [floatingButtonUpdateLock] was busy.
    // Without this, a fast "hidden while covered -> visible again" burst
    // (e.g. quick app switch back to the desktop) could drop the SHOW
    // request and leave the button missing until the next event.
    private val floatingButtonUpdatePending = AtomicBoolean(false)

    /**
     * Show/hide the floating double-tap button according to the setting,
     * applying the user's color/opacity appearance. WindowManager calls must
     * run on the main thread, so the DB read happens off-main and the window
     * ops are posted to the main handler.
     */
    fun update() {
        // Only the REAL engine owns the floating button. The picker's preview
        // engine used to create a second overlay window at the same spot
        // (verified with dumpsys: two APPLICATION_OVERLAY windows of ours,
        // Window #12 and #13, both 144x144), which left a stale duplicate
        // behind whenever the preview engine outlived its dismiss - and the
        // live content updates only ever reached the instance held by the real
        // engine's field.
        if (host.isPreview()) return
        if (!floatingButtonUpdateLock.compareAndSet(false, true)) {
            floatingButtonUpdatePending.set(true)
            return
        }
        scope.launch {
            try {
                val enabled = try {
                    host.buttonEnabled()
                } catch (_: Exception) {
                    false
                }
                val canOverlayEarly = try {
                    host.canDrawOverlays()
                } catch (_: Exception) {
                    false
                }
                if (!enabled || !canOverlayEarly) {
                    // The feature is off: drop the window instead of leaving a
                    // hidden overlay attached.
                    mainHandler.post {
                        host.onWindowChanged()
                        floatingButton?.removeOverlay()
                        floatingButton = null
                        finishUpdate()
                    }
                    return@launch
                }

                // Same value the enable check above used (one permission read).
                val canOverlay = canOverlayEarly
                val colorArgb = try {
                    FloatingSwitchButton.parseColor(host.colorHex())
                } catch (_: Exception) {
                    FloatingSwitchButton.DEFAULT_COLOR
                }
                val opacity = try {
                    host.alphaValue()
                        .toInt()
                        .coerceIn(SettingsKeys.FLOATING_BUTTON_ALPHA_MIN, 100)
                } catch (_: Exception) {
                    SettingsKeys.FLOATING_BUTTON_ALPHA_DEFAULT
                }
                val buttonText = try {
                    host.buttonText()
                } catch (_: Exception) {
                    SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT
                }
                val buttonImage = try {
                    host.buttonImageUri()
                } catch (_: Exception) {
                    ""
                }
                mainHandler.post {
                    try {
                        if (host.engineDestroyed()) return@post
                        // The floating button only lives on the desktop:
                        // hide it whenever the wallpaper is covered (any
                        // app open, lock screen, screen off).
                        if (enabled && canOverlay && host.wallpaperVisible() &&
                            !host.appInForeground()
                        ) {
                            // Visible again (or still): a hide that was only
                            // scheduled must be cancelled, so a transient
                            // cover costs no window churn at all.
                            cancelPendingHide()
                            // ONE overlay window per process, attached once
                            // and then only shown/hidden (see
                            // FloatingSwitchButton): re-creating it per engine
                            // left duplicates behind (removeView reported no
                            // error while dumpsys showed three live windows)
                            // and every add/remove made the ROM flip the
                            // wallpaper's visibility.
                            val button = floatingButton
                                ?: FloatingSwitchButton.obtain(context)
                                    .also { floatingButton = it }
                            button.setAppearance(colorArgb, opacity)
                            button.setContent(buttonText, buttonImage)
                            host.onWindowChanged()
                            button.showOnDesktop()
                        } else if (host.appInForeground()) {
                            // Our own app is in front: hide at once instead of
                            // after the settle debounce, otherwise the button
                            // lingers over the app's window transition.
                            cancelPendingHide()
                            host.onWindowChanged()
                            floatingButton?.hideOverlay()
                        } else {
                            scheduleHide()
                        }
                    } finally {
                        finishUpdate()
                    }
                }
            } catch (_: Exception) {
                finishUpdate()
            }
        }
    }

    /**
     * Live appearance + content update (recolors the on-screen button as the
     * user drags the opacity slider / picks a color, swaps label or picture).
     */
    fun applyAppearance(colorArgb: Int, opacity: Int, text: String, imageUri: String) {
        mainHandler.post {
            floatingButton?.setAppearance(colorArgb, opacity)
            floatingButton?.setContent(text, imageUri)
        }
    }

    /** Immediate hide (the app just opened); wins over a pending debounce. */
    fun dismissNow() {
        mainHandler.post {
            cancelPendingHide()
            host.onWindowChanged()
            floatingButton?.hideOverlay()
        }
    }

    /** Engine teardown: leave the singleton window hidden, never attached. */
    fun shutdown() {
        floatingButton?.hideOverlay()
    }

    /**
     * Release the update lock and, if another update was requested while this
     * one was running (hide/show toggling during an app transition), re-run it
     * so the LAST state always wins.
     */
    private fun finishUpdate() {
        floatingButtonUpdateLock.set(false)
        if (floatingButtonUpdatePending.compareAndSet(true, false)) {
            update()
        }
    }

    /**
     * Remove the button after [hideSettleMs], unless the wallpaper became
     * visible again in the meantime.
     */
    private fun scheduleHide() {
        if (floatingButton == null || floatingButtonHideRunnable != null) return
        val runnable = Runnable {
            floatingButtonHideRunnable = null
            // Re-check on the main thread at fire time: "visible again" is
            // the common case this debounce exists for.
            if (host.engineDestroyed() || host.wallpaperVisible()) return@Runnable
            host.onWindowChanged()
            floatingButton?.hideOverlay()
        }
        floatingButtonHideRunnable = runnable
        mainHandler.postDelayed(runnable, hideSettleMs)
    }

    private fun cancelPendingHide() {
        floatingButtonHideRunnable?.let { mainHandler.removeCallbacks(it) }
        floatingButtonHideRunnable = null
    }
}
