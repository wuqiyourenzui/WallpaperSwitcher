package com.wallpaperswitcher.wallpaper

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsDao
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.ShuffleShown
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.WallpaperImageDao
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.engine.MediaPick
import com.wallpaperswitcher.engine.MediaScanner
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.SwitchPicking
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「下一张用哪张」的取图与 shuffle 牌堆，从 `LiveWallpaperService` 拆分出来。
 *
 * 换壁纸路径（`executeSwitch`）和预取路径（`maybePrefetchNext`）用的是同一套
 * 判定：切换模式、游标、收藏权重、最近不重复，以及 SHUFFLE 的"已出过"牌堆。
 * 以前这两条路各自内联一份，这里收拢成一处；牌堆的落盘（每张一行
 * `shuffle_shown`）也一并在 [flush] 里。
 *
 * 逻辑逐字搬移，只把 app context / 数据库 / 进程级 ioScope 改为构造参数。
 */
internal class MediaPicker(
    private val context: Context,
    private val db: AppDatabase,
    private val ioScope: CoroutineScope,
) {

    private val tag = "MediaPicker"
    private val slot = WallpaperTarget.SLOT_HOME

    /**
     * Serialises the SHUFFLE deck's read-modify-write.
     *
     * The deck is touched from two coroutines that DO run at the same time: the
     * switch path (executeSwitch -> pickNextImage -> noteShuffleShown) and the
     * prefetch path (maybePrefetchNext -> pickNextImage). Both are "check the
     * deck, then act on it", so without this lock a plain set-to-set race could
     * lose an id - `shouldResetShuffleDeck` observed a full deck and ran
     * `clear()` right after the other coroutine had recorded a shown id, and the
     * media reappeared inside the same pass.
     */
    private val deckLock = Mutex()

    /** SHUFFLE 这一轮已经出过的 id（屏幕级牌堆）。 */
    private val shuffleShownIds = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var shuffleAllCount = 0

    /** 只等在 [flush] 里落盘的 id（见那里的注释）。 */
    private val pendingShuffleIds = ConcurrentHashMap.newKeySet<Long>()

    // Enabled-media count per screen, cached for SLOT_COUNT_CACHE_MS. One
    // entry per slot; the cache only has to survive a burst of switches.
    private val slotCountCacheAt = LongArray(2)
    private val slotCountCacheValue = IntArray(2)

    /**
     * Enabled-media count for [slot], cached for [SLOT_COUNT_CACHE_MS].
     *
     * The count feeds the SHUFFLE deck size and the SEQUENTIAL wraparound; it is
     * queried once per switch, so a short TTL removes the duplicate COUNT(*)
     * without ever serving a stale size for long.
     */
    suspend fun enabledCountCached(imageDao: WallpaperImageDao, slot: String): Int {
        val index = if (slot == WallpaperTarget.SLOT_HOME) 0 else 1
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - slotCountCacheAt[index] <= SLOT_COUNT_CACHE_MS) {
            return slotCountCacheValue[index]
        }
        val count = imageDao.countByEnabledGroups(slot)
        slotCountCacheValue[index] = count
        slotCountCacheAt[index] = now
        return count
    }

    /**
     * The media the next switch of [switchMode] would show on the home screen,
     * or null when the deck is empty. [forPrefetch] refuses media the prefetch
     * cache cannot hold (video/GIF), so a prefetch can never claim to be ready
     * with something the switch has to decode anyway.
     */
    suspend fun pickNextImage(
        switchMode: SwitchMode, imageDao: WallpaperImageDao, lastId: Long, dao: SettingsDao,
        forPrefetch: Boolean = false, enabledCount: Int = -1, pickSeq: Long = 0L,
        favoriteWeight: Int = 1, recentIds: Collection<Long> = emptyList(),
    ): WallpaperImage? {
        return when (switchMode) {
            SwitchMode.RANDOM -> {
                // 随机：在所有启用分组（时间规则允许的）的媒体池里抽。
                val groups = MediaPick.eligibleGroups(db, slot)
                val rows = MediaPick.poolFor(
                    imageDao, db.groupPickDao(), slot, groups,
                    favoriteWeight, knownCount = enabledCount,
                )
                MediaPick.randomFromPool(imageDao, rows, lastId, pickSeq, recentIds)
            }
            SwitchMode.SEQUENTIAL -> {
                // 顺序切换：从当前媒体开始按分组逐个推进（本组走完 →
                // 下一个分组），见 [MediaPick.sequentialAcrossGroups]。
                val groups = MediaPick.eligibleGroups(db, slot)
                val img = MediaPick.sequentialAcrossGroups(
                    imageDao, db.groupPickDao(), slot, groups, lastId
                )
                // Prefetch must not pick media it cannot cache
                // (video/GIF): the real switch still has to display
                // them, so they stay at the current position.
                if (forPrefetch && img != null &&
                    MediaTypes.isMotion(img.mediaType)
                ) null else img
            }
            SwitchMode.SHUFFLE -> shufflePick(
                imageDao, dao, lastId, forPrefetch, enabledCount, pickSeq, favoriteWeight
            )
        }
    }

    private suspend fun shufflePick(
        imageDao: WallpaperImageDao,
        dao: SettingsDao,
        lastId: Long,
        forPrefetch: Boolean,
        enabledCount: Int,
        pickSeq: Long,
        favoriteWeight: Int,
    ): WallpaperImage? = deckLock.withLock {
        // 洗牌：牌堆就是所有启用分组（时间规则允许的）的媒体池 —— 一轮里
        // 每个分组、每张媒体都会出一次，而不是"一个分组抽完才轮到下一个"。
        val groups = MediaPick.eligibleGroups(db, slot)
        val poolRows = MediaPick.poolFor(
            imageDao, db.groupPickDao(), slot, groups,
            favoriteWeight, knownCount = enabledCount,
        )
        val totalCount = poolRows.size
        if (totalCount == 0) return null
        if (shuffleShownIds.isEmpty() && shuffleAllCount == 0) {
            // Reload the deck the previous session left behind
            // (see ShuffleShown): one indexed scan of small rows
            // instead of parsing a comma-separated id string.
            val savedIds = db.shuffleDao().getShownIds(slot)
            val savedCount = dao.getLong(SettingsKeys.SHUFFLE_ALL_COUNT, 0L).toInt()
            if (savedIds.isNotEmpty()) {
                shuffleShownIds.addAll(savedIds)
            }
            shuffleAllCount = savedCount
        }
        // Only a FINISHED pass starts a new one. Changing the
        // enabled set (import, toggle) keeps this pass going: the
        // next pick filters the enabled ids by the shown ones, so
        // nothing already shown can reappear before the pass is
        // complete.
        if (SwitchPicking.shouldResetShuffleDeck(
                shuffleShownIds.size, totalCount
            )
        ) {
            shuffleShownIds.clear()
            db.shuffleDao().clearSlot(slot)
        }
        // Random UNSEEN pick, filtering the slot's id list in
        // memory (the pre-review implementation, restored on
        // request). The prefetch runs the same code, so the media
        // it decodes ahead may differ from the next switch.
        var candidate = MediaPick.shuffleUnseenFromPool(
            imageDao = imageDao,
            poolRows = poolRows,
            favoriteIds = MediaPick.poolFavorites(slot, favoriteWeight),
            shownIds = shuffleShownIds,
            excludeId = lastId,
            pickSeq = pickSeq,
            favoriteWeight = favoriteWeight,
        )
        if (candidate == null) {
            // Every enabled media has been shown (or the only
            // remaining one is the media already on screen):
            // start a fresh pass (the pre-review fallback, restored
            // on request).
            shuffleShownIds.clear()
            db.shuffleDao().clearSlot(slot)
            candidate = MediaPick.randomFromPool(
                imageDao, poolRows, lastId, pickSeq, emptyList()
            )
        }
        // Diagnostics: a deck must be played through before an
        // item may repeat, so the deck size and the pick are
        // logged (a repeat with saved<N is a bug).
        AppLog.d(
            tag,
            "Shuffle pick: deck=${shuffleShownIds.size}/$totalCount " +
                "(saved=$shuffleAllCount) -> id=${candidate?.id}"
        )
        // Prefetch of an uncacheable media (video/GIF) returns
        // null so the caller aborts without caching anything.
        if (forPrefetch && candidate?.mediaType != MediaTypes.IMAGE) null else candidate
    }

    /**
     * Record a media the SHUFFLE pass actually applied (never at pick/prefetch
     * time: a prefetched media whose cache was invalidated, or whose apply
     * failed, must stay available) and persist the progress right away - MIUI
     * kills and recreates the wallpaper engine often, and a stale/missing
     * "already shown" set made the rebuilt deck deal images twice.
     *
     * Only the NEWLY shown id is written (one row in `shuffle_shown`), which is
     * cheap enough to do on every switch.
     */
    suspend fun noteShuffleShown(imageDao: WallpaperImageDao, mediaId: Long) {
        // Same lock as the pick: recording a shown id must not interleave with a
        // pass reset (see [deckLock]).
        deckLock.withLock {
            shuffleShownIds.add(mediaId)
            shuffleAllCount = enabledCountCached(imageDao, slot)
            pendingShuffleIds.add(mediaId)
        }
        flush()
    }

    /**
     * Persist the shuffled-so-far ids (and the pass size). Never blocks the
     * caller: the write is dispatched to the process-lifetime [ioScope], and
     * shuffle state is only a hint - if the process dies before the write
     * lands, the deck simply resets.
     *
     * Only statics and locals are referenced inside the coroutine:
     * [ioScope] is process-lifetime, so touching engine state here would keep
     * it alive until the write finished.
     */
    fun flush() {
        val ids = pendingShuffleIds.toList()
        if (ids.isEmpty()) return
        pendingShuffleIds.removeAll(ids)
        // Since schema v6 the deck is one row per shown media (see
        // ShuffleShown): the write is a tiny INSERT, and the insert is
        // idempotent (IGNORE), so the old "serialize the writes and stamp them
        // so an older snapshot cannot land last" machinery is gone with the
        // snapshot itself.
        val shuffleDao = db.shuffleDao()
        val allCount = shuffleAllCount.toLong()
        val settingsDao = db.settingsDao()
        val shownSlot = slot
        ioScope.launch {
            var attempt = 0
            while (true) {
                try {
                    shuffleDao.insertShown(
                        ids.map {
                            ShuffleShown(slot = shownSlot, groupId = 0L, mediaId = it)
                        }
                    )
                    settingsDao.setLong(SettingsKeys.SHUFFLE_ALL_COUNT, allCount)
                    return@launch
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // Bounded retry: the old code set shuffleDirty = true so
                    // the NEXT flush retried, which could never work once the
                    // engine owning the flag was gone (and pinned it here).
                    attempt++
                    if (attempt >= SHUFFLE_WRITE_ATTEMPTS) {
                        AppLog.d(tag, "Shuffle state write failed: ${e.message}")
                        return@launch
                    }
                    delay(300L * attempt)
                }
            }
        }
    }

    private companion object {
        /**
         * TTL of the per-slot enabled-media count (see [enabledCountCached]).
         * The deck is rebuilt from the DB on every switch anyway, so a 3s-old
         * count is only ever used to size the deck.
         */
        private const val SLOT_COUNT_CACHE_MS = 3_000L

        /** Bounded retry for the shuffle-state write (see [flush]). */
        private const val SHUFFLE_WRITE_ATTEMPTS = 3
    }
}
