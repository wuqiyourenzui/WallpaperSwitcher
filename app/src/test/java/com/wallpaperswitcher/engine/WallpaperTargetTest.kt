package com.wallpaperswitcher.engine

import android.app.WallpaperManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dual-screen (Paperize-style) mapping tests: a group's stored 应用位置 value
 * decides which wallpaper slot(s) its media may be written to.
 */
class WallpaperTargetTest {

    @Test
    fun homeTargetsSystemSlotOnly() {
        val target = WallpaperTarget.HOME
        assertEquals(WallpaperManager.FLAG_SYSTEM, target.flags)
        assertTrue(target.includesHome)
        assertFalse(target.includesLock)
    }

    @Test
    fun lockTargetsLockSlotOnly() {
        val target = WallpaperTarget.LOCK
        assertEquals(WallpaperManager.FLAG_LOCK, target.flags)
        assertFalse(target.includesHome)
        assertTrue(target.includesLock)
    }

    @Test
    fun bothTargetsBothSlots() {
        val target = WallpaperTarget.BOTH
        assertEquals(
            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            target.flags
        )
        assertTrue(target.includesHome)
        assertTrue(target.includesLock)
    }

    @Test
    fun fromNameRoundTripsEveryValue() {
        for (target in WallpaperTarget.entries) {
            assertEquals(target, WallpaperTarget.fromName(target.nameValue))
        }
    }

    @Test
    fun unknownOrMissingNameFallsBackToBoth() {
        // Legacy rows (migration default) and garbage must never crash a
        // switch: they behave like "show everywhere".
        assertEquals(WallpaperTarget.BOTH, WallpaperTarget.fromName(null))
        assertEquals(WallpaperTarget.BOTH, WallpaperTarget.fromName(""))
        assertEquals(WallpaperTarget.BOTH, WallpaperTarget.fromName("SOMETHING_ELSE"))
        // Enum names are not the stored values.
        assertEquals(WallpaperTarget.BOTH, WallpaperTarget.fromName("home"))
    }

    @Test
    fun storedValuesAreUnique() {
        val names = WallpaperTarget.entries.map { it.nameValue }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun slotConstantsMatchTheTargetValues() {
        assertEquals(WallpaperTarget.HOME.nameValue, WallpaperTarget.SLOT_HOME)
        assertEquals(WallpaperTarget.LOCK.nameValue, WallpaperTarget.SLOT_LOCK)
    }

    // --- The two screens must never leak into each other ---
    // (regression: a lock-only group's image used to be rendered by the home
    // live-wallpaper engine and shown on the desktop)

    @Test
    fun homeOnlyGroupIsNeverSuitableForTheLockSlot() {
        assertTrue(WallpaperTarget.HOME.suitsSlot(WallpaperTarget.SLOT_HOME))
        assertFalse(WallpaperTarget.HOME.suitsSlot(WallpaperTarget.SLOT_LOCK))
    }

    @Test
    fun lockOnlyGroupIsNeverSuitableForTheHomeSlot() {
        assertFalse(WallpaperTarget.LOCK.suitsSlot(WallpaperTarget.SLOT_HOME))
        assertTrue(WallpaperTarget.LOCK.suitsSlot(WallpaperTarget.SLOT_LOCK))
    }

    @Test
    fun bothGroupSuitsEverySlot() {
        assertTrue(WallpaperTarget.BOTH.suitsSlot(WallpaperTarget.SLOT_HOME))
        assertTrue(WallpaperTarget.BOTH.suitsSlot(WallpaperTarget.SLOT_LOCK))
    }
}
