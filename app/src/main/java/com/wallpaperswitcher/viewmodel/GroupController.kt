package com.wallpaperswitcher.viewmodel

import android.app.Application
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 分组的增删改与调度参数（应用位置/独立间隔/时段/星期/素材类型），
 * 从 `WallpaperViewModel` 拆分出来：逻辑逐字搬移，只把 app、数据库、
 * 选中状态回调、写失败守卫与 toast 输出改为构造参数。
 *
 * Every write here ends with a [WallpaperSwitchService.poke] so the timer
 * loops re-evaluate which groups may feed each screen immediately instead of
 * after the next idle poll.
 */
internal class GroupController(
    private val app: Application,
    private val db: AppDatabase,
    private val storage: StorageController,
    private val scope: CoroutineScope,
    /** 当前选中的分组（删除后要清空详情页）。 */
    private val selectedGroupId: () -> Long?,
    private val onSelectGroup: (Long?) -> Unit,
    /** ViewModel 的统一写失败守卫（见 guardedWrite）。 */
    private val guard: (String, suspend () -> Unit) -> Unit,
    /** 新建分组失败的 toast（文案由 ViewModel 本地化）。 */
    private val onCreateFailed: suspend (String) -> Unit,
) {

    private val tag = "GroupController"

    fun createGroupAndOpen(name: String, onCreated: (Long) -> Unit) {
        scope.launch {
            try {
                val id = db.wallpaperGroupDao().insert(WallpaperGroup(name = name))
                onCreated(id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(tag, "创建分组失败", e)
                onCreateFailed(e.message.orEmpty())
            }
        }
    }

    fun updateGroup(group: WallpaperGroup) {
        guard("更新分组失败") { db.wallpaperGroupDao().update(group) }
    }

    fun deleteGroup(group: WallpaperGroup) {
        guard("删除分组失败") {
            val uris = try {
                db.wallpaperImageDao().getUrisByGroup(group.id)
            } catch (_: Throwable) {
                emptyList()
            }
            db.wallpaperGroupDao().delete(group)
            storage.deleteOwnedFiles(uris)
                // The group's media left the enabled set: drop the SHUFFLE deck's
                // cached id list (see MediaPick.enabledIdsFor).
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            // Reuse selectGroup(null) so the loaded list + count are cleared
            // too; otherwise the UI could briefly show the deleted group's
            // residual list.
            if (selectedGroupId() == group.id) onSelectGroup(null)
            clearCursorsOfDeletedMedia()
        }
    }

    /**
     * Delete several groups at once (home screen multi-select).
     *
     * Each group's media ROWS go with it (the Room relation cascades) - the files
     * on the phone are untouched, exactly like the single-group delete.
     */
    fun deleteGroups(ids: Set<Long>) {
        if (ids.isEmpty()) return
        guard("删除分组失败") {
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            val uris = ArrayList<String>()
            ids.forEach { id ->
                db.wallpaperGroupDao().getGroupById(id)?.let { group ->
                    try {
                        uris.addAll(db.wallpaperImageDao().getUrisByGroup(id))
                    } catch (_: Throwable) {
                    }
                    db.wallpaperGroupDao().delete(group)
                }
            }
            storage.deleteOwnedFiles(uris)
            // The detail screen must not keep showing a group that is now gone.
            if (selectedGroupId()?.let { it in ids } == true) onSelectGroup(null)
            clearCursorsOfDeletedMedia()
        }
    }

    /**
     * Enable/disable several groups at once (home screen multi-select).
     *
     * One [WallpaperSwitchService.poke] at the end instead of one per group: the
     * timer loops only need to re-evaluate which groups may feed each screen, and
     * poking N times would restart those loops N times.
     */
    fun setGroupsEnabled(ids: Set<Long>, enabled: Boolean) {
        if (ids.isEmpty()) return
        guard("批量切换分组失败") {
                com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            var changed = false
            ids.forEach { id ->
                val group = db.wallpaperGroupDao().getGroupById(id) ?: return@forEach
                if (group.isEnabled != enabled) {
                    db.wallpaperGroupDao().update(group.copy(isEnabled = enabled))
                    changed = true
                }
            }
            if (changed) WallpaperSwitchService.poke(app)
        }
    }

    /**
     * Drop cursors / write memos that pointed at media of a group that was just
     * deleted: their rows are gone (CASCADE), so a stale id would make the engine
     * or the lock enforcement chase a media that no longer exists.
     */
    private suspend fun clearCursorsOfDeletedMedia() {
        val settingsDao = db.settingsDao()
        val imageDao = db.wallpaperImageDao()
        val lastId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID)
        if (lastId > 0L && imageDao.getImageById(lastId) == null) {
            settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, 0L)
        }
        // Same for the lock screen's own cursor / write memo.
        val lastLockId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID_LOCK)
        if (lastLockId > 0L && imageDao.getImageById(lastLockId) == null) {
            settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID_LOCK, 0L)
        }
        val lastLockWrite = settingsDao.getLong(SettingsKeys.LAST_LOCK_WRITE_ID)
        if (lastLockWrite > 0L && imageDao.getImageById(lastLockWrite) == null) {
            settingsDao.setLong(SettingsKeys.LAST_LOCK_WRITE_ID, 0L)
        }
        val lastHomeWrite = settingsDao.getLong(SettingsKeys.LAST_HOME_WRITE_ID)
        if (lastHomeWrite > 0L && imageDao.getImageById(lastHomeWrite) == null) {
            settingsDao.setLong(SettingsKeys.LAST_HOME_WRITE_ID, 0L)
        }
        val manualPick = settingsDao.getLong(SettingsKeys.MANUAL_PICK_MEDIA_ID)
        if (manualPick > 0L && imageDao.getImageById(manualPick) == null) {
            settingsDao.setLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, 0L)
        }
    }

    fun toggleGroupEnabled(groupId: Long, enabled: Boolean) {
        guard("切换分组失败") {
            val group = db.wallpaperGroupDao().getGroupById(groupId)
                ?: return@guard
            db.wallpaperGroupDao().update(group.copy(isEnabled = enabled))
            // A group that just became (in)active changes what each screen may
            // show: wake the timer loops so the change applies immediately
            // instead of after the lock loop's idle poll.
            WallpaperSwitchService.poke(app)
        }
    }

    /**
     * Choose where this group's media may be shown: 桌面 / 锁屏 / 桌面和锁屏.
     * The home screen and the lock screen are switched independently, each from
     * the enabled groups that target it (Paperize-style dual screen).
     */
    fun setGroupTarget(groupId: Long, target: WallpaperTarget) {
        guard("设置应用位置失败") {
            db.wallpaperGroupDao().updateTarget(groupId, target.nameValue)
            AppLog.d(tag, "setGroupTarget: group=$groupId target=${target.nameValue}")
            WallpaperSwitchService.poke(app)
        }
    }

    /**
     * 分组独立间隔: [intervalMs] = 0 means "follow the screen's global
     * interval" (the default). Anything else makes this group due on its own
     * rhythm (see GroupPacing) - the timer wakes for whichever group is due
     * first, and the switch then only shows THIS group's media.
     */
    fun setGroupInterval(groupId: Long, intervalMs: Long) {
        guard("设置分组间隔失败") {
            db.wallpaperGroupDao().updateInterval(groupId, intervalMs.coerceAtLeast(0L))
            AppLog.d(tag, "setGroupInterval: group=$groupId interval=${intervalMs}ms")
            WallpaperSwitchService.poke(app)
        }
    }

    /**
     * 时间规则: the minutes-of-day window this group may be shown in
     * (-1 = 全天). Out-of-window groups are skipped by the scheduler instead of
     * being switched to.
     */
    fun setGroupActiveWindow(groupId: Long, fromMinute: Int, toMinute: Int) {
        guard("设置分组时段失败") {
            val group = db.wallpaperGroupDao().getGroupById(groupId)
                ?: return@guard
            db.wallpaperGroupDao().update(
                group.copy(
                    activeFromMinute = fromMinute.coerceIn(-1, 1439),
                    activeToMinute = toMinute.coerceIn(-1, 1439)
                )
            )
            AppLog.d(tag, "setGroupActiveWindow: group=$groupId $fromMinute..$toMinute")
            WallpaperSwitchService.poke(app)
        }
    }

    /**
     * 时间规则 · 星期: bitmask with bit 0 = Monday … bit 6 = Sunday.
     * `0` or the full mask means "every day".
     */
    fun setGroupActiveDays(groupId: Long, daysMask: Int) {
        guard("保存分组星期失败") {
            val group = db.wallpaperGroupDao().getGroupById(groupId)
                ?: return@guard
            val mask = daysMask and com.wallpaperswitcher.engine.GroupRules.ALL_DAYS
            db.wallpaperGroupDao().update(
                group.copy(
                    activeDays = if (mask == com.wallpaperswitcher.engine.GroupRules.ALL_DAYS) {
                        0
                    } else {
                        mask
                    }
                )
            )
            AppLog.d(tag, "setGroupActiveDays: group=$groupId mask=$mask")
            WallpaperSwitchService.poke(app)
        }
    }

    /**
     * 分组素材类型: "" = 两者都切换, "IMAGE" = 仅图片, "MOTION" = 仅视频（含 GIF）.
     * 只影响这个分组的取图，桌面/锁屏各自的节奏与全局模式都不变。
     */
    fun setGroupMediaFilter(groupId: Long, filter: String) {
        guard("保存分组素材设置失败") {
            val group = db.wallpaperGroupDao().getGroupById(groupId)
                ?: return@guard
            val safe = when (filter) {
                com.wallpaperswitcher.engine.GroupRules.MEDIA_IMAGE,
                com.wallpaperswitcher.engine.GroupRules.MEDIA_VIDEO -> filter
                else -> ""
            }
            db.wallpaperGroupDao().update(group.copy(filterMode = safe))
            com.wallpaperswitcher.engine.MediaPick.invalidateEnabledIds()
            AppLog.d(tag, "setGroupMediaFilter: group=$groupId filter=$safe")
            WallpaperSwitchService.poke(app)
        }
    }
}
