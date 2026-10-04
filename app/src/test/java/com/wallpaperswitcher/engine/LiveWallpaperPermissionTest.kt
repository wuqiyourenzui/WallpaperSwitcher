package com.wallpaperswitcher.engine

import android.app.AppOpsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the MIUI/HyperOS 「动态壁纸服务」 gate.
 *
 * The system picker (`LiveWallpaperChange`) requires the wallpaper app's
 * app-op 10045 to be exactly `MODE_ALLOWED` and finishes itself the moment it
 * is not - including `MODE_DEFAULT`, which is what a freshly installed app
 * reports. The decision must stay conservative: anything unreadable (other
 * ROMs, hidden op) is "allowed" so no working device is ever blocked by a
 * guess.
 */
class LiveWallpaperPermissionTest {

    @Test
    fun allowedOpKeepsThePicker() {
        assertFalse(
            LiveWallpaperPermission.isBlocked("Xiaomi", AppOpsManager.MODE_ALLOWED)
        )
    }

    @Test
    fun ignoredOpBlocksThePicker() {
        assertTrue(
            LiveWallpaperPermission.isBlocked("Xiaomi", AppOpsManager.MODE_IGNORED)
        )
    }

    @Test
    fun defaultOpBlocksThePickerToo() {
        // LiveWallpaperChange compares against MODE_ALLOWED, so MODE_DEFAULT -
        // the state a fresh install starts in - is enough to hide the screen.
        assertTrue(
            LiveWallpaperPermission.isBlocked("Xiaomi", AppOpsManager.MODE_DEFAULT)
        )
    }

    @Test
    fun otherManufacturersAreNeverBlocked() {
        assertFalse(LiveWallpaperPermission.isBlocked("samsung", AppOpsManager.MODE_IGNORED))
        assertFalse(LiveWallpaperPermission.isBlocked("Google", AppOpsManager.MODE_ERRORED))
        assertFalse(LiveWallpaperPermission.isBlocked(null, AppOpsManager.MODE_IGNORED))
    }

    @Test
    fun unreadableOpNeverBlocks() {
        assertFalse(LiveWallpaperPermission.isBlocked("Xiaomi", null))
    }
}
