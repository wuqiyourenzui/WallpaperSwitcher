package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.GroupPickDao
import com.wallpaperswitcher.data.MediaWeight
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
     * Cache of `slot -> enabled id list`, used by the SHUFFLE deck.
     *
     * The deck filters the slot's whole id list in memory on every pick (see
     * [shuffleUnseen]), which on an 18k-media library is a full id scan plus an
     * 18k-element allocation **per switch** - pure waste while the set cannot have
     * changed. A cached list is only trusted while BOTH keys still match:
     *
     *  - the media store [MediaScanner.currentGeneration] (a row added/removed by
     *    the scanner bumps it; `Long.MIN_VALUE` on API < 30 means "no signal", so
     *    only the version key guards the entry there);
     *  - the slot's enabled COUNT, which both callers already query before
     *    picking (so it costs nothing) and which catches a set that changed
     *    without going through [invalidateEnabledIds] - an auto-scan import, for
     *    instance, which runs in a worker;
     *  - [enabledSetVersion], which the app bumps for everything the generation
     *    cannot see - enabling/disabling a group, a 应用位置 change, media being
     *    deleted, a group being removed (see [invalidateEnabledIds]).
     *
     * Worst case for a missed invalidation is one extra pick: the deck prunes ids
     * whose row lookup fails and the caller falls back, so a stale list can never
     * pick something outside the enabled set for long.
     */
    private const val CACHE_SLOTS = 2
    private val cacheLock = Any()
    private val cache = HashMap<String, CachedIds>(CACHE_SLOTS)
    private var enabledSetVersion = 0L

    private class CachedIds(
        val ids: List<Long>,
        /** Favourite ids of the same set: weighted picks need to know who is ★. */
        val favorites: Set<Long>,
        val generation: Long?,
        val enabledCount: Int,
        val version: Long
    )

    /** favouriteWeight used to build a cached entry (part of its key). */
    private var cachedFavoriteWeight = 0

    /** Invalidate every cached id list: a group / media / target change happened. */
    fun invalidateEnabledIds() {
        synchronized(cacheLock) {
            enabledSetVersion++
            cachedFavoriteWeight = 0
            cache.clear()
        }
    }

    private suspend fun enabledIdsFor(
        imageDao: WallpaperImageDao,
        slot: String,
        generation: Long?,
        knownCount: Int,
        favoriteWeight: Int = 1,
    ): List<Long> {
        val count = if (knownCount >= 0) knownCount else imageDao.countByEnabledGroups(slot)
        synchronized(cacheLock) {
            val hit = cache[slot]
            if (hit != null && hit.version == enabledSetVersion &&
                hit.generation == generation && hit.enabledCount == count &&
                cachedFavoriteWeight == favoriteWeight
            ) {
                return hit.ids
            }
        }
        val weights = if (count == 0) emptyList() else {
            imageDao.weightsForSlot(slot, favoriteWeight)
        }
        val ids = weights.map { it.id }
        val favorites = if (favoriteWeight > 1) {
            weights.filter { it.weight > 1 }.map { it.id }.toHashSet()
        } else {
            emptySet()
        }
        synchronized(cacheLock) {
            // Two slots at most (home + lock): drop an arbitrary older entry
            // instead of letting the map grow if a third key ever appears.
            if (!cache.containsKey(slot) && cache.size >= CACHE_SLOTS) {
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            cache[slot] = CachedIds(ids, favorites, generation, count, enabledSetVersion)
            cachedFavoriteWeight = favoriteWeight
        }
        return ids
    }

    /** The ★ set of a cached slot (empty when the boost is off). */
    private suspend fun favoritesFor(
        imageDao: WallpaperImageDao,
        slot: String,
        generation: Long?,
        knownCount: Int,
        favoriteWeight: Int,
    ): Set<Long> {
        enabledIdsFor(imageDao, slot, generation, knownCount, favoriteWeight)
        synchronized(cacheLock) {
            return cache[slot]?.favorites ?: emptySet()
        }
    }

    /**
     * Weighted random pick over [rows], skipping [exclude] (the cursor) and
     * [recent] (最近 N 张不重复). Deterministic for a given seed, so 下一张预览
     * still names the media the switch will show.
     *
     * @return the picked id, or null when nothing is left to draw.
     */
    fun weightedPick(
        rows: List<MediaWeight>,
        exclude: Set<Long>,
        recent: Set<Long>,
        seed: Long,
    ): Long? {
        val candidates = rows.filter { it.id !in exclude && it.id !in recent && it.weight > 0 }
        if (candidates.isEmpty()) return null
        var total = 0L
        for (row in candidates) total += row.weight
        if (total <= 0L) return null
        var draw = kotlin.random.Random(seed).nextLong(total)
        for (row in candidates) {
            if (draw < row.weight) return row.id
            draw -= row.weight
        }
        return candidates.last().id
    }

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
        knownCount: Int = -1,
        pickSeq: Long = 0L,
        favoriteWeight: Int = 1,
        recentIds: Collection<Long> = emptyList(),
    ): WallpaperImage? {
        val count = if (knownCount >= 0) knownCount else imageDao.countByEnabledGroups(slot)
        if (count == 0) return null
        if (count == 1) return imageDao.getRandomImageFromEnabledGroups(slot)
        // 收藏优先 / 最近不重复: draw from the weighted list. Only taken when the
        // user turned one of them on, so the default path keeps its fast
        // COUNT + OFFSET query untouched.
        if (favoriteWeight > 1 || recentIds.isNotEmpty()) {
            val rows = try {
                imageDao.weightsForSlot(slot, favoriteWeight)
            } catch (_: Exception) {
                emptyList()
            }
            val picked = weightedPick(
                rows, setOf(lastId), recentIds.toHashSet(),
                SwitchPicking.pickSeed(lastId, 0, count, pickSeq)
            )
            imageDao.getImageById(picked ?: -1L)?.let { return it }
        }
        // Seeded from the cursor: 下一张预览 must name the very media this
        // pick will return, and clicking it twice must not re-roll (see
        // SwitchPicking.stableIndex). After this pick the cursor moves on, so
        // the NEXT switch draws a different offset.
        val offset = SwitchPicking.randomOffset(
            count,
            SwitchPicking.pickSeed(lastId, deckSize = 0, universeSize = count, seq = pickSeq)
        )
        return imageDao.getRandomImageFromEnabledGroupsExcludingAt(slot, lastId, offset)
            ?: imageDao.getRandomImageFromEnabledGroupsExcluding(slot, lastId)
            ?: imageDao.getRandomImageFromEnabledGroups(slot)
    }

    /**
     * SHUFFLE pick for [slot]: a random media that has NOT been shown yet in the
     * current pass. Shared by the live engine and the static applier so both play
     * a pass through completely.
     *
     * The slot's whole enabled id list is filtered here in memory (see
     * [SwitchPicking.pickUnseen]) instead of an `id NOT IN (...)` list, which keeps
     * a group with more media than SQLite allows bound variables (999 on older
     * builds) from failing the switch. The list itself now comes from a cache
     * ([enabledIdsFor]) that is invalidated by the media-store generation and by
     * every group/target/media change.
     *
     * Ids of media that are no longer enabled are pruned from [shownIds] first.
     * Leftovers from an earlier set change used to count towards "already shown",
     * which made the pass look finished early and brought already-shown media back
     * before the pass was really over.
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
        excludeId: Long,
        /**
         * Media-store generation to key the cache on
         * ([MediaScanner.currentGeneration]), or null to key it on
         * [invalidateEnabledIds] alone (unit tests, and platforms that cannot
         * report a generation at all).
         */
        generation: Long? = null,
        /**
         * The slot's enabled count when the caller already queried it (both do,
         * for the pass bookkeeping), or a negative value to query it here. It is
         * part of the cache key so a set that changed without an explicit
         * invalidation - an auto-scan import in a worker, for example - still
         * gets a fresh list.
         */
        knownCount: Int = -1,
        /**
         * [SettingsKeys.PICK_SEQ] of the caller: the applied-switch counter that
         * both the preview and the switch read from the database.
         */
        pickSeq: Long = 0L
        ,
        /** 收藏优先: non-favourites weigh 1, favourites weigh this. */
        favoriteWeight: Int = 1,
    ): WallpaperImage? {
        val enabledIds = enabledIdsFor(imageDao, slot, generation, knownCount, favoriteWeight)
        shownIds.retainAll(enabledIds.toHashSet())
        val favorites = if (favoriteWeight > 1) {
            favoritesFor(imageDao, slot, generation, knownCount, favoriteWeight)
        } else {
            emptySet()
        }
        // Deterministic deck pick: the preview reads the same persisted deck and
        // computes the same id, while a switch that really dealt a card grows
        // the deck and therefore changes the seed.
        val seed = SwitchPicking.pickSeed(
            excludeId, shownIds.size, enabledIds.size, pickSeq
        )
        val pickId = SwitchPicking.pickUnseen(
            enabledIds, shownIds, excludeId, seed, favorites, favoriteWeight
        )
            ?: return null
        return imageDao.getImageById(pickId)
    }

    /**
     * RANDOM pick **within one group** (the per-group switching path).
     *
     * Same COUNT + random-OFFSET strategy as [random], but scoped to
     * [groupId]: the caller wants the next media of that group, not of the
     * whole screen. No id-list cache: this path only runs for groups that opted
     * into their own rhythm, and the query is a single indexed count.
     */
    suspend fun randomInGroup(
        pickDao: GroupPickDao,
        slot: String,
        groupId: Long,
        lastId: Long,
        pickSeq: Long = 0L,
        /** [WallpaperGroup.filterMode] of the group ("" = 全部). */
        filter: String = "",
        favoriteWeight: Int = 1,
        recentIds: Collection<Long> = emptyList(),
    ): WallpaperImage? {
        val count = pickDao.countInGroup(slot, groupId, filter)
        if (count == 0) return null
        if (count == 1) return pickDao.getRandomInGroup(slot, groupId, filter)
        if (favoriteWeight > 1 || recentIds.isNotEmpty()) {
            val rows = try {
                pickDao.weightsInGroup(slot, groupId, filter, favoriteWeight)
            } catch (_: Exception) {
                emptyList()
            }
            val picked = weightedPick(
                rows, setOf(lastId), recentIds.toHashSet(),
                SwitchPicking.pickSeed(lastId, 0, count, pickSeq)
            )
            if (picked != null) pickDao.getInGroupById(slot, groupId, picked, filter)?.let {
                return it
            }
        }
        val offset = SwitchPicking.randomOffset(
            count,
            SwitchPicking.pickSeed(lastId, deckSize = 0, universeSize = count, seq = pickSeq)
        )
        return pickDao.getRandomInGroupAt(slot, groupId, lastId, offset, filter)
            ?: pickDao.getRandomInGroupExcluding(slot, groupId, lastId, filter)
            ?: pickDao.getRandomInGroup(slot, groupId, filter)
    }

    /**
     * SHUFFLE pick **within one group**: the group's own id list is filtered by
     * the (slot, groupId) deck in memory (see [SwitchPicking.pickUnseen]), so
     * two groups can never consume each other's cards. Ids the group no longer
     * holds are pruned from [shownIds].
     */
    suspend fun shuffleUnseenInGroup(
        pickDao: GroupPickDao,
        imageDao: WallpaperImageDao,
        slot: String,
        groupId: Long,
        shownIds: MutableCollection<Long>,
        excludeId: Long,
        pickSeq: Long = 0L,
        /** [WallpaperGroup.filterMode] of the group ("" = 全部). */
        filter: String = "",
        favoriteWeight: Int = 1,
    ): WallpaperImage? {
        val enabledIds = pickDao.getEnabledIdsInGroup(slot, groupId, filter)
        shownIds.retainAll(enabledIds.toHashSet())
        val favorites = if (favoriteWeight > 1) {
            try {
                pickDao.weightsInGroup(slot, groupId, filter, favoriteWeight)
                    .filter { it.weight > 1 }.map { it.id }.toHashSet()
            } catch (_: Exception) {
                emptySet()
            }
        } else {
            emptySet()
        }
        val seed = SwitchPicking.pickSeed(
            excludeId, shownIds.size, enabledIds.size, pickSeq
        )
        val pickId = SwitchPicking.pickUnseen(
            enabledIds, shownIds, excludeId, seed, favorites, favoriteWeight
        )
            ?: return null
        return imageDao.getImageById(pickId)
    }
}
