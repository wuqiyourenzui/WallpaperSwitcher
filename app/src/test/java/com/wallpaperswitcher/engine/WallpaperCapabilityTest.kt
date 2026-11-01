package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The static write is now refused *before* the decode when the device (or a
 * device policy) says no wallpaper may be set - see
 * `WallpaperApplier.staticWriteBlocked`.
 *
 * The mapping is kept pure so it can be pinned here: reading the two capability
 * flags the wrong way round would silently stop every timed switch on a normal
 * device, with no error to look at (the write would simply never be attempted).
 */
class WallpaperCapabilityTest {

    @Test
    fun supportedAndAllowedLetsTheWriteThrough() {
        assertNull(
            "a normal device must attempt the write",
            WallpaperApplier.wallpaperRefusalName(supported = true, allowed = true)
        )
    }

    @Test
    fun noSystemWallpaperIsReported() {
        assertEquals(
            "isWallpaperSupported=false (no system wallpaper on this device)",
            WallpaperApplier.wallpaperRefusalName(supported = false, allowed = true)
        )
    }

    @Test
    fun policyRestrictionIsReported() {
        assertEquals(
            "isSetWallpaperAllowed=false (device policy restricts wallpaper)",
            WallpaperApplier.wallpaperRefusalName(supported = true, allowed = false)
        )
    }

    @Test
    fun unsupportedWinsOverDisallowed() {
        // Both false must not crash or report the policy reason: a device
        // without wallpapers at all is the more fundamental answer.
        assertEquals(
            "isWallpaperSupported=false (no system wallpaper on this device)",
            WallpaperApplier.wallpaperRefusalName(supported = false, allowed = false)
        )
    }
}
