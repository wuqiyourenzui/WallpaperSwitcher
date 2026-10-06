package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.GroupPickDao
import com.wallpaperswitcher.data.MediaWeight
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.WallpaperImageDao
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.data.AppDatabase

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
            weightsCache.clear()
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
     * Cached `weightsForSlot` rows for the weighted RANDOM path.
     *
     * It used to re-dump id+weight for the whole slot on every switch (and
     * every prefetch) whenever 收藏优先 / 最近不重复 was on - on the 65k-media
     * library that is a 65k-element query + allocation per pick. The cache is
     * keyed exactly like [enabledIdsFor]: group/media version + enabled count +
     * favoriteWeight. A favourite toggle bumps the version (the ViewModel
     * calls [invalidateEnabledIds]), and a worker import changes the count.
     */
    private class CachedWeights(
        val rows: List<MediaWeight>,
        val enabledCount: Int,
        val favoriteWeight: Int,
        val version: Long
    )

    private val weightsCache = HashMap<String, CachedWeights>(CACHE_SLOTS)

    private suspend fun weightsFor(
        imageDao: WallpaperImageDao,
        slot: String,
        knownCount: Int,
        favoriteWeight: Int,
    ): List<MediaWeight> {
        val count = if (knownCount >= 0) knownCount else imageDao.countByEnabledGroups(slot)
        if (count == 0) return emptyList()
        synchronized(cacheLock) {
            val hit = weightsCache[slot]
            if (hit != null && hit.version == enabledSetVersion &&
                hit.enabledCount == count && hit.favoriteWeight == favoriteWeight
            ) {
                return hit.rows
            }
        }
        val rows = try {
            imageDao.weightsForSlot(slot, favoriteWeight)
        } catch (_: Exception) {
            emptyList()
        }
        synchronized(cacheLock) {
            if (!weightsCache.containsKey(slot) && weightsCache.size >= CACHE_SLOTS) {
                weightsCache.keys.firstOrNull()?.let { weightsCache.remove(it) }
            }
            weightsCache[slot] = CachedWeights(rows, count, favoriteWeight, enabledSetVersion)
        }
        return rows
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
            val rows = weightsFor(imageDao, slot, count, favoriteWeight)
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

    /** 顺序=新的在前 (see [WallpaperGroup.sortOrder]). */
    private const val SORT_NEWEST = "NEWEST"

    /**
     * The groups a screen-wide pick may use right now: enabled, targeting
     * [slot], and allowed by their own 时间规则. Sorted by build order (id),
     * which is also the order 顺序切换 walks them in.
     *
     * 随机 / 洗牌 / 顺序 三种模式都以这张列表为全集：随机和洗牌在
     * **所有启用分组**的媒体池里抽，顺序从当前媒体开始按这个顺序逐组推进。
     */
    suspend fun eligibleGroups(
        db: AppDatabase,
        slot: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<WallpaperGroup> = try {
        db.wallpaperGroupDao().getEnabledGroupsSync()
            .filter {
                WallpaperTarget.fromName(it.target).suitsSlot(slot) &&
                    GroupRules.isActiveAt(it, nowMs)
            }
            .sortedBy { it.id }
    } catch (_: Exception) {
        emptyList()
    }

    private class CachedPool(
        val rows: List<MediaWeight>,
        val favoriteIds: Set<Long>,
        val groupsKey: String,
        val enabledCount: Int,
        val favoriteWeight: Int,
        val version: Long,
    )

    /**
     * Pool of every media the enabled groups may show on [slot] right now:
     * each group contributes exactly the media its own filter allows
     * (仅图片 / 仅视频 / 全部), favourites carry [favoriteWeight].
     *
     * This is the "all enabled groups" universe the three switch modes draw
     * from (see [eligibleGroups]). It replaced the single-table
     * `weightsForSlot` query, which could not honour per-group filters or
     * 时间规则 - a group set to 仅图片 must not leak its videos into the pool.
     * Cached like the old id list: group/media version + enabled count +
     * favourite weight + the eligible-group set are all part of the key.
     */
    private val poolCache = HashMap<String, CachedPool>(CACHE_SLOTS)

    suspend fun poolFor(
        imageDao: WallpaperImageDao,
        pickDao: GroupPickDao,
        slot: String,
        groups: List<WallpaperGroup>,
        favoriteWeight: Int,
        knownCount: Int = -1,
    ): List<MediaWeight> {
        val groupsKey = groups.joinToString(",") { group ->
            "${group.id}:${GroupRules.mediaFilter(group)}"
        }
        val count = if (knownCount >= 0) knownCount else imageDao.countByEnabledGroups(slot)
        synchronized(cacheLock) {
            val hit = poolCache[slot]
            if (hit != null && hit.version == enabledSetVersion &&
                hit.groupsKey == groupsKey && hit.enabledCount == count &&
                hit.favoriteWeight == favoriteWeight
            ) {
                return hit.rows
            }
        }
        val rows = ArrayList<MediaWeight>()
        for (group in groups) {
            try {
                rows += pickDao.weightsInGroup(
                    slot, group.id, GroupRules.mediaFilter(group), favoriteWeight
                )
            } catch (_: Exception) {
                // A broken group must not take the whole pool down.
            }
        }
        val favorites = if (favoriteWeight > 1) {
            rows.filter { it.weight > 1 }.map { it.id }.toHashSet()
        } else {
            emptySet()
        }
        synchronized(cacheLock) {
            if (!poolCache.containsKey(slot) && poolCache.size >= CACHE_SLOTS) {
                poolCache.keys.firstOrNull()?.let { poolCache.remove(it) }
            }
            poolCache[slot] = CachedPool(
                rows, favorites, groupsKey, count, favoriteWeight, enabledSetVersion
            )
        }
        return rows
    }

    /** ★ ids of a pool built by [poolFor] (empty when 收藏优先 is off). */
    fun poolFavorites(slot: String, favoriteWeight: Int): Set<Long> {
        if (favoriteWeight <= 1) return emptySet()
        synchronized(cacheLock) {
            return poolCache[slot]?.favoriteIds ?: emptySet()
        }
    }

    /**
     * RANDOM pick over a pool built by [poolFor]: weighted draw (收藏优先),
     * skipping the media on screen and the 最近 N 张不重复 window.
     */
    suspend fun randomFromPool(
        imageDao: WallpaperImageDao,
        rows: List<MediaWeight>,
        lastId: Long,
        pickSeq: Long = 0L,
        recentIds: Collection<Long> = emptyList(),
    ): WallpaperImage? {
        if (rows.isEmpty()) return null
        if (rows.size == 1) return imageDao.getImageById(rows[0].id)
        val seed = SwitchPicking.pickSeed(lastId, 0, rows.size, pickSeq)
        val picked = weightedPick(rows, setOf(lastId), recentIds.toHashSet(), seed)
            ?: weightedPick(rows, emptySet(), emptySet(), seed)
            ?: return null
        return imageDao.getImageById(picked)
            ?: imageDao.getImageById(rows.first().id)
    }

    /**
     * SHUFFLE pick over a pool built by [poolFor]: a random media not yet shown
     * in the current pass ([shownIds], pruned to the pool first). Mirrors the
     * former slot-wide [shuffleUnseen], which could not honour per-group
     * filters or 时间规则.
     */
    suspend fun shuffleUnseenFromPool(
        imageDao: WallpaperImageDao,
        poolRows: List<MediaWeight>,
        favoriteIds: Set<Long>,
        shownIds: MutableCollection<Long>,
        excludeId: Long,
        pickSeq: Long = 0L,
        favoriteWeight: Int = 1,
    ): WallpaperImage? {
        if (poolRows.isEmpty()) return null
        val poolIds = poolRows.map { it.id }
        shownIds.retainAll(poolIds.toHashSet())
        val seed = SwitchPicking.pickSeed(excludeId, shownIds.size, poolIds.size, pickSeq)
        val pickId = SwitchPicking.pickUnseen(
            poolIds, shownIds, excludeId, seed, favoriteIds, favoriteWeight
        ) ?: return null
        return imageDao.getImageById(pickId)
    }

    /**
     * 顺序切换（屏幕级）：从当前媒体开始，**按分组逐个推进**。
     *
     * 先在当前分组里按该组自己的顺序继续（[WallpaperGroup.sortOrder] =
     * NEWEST 时新的在前，否则 id 升序），本组走完就进入 **下一个分组**
     * （按分组的建立顺序），最后一个分组走完回到第一个分组。每个分组在
     * 一轮里只被访问一次。
     *
     * 以前屏幕级顺序切换是一条「全库 id 升序游标」：当媒体是后来才补进老
     * 分组（id 与分组顺序交错）时，切换会在分组之间来回跳，而不是「一个
     * 分组切换完下一个分组再顺序切换」。随机 / 洗牌不受影响，它们本来就
     * 从所有启用分组里取。
     *
     * [groups] 必须是启用分组按建立顺序（id 升序）排好的列表；调用方负责
     * 按 slot 过滤（[WallpaperTarget.suitsSlot]）。
     */
    suspend fun sequentialAcrossGroups(
        imageDao: WallpaperImageDao,
        pickDao: GroupPickDao,
        slot: String,
        groups: List<WallpaperGroup>,
        lastId: Long,
    ): WallpaperImage? {
        if (groups.isEmpty()) return null
        val current = if (lastId > 0L) {
            try {
                imageDao.getImageById(lastId)
            } catch (_: Exception) {
                null
            }
        } else null
        val currentGroupId = current?.groupId ?: 0L
        // Current group first, then the following ones, wrapping at the end:
        // every group is visited exactly once before this pass starts over.
        val startIndex =
            groups.indexOfFirst { it.id == currentGroupId }.coerceAtLeast(0)
        val ordered = groups.subList(startIndex, groups.size) +
            groups.subList(0, startIndex)
        for (group in ordered) {
            val filter = GroupRules.mediaFilter(group)
            val newestFirst = group.sortOrder == SORT_NEWEST
            if (group.id == currentGroupId && current != null) {
                val next = if (newestFirst) {
                    pickDao.getSequentialInGroupBefore(slot, group.id, lastId, filter)
                } else {
                    pickDao.getSequentialInGroupAfter(slot, group.id, lastId, filter)
                }
                if (next != null) return next
                // This group is finished: continue with the next one.
            } else {
                val first = if (newestFirst) {
                    pickDao.getNewestInGroup(slot, group.id, filter)
                } else {
                    pickDao.getFirstInGroup(slot, group.id, filter)
                }
                if (first != null) return first
            }
        }
        return null
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
