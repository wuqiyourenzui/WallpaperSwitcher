package com.wallpaperswitcher.viewmodel

import android.app.Application
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.engine.LiveWallpaperPermission
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.service.StaticApplyOutcome
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「把某张媒体设为壁纸」的两条流程，从 `WallpaperViewModel` 拆分出来：
 *
 * - [applyToScreen]：按分组 应用位置 写入桌面/锁屏（动态壁纸引擎在跑时桌面走引擎）；
 * - [applyAsLive]：总是弹出系统动态壁纸确认页，让用户显式确认。
 *
 * 逻辑逐字搬移，只把 app、数据库、协程 scope、本地化与 toast/hint 输出改为构造参数。
 */
internal class WallpaperApplyController(
    private val app: Application,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    /** 本地化（跟随 App 内语言，见 WallpaperViewModel.str）。 */
    private val localize: (Int, List<Any>) -> String,
    private val onToast: suspend (String) -> Unit,
    private val onHint: suspend (String) -> Unit,
    /** 系统动态壁纸选择页被 ROM 拦截（MIUI/HyperOS 动态壁纸服务关闭）。 */
    private val onPickerBlocked: () -> Unit,
    private val launchPicker: () -> Boolean,
) {

    private val tag = "WallpaperApply"

    private suspend fun toast(id: Int, vararg args: Any) =
        onToast(localize(id, args.toList()))

    private suspend fun hint(id: Int, vararg args: Any) =
        onHint(localize(id, args.toList()))

    /**
     * Make one media item the displayed wallpaper, applied to the screen(s) its
     * group targets (Paperize-style dual screen):
     * - 桌面 (HOME): the live engine shows it (broadcast) when the engine is
     *   running, otherwise it is written as the static home wallpaper;
     * - 锁屏 (LOCK): always written as the static lock-screen wallpaper (a live
     *   wallpaper cannot render a different image there);
     * - a video/GIF that would have to be written statically is applied as its
     *   first frame, and the user is told so.
     */
    fun applyToScreen(
        image: WallpaperImage,
        forceSlot: String? = null,
        /**
         * 应用结果回调（可选）。
         *
         * 调用方如果自己有"成功"提示（例如「最近显示」推回时弹的"已推回"），
         * 必须用这个回调决定要不要弹：失败时 Controller 自己会发失败 Toast
         * （分组被禁用 / 文件读不了 / 正忙），那时再弹一句"已推回"就是自相矛盾。
         */
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val group = db.wallpaperGroupDao().getGroupById(image.groupId)
                // A DISABLED group is not part of the rotation: its media must
                // not be settable from the group screen either (user report:
                // 「当分组图片未启用时，里面的图片仍能设置为壁纸」). The engine, the
                // static applier and both timers only ever pick from ENABLED
                // groups, so applying this media produced a wallpaper the next
                // redraw/switch replaced again.
                if (group != null && !group.isEnabled) {
                    AppLog.d(
                        tag,
                        "setImageAsWallpaper ignored: group ${group.id} is disabled"
                    )
                    toast(R.string.toast_group_disabled)
                    onResult?.invoke(false)
                    return@launch
                }
                val target = when (forceSlot) {
                    // 调用方明确指定了屏（大图浏览的「设为桌面/锁屏」、回滚页的
                    // 「推回当前屏」）：就写那一块，**即使分组是 LOCK-only/BOTH**。
                    // 以前只对 LOCK 做了强制，传 HOME 会回落到分组自己的 target ——
                    // 于是 LOCK-only 分组里点「设为桌面」实际上写的是锁屏。
                    WallpaperTarget.SLOT_LOCK -> WallpaperTarget.LOCK
                    WallpaperTarget.SLOT_HOME -> WallpaperTarget.HOME
                    else -> WallpaperTarget.fromName(group?.target)
                }
                AppLog.d(
                    tag,
                    "setImageAsWallpaper: id=${image.id} type=${image.mediaType} target=${target.nameValue}"
                )
                // The user explicitly picked this media: restart that screen's
                // schedule so the pick stays for at least one full interval, and
                // (home only) keep the timer off it while the system dialog is
                // open. The lock timer must keep running - it is only re-anchored.
                val now = System.currentTimeMillis()
                if (target.includesHome) {
                    db.settingsDao().setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, now)
                    db.settingsDao().setLong(
                        SettingsKeys.MANUAL_PICK_HOLD_UNTIL,
                        now + WallpaperViewModel.MANUAL_PICK_HOLD_MS
                    )
                }
                if (target.includesLock) {
                    db.settingsDao().setLong(SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS, now)
                }
                val motion = MediaTypes.isMotion(image.mediaType)
                val engineRunning = LiveWallpaperService.engineRunning
                // The engine flag can be stale (its process was killed in the
                // background, or a preview engine just tore down) while the live
                // wallpaper is still the home wallpaper. Treat that as ithe
                // engine owns the home screeni - writing a static image there
                // would replace the live wallpaper the user just set up.
                // Binder round-trip to WallpaperManagerService: keep it off the
                // main thread (this runs while the user's tap is being handled).
                val homeIsLive = withContext(Dispatchers.IO) {
                    LiveWallpaperService.isHomeLiveWallpaper(app)
                }

                // A motion wallpaper can only animate through the live engine.
                if (motion && target.includesHome && !engineRunning && !homeIsLive) {
                    // The ROM may refuse to show the picker at all (MIUI/HyperOS
                    // 动态壁纸服务): then the dialog is the only useful answer -
                    // emitting the "choose this app" hint on top of a picker that
                    // never appears would be noise.
                    if (!liveWallpaperPickerBlocked()) {
                        if (launchPicker()) {
                            hint(R.string.hint_motion_needs_engine)
                        } else {
                            toast(R.string.toast_picker_unavailable)
                        }
                    }
                    onResult?.invoke(false)
                    return@launch
                }

                var applied = false
                // "Busy" (a timed apply held the static guard for the whole wait
                // window) must not be reported as "the file could not be read".
                var busy = false
                fun note(outcome: StaticApplyOutcome) {
                    when (outcome) {
                        StaticApplyOutcome.APPLIED -> applied = true
                        StaticApplyOutcome.BUSY -> busy = true
                        StaticApplyOutcome.FAILED -> Unit
                    }
                }
                if (target.includesHome) {
                    if (engineRunning || homeIsLive) {
                        // Keep LAST_IMAGE_ID in sync so the engine continues
                        // from this media after a restart (and so the engine
                        // picks it up when its process is restarted). This is
                        // deliberate even when no engine accepts the push right
                        // now: the cursor is the PICK, and the next engine start
                        // renders it (see pushConfirmedPickToEngine).
                        db.settingsDao().setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                        // A fire-and-forget broadcast used to be sent here and the
                        // result thrown away, so a stale `engineRunning`/`homeIsLive`
                        // (engine killed between the read and the send) reported
                        // "已设为壁纸" while nothing changed. Ask the engine directly
                        // and only claim success when one really accepted it.
                        applied = LiveWallpaperService.pushConfirmedPickToEngine(image.id)
                        if (!applied) {
                            AppLog.w(
                                tag,
                                "No live engine accepted the home pick; cursor kept for the next start"
                            )
                        }
                    } else {
                        note(WallpaperSwitchService.applyStaticWallpaper(
                            app,
                            image.id,
                            android.app.WallpaperManager.FLAG_SYSTEM
                        ))
                    }
                }
                if (target.includesLock) {
                    // 锁屏独立定时开着时锁屏是静态图：视频/GIF 会被冻结成首帧，
                    // 跳过并提示（和之前一样）。关着时锁屏跟随动态壁纸，视频由
                    // 动态壁纸在锁屏播放 —— 不写静态锁屏图，也不再提示"锁屏不支持"。
                    val lockTimerEnabled = try {
                        db.settingsDao().getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)
                    } catch (_: Exception) {
                        true
                    }
                    when {
                        motion && !lockTimerEnabled -> {
                            AppLog.d(
                                tag,
                                "setImageAsWallpaper: lock follows the live wallpaper; " +
                                    "motion media is not skipped for the lock screen"
                            )
                            // 清掉可能存在的独立锁屏图，让动态壁纸（视频）透到锁屏。
                            try {
                                WallpaperSwitchService.enforceSlotsAfterLiveApply(app)
                            } catch (_: Exception) {
                            }
                            // 锁屏跟随动态壁纸，这次点击在锁屏侧没有可写的静态图：
                            // 对"仅锁屏"分组按已处理对待，避免误报"无法读取"。
                            if (!target.includesHome) applied = true
                        }
                        motion -> {
                            AppLog.d(
                                tag,
                                "setImageAsWallpaper: skipping motion media for the lock screen"
                            )
                            toast(R.string.toast_lock_no_motion)
                        }
                        else -> note(WallpaperSwitchService.applyStaticWallpaper(
                            app,
                            image.id,
                            android.app.WallpaperManager.FLAG_LOCK
                        ))
                    }
                }

                if (!applied) {
                    toast(
                        if (busy) R.string.toast_wallpaper_busy
                        else R.string.toast_wallpaper_unreadable
                    )
                    onResult?.invoke(false)
                    return@launch
                }
                toast(R.string.toast_wallpaper_set, localize(target.labelRes, emptyList()))
                onResult?.invoke(true)
            } catch (e: Exception) {
                AppLog.e(tag, "setImageAsWallpaper failed", e)
                toast(R.string.toast_set_failed, e.message.orEmpty())
                onResult?.invoke(false)
            }
        }
    }

    /**
     * Live wallpaper flow: the media becomes the displayed item and the SYSTEM
     * live-wallpaper preview/confirmation screen is ALWAYS shown (even when our
     * engine is already running) so the user explicitly confirms the change.
     */
    fun applyAsLive(image: WallpaperImage) {
        scope.launch {
            try {
                val group = db.wallpaperGroupDao().getGroupById(image.groupId)
                // Same rule as applyToScreen: a disabled group's media is
                // not settable (see the comment there). Checked BEFORE the HOME
                // cursor is moved / the picker is launched, so a disabled pick
                // cannot reach the engine or leave a pending preview pick behind.
                if (group != null && !group.isEnabled) {
                    AppLog.d(
                        tag,
                        "setAsLiveWallpaper ignored: group ${group.id} is disabled"
                    )
                    toast(R.string.toast_group_disabled)
                    return@launch
                }
                // ROM-level gate, checked before ANY state is moved: HyperOS/
                // MIUI refuses to draw the system picker unless this app is
                // allowed the 「动态壁纸服务」 app-op, and the refusal is
                // invisible here (startActivity succeeds; the screen finishes
                // itself ~20ms later). Aborting up front keeps the cursor and
                // the manual-pick memo untouched, and the dialog tells the user
                // where the switch is.
                if (liveWallpaperPickerBlocked()) return@launch
                val target = WallpaperTarget.fromName(group?.target)
                AppLog.d(
                    tag,
                    "setAsLiveWallpaper: id=${image.id} target=${target.nameValue}"
                )
                // Only a home-capable pick may move the HOME cursor: pointing it
                // at a lock-only media made the engine pick a different ("home")
                // image anyway, and it moved the cursor the lock enforcement
                // used to read (see MANUAL_PICK_MEDIA_ID).
                if (target.includesHome) {
                    // Remember the cursor BEFORE moving it: the picker's preview
                    // engine renders LAST_IMAGE_ID, and if the user cancels the
                    // system screen the engine must not apply the previewed media
                    // (see LiveWallpaperService.restoreHomeCursorAfterCancelledPick).
                    val previousHomeId = db.settingsDao().getLong(SettingsKeys.LAST_IMAGE_ID, 0L)
                    db.settingsDao().setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                    LiveWallpaperService.notePreviewPick(previousHomeId, image.id)
                }
                if (target.includesLock) {
                    // Remember exactly what the user picked: the enforcement
                    // runs after the system dialog closes, and by then the home
                    // timer may already have advanced LAST_IMAGE_ID.
                    val pickedAt = System.currentTimeMillis()
                    db.settingsDao().setLong(SettingsKeys.MANUAL_PICK_MEDIA_ID, image.id)
                    db.settingsDao().setLong(SettingsKeys.MANUAL_PICK_AT, pickedAt)
                }
                // Keep the engine preview (and the wallpaper it applies) on
                // THIS media while the system dialog is open, and restart the
                // schedule(s) the pick belongs to so it survives at least one
                // interval. The HOME anchor/hold only apply to home-capable
                // groups: a lock-only pick must not postpone the desktop timer
                // (same rule as applyToScreen).
                val now = System.currentTimeMillis()
                if (target.includesHome) {
                    db.settingsDao().setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, now)
                    db.settingsDao().setLong(
                        SettingsKeys.MANUAL_PICK_HOLD_UNTIL,
                        now + WallpaperViewModel.MANUAL_PICK_HOLD_MS
                    )
                }
                if (target.includesLock) {
                    db.settingsDao().setLong(SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS, now)
                }
                // Deliberately NO engine switch here.
                //
                // This used to push the picked media to the engine so the system
                // dialog's preview showed it - but that also meant the wallpaper
                // was already changed behind the dialog, so even CANCELLING the
                // system screen left the picked image applied (user report:
                // 「点击图片弹出系统动态壁纸界面，就设置了动态壁纸」).
                //
                // Clicking a picture now only opens the system live-wallpaper
                // screen. Confirming it re-applies our engine, which renders
                // LAST_IMAGE_ID (set above) and re-asserts the group's 应用位置
                // via enforceSlotsAfterLiveApply; cancelling changes nothing on
                // screen. A lock-only pick never touched the engine anyway.
                // Total failure (no activity handles either intent) used to be
                // swallowed: the tap then looked broken. Say so instead.
                if (!launchPicker()) {
                    toast(R.string.toast_picker_unavailable)
                    return@launch
                }
                // Every group can be set as a live wallpaper. Which screen then
                // shows which media is decided by the group's 应用位置 (see
                // enforceSlotsAfterLiveApply): lock-targeted groups get this
                // media on the lock screen, home/both groups keep the home
                // engine running on their own media.
                // Long hint: the system picker opens on top of the app right
                // away, so this has to survive several seconds (see HintOverlay).
                when {
                    // The system dialog fills the slot(s) it is told to, and
                    // on top of that this app re-asserts the group's 应用位置
                    // (see enforceSlotsAfterLiveApply), so the hint tells the
                    // user exactly which option matches their group.
                    target.includesHome && target.includesLock ->
                        hint(R.string.hint_pick_both)
                    target.includesHome ->
                        hint(R.string.hint_pick_home)
                    else ->
                        // Lock-only group: the system dialog has no ilock
                        // screeni option for live wallpapers, and this app
                        // re-asserts the group's 应用位置 afterwards (see
                        // enforceSlotsAfterLiveApply), so any confirm in the
                        // dialog ends up with this group's image on the lock
                        // screen. The hint therefore only tells the user to
                        // confirm, not which option to pick.
                        hint(R.string.hint_pick_lock)
                }
            } catch (e: Exception) {
                AppLog.e(tag, "setAsLiveWallpaper failed", e)
                toast(R.string.toast_set_failed, e.message.orEmpty())
            }
        }
    }

    /**
     * `true` when the system live-wallpaper picker is known to refuse this app
     * (MIUI/HyperOS 「动态壁纸服务」 off): the caller must abort without
     * touching any cursor, and the UI shows the "turn the switch on" dialog.
     */
    private fun liveWallpaperPickerBlocked(): Boolean {
        if (LiveWallpaperPermission.isSystemPickerAllowed(app)) {
            return false
        }
        AppLog.w(
            tag,
            "System live-wallpaper picker blocked: the MIUI 动态壁纸服务 permission is off"
        )
        onPickerBlocked()
        return true
    }
}
