package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.WallpaperImageDao

/**
 * Media selection shared by the two switch paths (the live wallpaper engine and
 * the static applier), so RANDOM behaves exactly the same on both.
 *
 * Kept separate from [SwitchPicking] because it talks to the DAO; the pure
 * helpers stay Android-free and unit-testable.
 */
internal object MediaPick {

    /**
     * RANDOM pick for [slot], excluding the media that is currently shown.
     *
     * Uses COUNT + a random OFFSET instead of ORDER BY RANDOM(): the latter
     * sorts the whole table on every switch, which is slow and power-hungry on
     * large libraries. The ORDER BY RANDOM() variants stay as fallbacks for the
     * case where the OFFSET lands on a deleted row gap.
     *
     * @param knownCount size of the slot's enabled set when the caller already
     *   queried it, or a negative value to query it here.
     */
    suspend fun random(
        imageDao: WallpaperImageDao,
        slot: String,
        lastId: Long,
        knownCount: Int = -1
    ): WallpaperImage? {
        val count = if (knownCount >= 0) knownCount else imageDao.countByEnabledGroups(slot)
        if (count == 0) return null
        if (count == 1) return imageDao.getRandomImageFromEnabledGroups(slot)
        val offset = SwitchPicking.randomOffset(count)
        return imageDao.getRandomImageFromEnabledGroupsExcludingAt(slot, lastId, offset)
            ?: imageDao.getRandomImageFromEnabledGroupsExcluding(slot, lastId)
            ?: imageDao.getRandomImageFromEnabledGroups(slot)
    }

    /**
     * SHUFFLE pick for [slot]: a random media that has NOT been shown yet in the
     * current pass. Shared by the live engine and the static applier so both play
     * a pass through completely.
     *
     * Restored to the pre-review implementation on request: the slot's whole
     * enabled id list is fetched on every pick and the unseen set is filtered here
     * in memory (see [SwitchPicking.pickUnseen]). The in-memory filter - instead
     * of an `id NOT IN (...)` list - is what keeps a group with more media than
     * SQLite allows bound variables (999 on older builds) from failing the switch.
     *
     * Kept from the later fix: ids of media that are no longer enabled are pruned
     * from [shownIds] first. Leftovers from an earlier set change used to count
     * towards "already shown", which made the pass look finished early and brought
     * already-shown media back before the pass was really over.
     *
     * @param shownIds ids already displayed in the current pass (a Set keeps the
     *   lookup O(1)). Pruned in place to the currently enabled ids.
     * @return the picked media, or null when every enabled media has been shown
     *   (the caller starts a new pass), or nothing targets this screen.
     */
    suspend fun shuffleUnseen(
        imageDao: WallpaperImageDao,
        slot: String,
        shownIds: MutableCollection<Long>,
        excludeId: Long
    ): WallpaperImage? {
        val enabledIds = imageDao.getEnabledIds(slot)
        shownIds.retainAll(enabledIds.toHashSet())
        val pickId = SwitchPicking.pickUnseen(enabledIds, shownIds, excludeId) ?: return null
        return imageDao.getImageById(pickId)
    }
}
