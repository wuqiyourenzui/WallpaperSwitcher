package com.wallpaperswitcher.wallpaper

import android.content.Context
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.engine.GroupPick
import com.wallpaperswitcher.engine.GroupRules
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.PickOptions
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「预解码下一张」的决策与解码，从 `LiveWallpaperService` 拆分出来（第 23 轮解构）。
 *
 * 只做一件事：判断这次切换之后值不值得预取（可见 + 亮屏 + 非定时 + 连点），
 * 然后按"下一次切换会选中的那张"（同分组、同模式、同游标种子）解码并放进
 * [PrefetchCache]。缓存本身仍由引擎共享持有 —— 切换路径要 `take()`/`clear()`
 * 它，这里只负责填。
 */
internal class PrefetchController(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val tag: String,
    private val cache: PrefetchCache,
    private val picker: MediaPicker,
    private val loader: MediaBitmapLoader,
    /** 手动触发（双击 / 悬浮按钮 / 立即切换）判定，见引擎的同名函数。 */
    private val isUserTapSource: (String) -> Boolean,
    private val host: Host,
) {

    internal interface Host {
        fun isVisible(): Boolean
        fun isInteractive(): Boolean
        fun lastDisplayedId(): Long
        fun isFailed(mediaId: Long): Boolean
        fun engineDestroyed(): Boolean
    }

    /**
     * Decode the next candidate image in the background so the following
     * auto-switch is near-instant. Only caches IMAGE media (never
     * videos/GIFs), only while the wallpaper is visible and the screen is
     * interactive, and never more than one image ahead. Picking is
     * side-effect free (SEQUENTIAL is item-based on LAST_IMAGE_ID, SHUFFLE
     * records shown items only when a switch actually applies them), so a
     * prefetch that is invalidated or fails can never skip an item.
     */
    fun maybePrefetchNext(
        source: String,
        sincePreviousSwitchMs: Long,
        groupId: Long = 0L,
    ) {
        if (!host.isVisible() || !host.isInteractive()) return
        if (cache.hasCache()) return
        // Prefetch ONLY while the user keeps switching rapidly: a warm
        // decode is what makes a double-tap burst / a run of floating-button
        // taps feel instant.
        if (source == LiveWallpaperService.SOURCE_TIMER) {
            AppLog.d(tag, "Timer switch: skipping prefetch (media access)")
            return
        }
        // 用户手动切换之后总是预解码下一张：用户此刻正看着桌面，很可能会
        // 再点一次，预解码把下一次点击从"现解码 ~250ms"变成"纹理上传 ~20ms"
        // （这就是"悬浮按钮不跟手"的直接原因）。定时切换仍然不预取，避免
        // 成倍增加媒体库读取（用户此前明确提过）。
        if (!isUserTapSource(source)) {
            if (sincePreviousSwitchMs <= 0L) {
                // First switch of this engine: there is no "previous" to
                // compare against, so this is never a rapid burst.
                AppLog.d(tag, "First switch of this engine; skipping prefetch")
                return
            }
            if (sincePreviousSwitchMs > PREFETCH_RAPID_GAP_MS) {
                AppLog.d(
                    tag,
                    "Not a rapid switch (${sincePreviousSwitchMs}ms); skipping prefetch"
                )
                return
            }
        }
        if (!cache.beginStore()) return
        scope.launch {
            try {
                val dao = db.settingsDao()
                val imageDao = db.wallpaperImageDao()
                if (db.wallpaperGroupDao().getEnabledGroupsSync().isEmpty()) return@launch
                val globalMode = try {
                    SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                } catch (_: Exception) { SwitchMode.RANDOM }
                // Prefetch the image the NEXT switch of THIS group will show
                // (its own mode + its own cursor), so the cached bitmap is
                // usable by that switch and not by another group's.
                val pickSeq = dao.getLong(SettingsKeys.PICK_SEQ)
                val group = if (groupId > 0L) {
                    db.wallpaperGroupDao().getGroupById(groupId)?.takeIf {
                        it.isEnabled && WallpaperTarget.fromName(it.target).includesHome
                    }
                } else null
                val scopedGroupId = group?.id ?: 0L
                val favoriteWeight = PickOptions.favoriteWeight(dao)
                val recentWindow = PickOptions.recentWindow(dao)
                val recentIds = PickOptions.recentIds(db, HOME_SLOT, recentWindow)
                val next = if (scopedGroupId > 0L) {
                    val lastId = try {
                        db.groupScheduleDao().get(scopedGroupId, HOME_SLOT)?.lastMediaId ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                    GroupPick.pick(
                        db, HOME_SLOT, scopedGroupId, globalMode, lastId,
                        forPrefetch = true, pickSeq = pickSeq,
                        filter = GroupRules.mediaFilter(group!!),
                        favoriteWeight = favoriteWeight,
                        recentIds = recentIds,
                    )
                } else {
                    picker.pickNextImage(
                        globalMode, imageDao, dao.getLong(SettingsKeys.LAST_IMAGE_ID), dao,
                        forPrefetch = true,
                        enabledCount = picker.enabledCountCached(imageDao, HOME_SLOT),
                        // Same seed the following switch will use (the counter
                        // only moves once that switch is applied).
                        pickSeq = pickSeq,
                        favoriteWeight = favoriteWeight,
                        recentIds = recentIds
                    )
                } ?: return@launch
                if (MediaTypes.isMotion(next.mediaType)) return@launch
                if (next.id == host.lastDisplayedId() || host.isFailed(next.id)) return@launch
                AppLog.d(
                    tag,
                    "Prefetching next image: ${next.displayName} id=${next.id} group=$scopedGroupId"
                )
                val prefetchedImage = loader.loadBitmapWithTimeout(
                    next.uri, media = next
                )
                val bmp = prefetchedImage?.bitmap
                if (bmp == null || bmp.isRecycled) return@launch
                if (host.engineDestroyed() || next.id == host.lastDisplayedId() ||
                    host.isFailed(next.id)
                ) {
                    bmp.recycle()
                    return@launch
                }
                // The visibility check at the top of this function ran BEFORE the
                // decode, and a decode can take seconds on a cloud provider: by
                // now the screen may be off or the wallpaper covered, and the
                // cache would then hold a full-screen ARGB bitmap for
                // PREFETCH_KEEP_MS - exactly the window in which the process is
                // most likely to be killed. Check again and walk away instead.
                if (!host.isVisible() || !host.isInteractive()) {
                    AppLog.d(tag, "Prefetch completed while hidden; dropping the bitmap")
                    bmp.recycle()
                    return@launch
                }
                val storedPrefetch = if (host.engineDestroyed()) {
                    false
                } else {
                    cache.store(
                        next.id, bmp, scopedGroupId, globalMode, prefetchedImage.rotateCw
                    )
                }
                if (storedPrefetch) {
                    AppLog.d(tag, "Prefetch ready: ${next.displayName} id=${next.id}")
                } else {
                    bmp.recycle()
                }
            } catch (_: Exception) {
            } finally {
                cache.endStore()
            }
        }
    }

    private companion object {
        private val HOME_SLOT = WallpaperTarget.SLOT_HOME

        /**
         * Prefetch is only worth it while the user is switching RAPIDLY (double
         * tap bursts, repeated floating-button taps): the gap to the previous
         * switch must be this short. Anything else is decoded on demand, which
         * halves the media-library reads per switch.
         */
        private const val PREFETCH_RAPID_GAP_MS = 3_000L
    }
}
