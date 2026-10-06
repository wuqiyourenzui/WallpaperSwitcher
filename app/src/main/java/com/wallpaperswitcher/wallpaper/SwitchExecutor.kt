package com.wallpaperswitcher.wallpaper

import android.graphics.Bitmap
import android.os.Handler
import android.os.PowerManager
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.incrementLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.engine.GroupPick
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.PickOptions
import com.wallpaperswitcher.engine.SwitchPicking
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText
import kotlinx.coroutines.delay

/**
 * 一次壁纸切换的完整执行流程，从 `LiveWallpaperService` 拆分出来（第 24 轮解构）：
 * 门禁与设置读取 → 目标/分组/屏幕级取图（含预取缓存消费）→ 失败媒体跳过 →
 * 按媒体类型落屏（视频 / GIF / 图片）→ 游标、记录、shuffle 牌堆与淡入。
 *
 * 引擎只通过 [Host] 暴露它自己那点渲染状态与三个渲染动作（清图 / 退位 / 淡入），
 * 其余协作者（数据库、取图、预取缓存、失败恢复、视频会话、切换队列）都是构造参数。
 */
internal class SwitchExecutor(
    private val db: AppDatabase,
    private val picker: MediaPicker,
    private val loader: MediaBitmapLoader,
    private val gif: GifPlaybackController,
    private val prefetch: PrefetchCache,
    private val recovery: MediaFailureRecovery,
    private val video: VideoSessionController,
    private val switchQueue: SwitchQueue,
    private val mainHandler: Handler,
    private val powerManager: PowerManager,
    private val tag: String,
    private val host: Host,
) {

    internal interface Host {
        fun renderer(): WallpaperRenderer?

        fun currentBitmap(): Bitmap?
        fun setCurrentBitmap(bitmap: Bitmap?)

        fun scaleMode(): ScaleMode
        fun setScaleMode(mode: ScaleMode)

        fun autoRotateMismatch(): Boolean
        fun setAutoRotateMismatch(value: Boolean)
        fun autoRotateClockwise(): Boolean
        fun setAutoRotateClockwise(value: Boolean)

        fun lastDisplayedId(): Long
        fun setLastDisplayedId(id: Long)

        fun setSuppressFadeUntilFirstFrame(value: Boolean)
        fun setFadePendingForFirstFrame(value: Boolean)

        fun pendingVideoEndSwitch(): Boolean
        fun setPendingVideoEndSwitch(value: Boolean)
        fun pendingVideoEndGroupId(): Long
        fun setPendingVideoEndGroupId(value: Long)

        suspend fun applyClarityMode()
        suspend fun applyKenBurnsMode()

        fun clearCurrentBitmap()
        fun retireCurrentBitmap()
        suspend fun maybeFade()
    }

    suspend fun execute(source: String, targetId: Long?, groupId: Long = 0L) {
        // Only skip when the SCREEN is actually off: starting a decode in
        // the dark wastes battery, and the switch runs on the next tick
        // after screen-on. When another app merely covers the wallpaper
        // (screen still on), timer switches still execute - the new media
        // simply plays in the throttled power-save mode until visible.
        if (!powerManager.isInteractive()) {
            AppLog.d(tag, "Skip $source switch while screen is off (power save)")
            return
        }
        val dao = db.settingsDao()
        val imageDao = db.wallpaperImageDao()
        // 视频播完再切: hold a TIMED switch until the current clip reaches
        // the end of its pass (onVideoPassCompleted runs it then). Manual /
        // unlock switches are never held - the user expects the tap to act.
        // Every later tick must be dropped while a hold is pending too:
        // letting it through cut the clip off at the next interval.
        if (source == LiveWallpaperService.SOURCE_TIMER) {
            // 只有“真的在屏幕上播放”的视频才值得等它播完：power-save（不可见/
            // 被遮挡/前台）时解码已暂停，isVideoPlaying 却仍为 true —— 继续等
            // 会让定时切换永远被挡住（日志里的 “still held” 刷屏）。
            val videoOnScreen = video.active &&
                host.renderer()?.isVideoPlaying == true &&
                host.renderer()?.powerSaveMode != true
            val holdForVideo = try {
                dao.getBool(SettingsKeys.VIDEO_PLAY_TO_END, false)
            } catch (_: Exception) {
                false
            }
            when (
                SwitchPicking.videoEndHold(
                    optionEnabled = holdForVideo,
                    videoPlaying = videoOnScreen,
                    holdPending = host.pendingVideoEndSwitch(),
                )
            ) {
                SwitchPicking.VideoEndHold.HOLD_PENDING -> {
                    host.setPendingVideoEndSwitch(true)
                    host.setPendingVideoEndGroupId(groupId)
                    AppLog.d(tag, "Timed switch held: the video plays to its end first")
                    return
                }
                SwitchPicking.VideoEndHold.DROP_TICK -> {
                    // The clip is still playing: this tick must not switch.
                    // The held one runs from onVideoPassCompleted instead.
                    AppLog.d(tag, "Timed switch still held: waiting for the video to end")
                    return
                }
                SwitchPicking.VideoEndHold.SWITCH_NOW -> {
                    // Either the option was switched OFF while a tick was
                    // held, or the held clip left the screen without ever
                    // completing a pass (start failure / recovery / manual
                    // switch): the hold can never be released by
                    // onVideoPassCompleted any more, so drop it here instead
                    // of swallowing every future tick.
                    if (host.pendingVideoEndSwitch()) {
                        AppLog.d(
                            tag,
                            "Held timed switch released: no video is waiting on screen"
                        )
                    }
                    host.setPendingVideoEndSwitch(false)
                    host.setPendingVideoEndGroupId(0L)
                }
            }
        } else {
            // A manual / unlock / floating / notification / recovery switch
            // supersedes any held tick: the clip it was waiting for is being
            // replaced, so its pass will never complete. Without this the
            // stale hold blocked every later timed switch (the emulator
            // deadlock: "still held" ticks forever, wallpaper frozen).
            if (host.pendingVideoEndSwitch()) {
                AppLog.d(tag, "Held timed switch dropped: superseded by $source")
            }
            host.setPendingVideoEndSwitch(false)
            host.setPendingVideoEndGroupId(0L)
        }
        host.applyClarityMode()
        host.applyKenBurnsMode()
        host.setAutoRotateMismatch(try {
            dao.getBool(SettingsKeys.ROTATE_MISMATCH_ENABLED, true)
        } catch (_: Exception) {
            true
        })
        host.renderer()?.autoRotateMismatch = host.autoRotateMismatch()
        host.setAutoRotateClockwise(try {
            dao.getBool(SettingsKeys.ROTATE_MISMATCH_CW, true)
        } catch (_: Exception) {
            true
        })
        host.renderer()?.autoRotateClockwise = host.autoRotateClockwise()
        // Per-group tick (see GroupPacing): the scheduler picked ONE group
        // to bring on screen, so this switch must stay inside it. A group
        // that was disabled or retargeted between the tick and this switch
        // falls back to the screen-wide path instead of showing media the
        // user just removed from the rotation.
        val scopedGroup = if (groupId > 0L) {
            try {
                db.wallpaperGroupDao().getGroupById(groupId)?.takeIf {
                    it.isEnabled && WallpaperTarget.fromName(it.target).includesHome
                }
            } catch (_: Exception) {
                null
            }
        } else null
        val scopedGroupId = scopedGroup?.id ?: 0L
        // The group's 仅图片 / 仅视频 filter, shared by every pick and
        // fallback below. A fallback that forgot it could hand a video to a
        // 仅图片 group - which is exactly the reported "still switches to a
        // video" symptom.
        val scopedFilter = scopedGroup?.let {
            com.wallpaperswitcher.engine.GroupRules.mediaFilter(it)
        } ?: ""

        val globalSwitchMode = try {
            SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
        } catch (_: Exception) { SwitchMode.RANDOM }
        // 切换模式是全局设置：分组只带自己的间隔与时段（分组级的模式已取消）。
        val switchMode = globalSwitchMode

        val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
        if (groups.isEmpty()) return

        // Seed of this pick: the applied-switch counter. Read once here and
        // reused for the prefetch below - the preview reads the same value,
        // and it only moves after a switch has really been applied, so the
        // media 下一张预览 named is the media this switch shows.
        val pickSeq = try {
            dao.getLong(SettingsKeys.PICK_SEQ)
        } catch (_: Exception) {
            0L
        }
        // 收藏优先 / 最近 N 张不重复: read once, shared with the prefetch.
        val favoriteWeight = PickOptions.favoriteWeight(dao)
        val recentWindow = PickOptions.recentWindow(dao)
        val recentIds = PickOptions.recentIds(db, HOME_SLOT, recentWindow)

        // If target is already playing, skip restart (avoids video pause on "apply")
        if (targetId != null && targetId > 0 && targetId == host.lastDisplayedId()) {
            prefetch.clear()
            if (video.active && host.renderer()?.isVideoPlaying == true) {
                AppLog.d(tag, "Target $targetId already playing, skip")
                return
            }
            val shownBmp = host.currentBitmap()
            if (!video.active && shownBmp != null && !shownBmp.isRecycled) {
                AppLog.d(tag, "Target $targetId already showing, skip")
                return
            }
            // A GIF already rendering must not be re-decoded and restarted
            // by re-selecting the same media (the animation would flash and
            // restart from the first frame).
            if (!video.active && gif.isActive()) {
                AppLog.d(tag, "Target $targetId GIF already rendering, skip")
                return
            }
        }

        host.setScaleMode(try {
            ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
        } catch (_: Exception) { ScaleMode.FIT })

        var cachedBitmap: Bitmap? = null
        // GPU quarter turn that belongs to [cachedBitmap] (see [PrefetchCache.take]).
        var cachedRotateCw: Boolean? = null
        var nextImage = if (targetId != null && targetId > 0) {
            // A manual selection supersedes any prefetched next image.
            prefetch.clear()
            val img = imageDao.getImageById(targetId)
            // Explicit user selection always applies the chosen media.
            // Silently replacing a manual pick with a random image was
            // confusing. A DISABLED group's media never reaches this point:
            // the user-facing entry points (setAsLiveWallpaper /
            // setImageAsWallpaper) refuse a disabled group, and the internal
            // callers (media-type repair, a confirmed pick) take their id from
            // an enabled pick - so this permissive rule cannot re-apply media
            // from a group the user switched off.
            img ?: picker.pickNextImage(SwitchMode.RANDOM, imageDao, 0L, dao)
        } else if (scopedGroupId > 0L) {
            // Group-scoped switch: the group's own cursor (lastMediaId) is
            // the reference, not the shared LAST_IMAGE_ID.
            val lastId = try {
                db.groupScheduleDao().get(scopedGroupId, HOME_SLOT)?.lastMediaId ?: 0L
            } catch (_: Exception) {
                0L
            }
            // Consume a prefetch that belongs to THIS group (see
            // PrefetchController): the cached bitmap is the image this
            // group's next switch would pick, so a warm tap is an upload
            // instead of a decode. Another group's / the screen-wide cache
            // is dropped rather than shown.
            val prefetched = prefetch.take()
            val cachedId = prefetched.imageId
            val cachedBmp = prefetched.bitmap
            var cached: WallpaperImage? = null
            if (prefetched.groupId == scopedGroupId && cachedId > 0L && cachedBmp != null) {
                val img = imageDao.getImageById(cachedId)
                if (img != null && img.groupId == scopedGroupId &&
                    !recovery.isFailed(img.id) &&
                    // The cached bitmap may predate a 仅图片 / 仅视频 change:
                    // drop it instead of showing a media the group no
                    // longer allows.
                    com.wallpaperswitcher.engine.GroupRules
                        .allowsMedia(scopedFilter, img.mediaType)
                ) {
                    cached = img
                    cachedBitmap = cachedBmp
                    cachedRotateCw = prefetched.rotateCw
                } else {
                    cachedBmp.recycle()
                }
            } else {
                cachedBmp?.recycle()
            }
            cached ?: GroupPick.pick(
                db, HOME_SLOT, scopedGroupId, switchMode, lastId,
                pickSeq = pickSeq,
                filter = scopedFilter,
                favoriteWeight = favoriteWeight,
                recentIds = recentIds,
            )
        } else {
            val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
            // Auto switch: consume the prefetch cache when it is still
            // valid, otherwise pick normally.
            // If a prefetch decode is already in flight (rapid double-tap
            // burst), briefly wait for it instead of launching a second
            // concurrent screen-size decode: parallel decodes spike memory
            // and CPU and make switching visibly stutter.
            if (prefetch.isStoreInProgress()) {
                var waited = 0
                while (waited < 250) {
                    val ready = prefetch.hasCache()
                    if (ready || !prefetch.isStoreInProgress()) break
                    delay(10)
                    waited += 10
                }
            }
            // The cache holds the "next" image for the switch mode that was
            // active when it was built. If the user changed the mode since
            // (e.g. RANDOM -> SEQUENTIAL), it is stale: drop it so the new
            // mode picks fresh (SEQUENTIAL continues after the currently
            // displayed wallpaper instead of showing a leftover random
            // prefetch).
            prefetch.discardIfModeMismatch(switchMode)
            val prefetched = prefetch.take()
            val cachedId = prefetched.imageId
            val cachedBmp = prefetched.bitmap
            cachedRotateCw = prefetched.rotateCw
            var cached: WallpaperImage? = null
            // Only a screen-wide prefetch may satisfy a screen-wide switch.
            if (prefetched.groupId == 0L && cachedId > 0L && cachedBmp != null) {
                val img = imageDao.getImageById(cachedId)
                if (img != null && !MediaTypes.isMotion(img.mediaType) &&
                    !recovery.isFailed(img.id)
                ) {
                    val group = db.wallpaperGroupDao().getGroupById(img.groupId)
                    if (group != null && group.isEnabled) cached = img
                }
                if (cached == null) {
                    cachedBmp.recycle()
                }
            }
            if (cached != null) {
                cachedBitmap = cachedBmp
                cached
            } else {
                // Query the enabled count ONCE and reuse it: the SHUFFLE
                // path used to re-count on every random attempt (up to 10x
                // per switch) plus its own total-count query.
                val enabledCount = picker.enabledCountCached(imageDao, HOME_SLOT)
                picker.pickNextImage(
                    switchMode, imageDao, lastId, dao,
                    enabledCount = enabledCount, pickSeq = pickSeq,
                    favoriteWeight = favoriteWeight, recentIds = recentIds
                )
            }
        }

        if (nextImage == null) {
            if (scopedGroupId > 0L) {
                // The group has no media this screen can show: nothing to
                // switch to, and falling back to another group would break
                // the per-group rhythm the scheduler just chose.
                AppLog.d(tag, "Group $scopedGroupId has no media for $HOME_SLOT; skipping tick")
                return
            }
            nextImage = imageDao.getFirstFromEnabledGroups(HOME_SLOT)
            if (nextImage == null) return
        }

        // Skip media that recently failed to start (broken video files) so
        // recovery switches never re-pick the same broken item.
        var media: WallpaperImage = nextImage
        if (recovery.isFailed(media.id)) {
            var attempts = 0
            while (attempts < FAILED_MEDIA_RETRY_LIMIT) {
                attempts++
                // Exclude the PREVIOUS candidate on every pass. The old loop
                // only assigned `media` when it had already found a healthy
                // item, so all five queries excluded the same id and four of
                // them were pure repetition (wasted media-library reads).
                val alt = if (scopedGroupId > 0L) {
                    val pickDao = db.groupPickDao()
                    pickDao.getRandomInGroupExcluding(
                        HOME_SLOT, scopedGroupId, media.id, scopedFilter
                    )
                        ?: pickDao.getRandomInGroup(HOME_SLOT, scopedGroupId, scopedFilter)
                        ?: break
                } else {
                    imageDao.getRandomImageFromEnabledGroupsExcluding(HOME_SLOT, media.id)
                        ?: imageDao.getRandomImageFromEnabledGroups(HOME_SLOT)
                        ?: break
                }
                media = alt
                if (!recovery.isFailed(alt.id)) break
            }
        }
        nextImage = media

        // The media the engine is already showing: a timer tick that picks it
        // again (one-item group, sequential/shuffle wrap) must not restart
        // it - a video would visibly jump back to its first frame, and the
        // decode + texture upload is pure waste. The cursor still points at
        // this media, so the next distinctive pick works as before.
        if (targetId == null && host.lastDisplayedId() != 0L &&
            nextImage.id == host.lastDisplayedId()
        ) {
            AppLog.d(
                tag,
                "Auto switch picked the media already on screen (${nextImage.displayName}); skipping"
            )
            return
        }
        val mediaType = nextImage.mediaType
        // Include the id: multiple files can share the same display name
        // (logs showed '1 (7).jpg' twice with different ids), which made
        // duplicate/order investigations ambiguous.
        AppLog.d(tag, "Switch to: ${nextImage.displayName} ($mediaType) id=${nextImage.id}")

        gif.pause()

        // A video gets its fade-in from the render thread once its first
        // frame is really on screen (see onFirstVideoFrame); a switch must
        // never suppress that.
        var fadeHandledByFirstFrame = false
        // Did THIS switch manage to put its media on screen? A boolean of its
        // own, because `lastDisplayedId` is shared: a second switch that starts
        // right after (rapid taps + the prefetch flow) overwrites it, and the
        // old check then thought this media was never shown - which left the
        // shuffle pass without a record of it and repeated it later.
        var appliedThisSwitch = false
        host.setSuppressFadeUntilFirstFrame(false)
        host.setFadePendingForFirstFrame(false)
        when (mediaType) {
            MediaTypes.VIDEO -> {
                // Image/GIF -> Video. The previous image stays on screen: its
                // GL texture still holds the pixels and the codec renders
                // into its own texture, so the bitmap is only released once the
                // new video's first frame is really presented (see
                // onFirstVideoFrame). Recycling it here left a window where a
                // redraw had nothing to draw - the image->video black flash.
                video.stop()
                // No extra settle delay: video.stop() already joins the decode
                // thread (bounded, <=120ms) before returning.
                host.retireCurrentBitmap()
                if (video.start(
                        nextImage.uri, host.scaleMode(),
                        video.resumePositionFor(nextImage.id)
                    )
                ) {
                    host.setLastDisplayedId(nextImage.id)
                    appliedThisSwitch = true
                    fadeHandledByFirstFrame = true
                    // Decided here, applied by onFirstVideoFrame once the
                    // frame is actually on screen. Every switch plays the
                    // transition, however fast the taps come.
                    host.setFadePendingForFirstFrame(true)
                }
            }
            MediaTypes.GIF -> {
                // Any → GIF: stop video atomically (show nothing, GIF will overwrite)
                video.stop()
                delay(SWITCH_SETTLE_DELAY_MS)
                host.clearCurrentBitmap()
                video.markInactive()
                mainHandler.post { gif.play(nextImage.uri, host.scaleMode(), nextImage.id) }
                gif.startHealthMonitor(nextImage.id, nextImage.uri)
                host.setLastDisplayedId(nextImage.id)
                appliedThisSwitch = true
            }
            else -> {
                // Any → Image: load bitmap FIRST, then stop video + render atomically
                video.markInactive()
                var bitmap = cachedBitmap
                var rotateCw = cachedRotateCw
                if (bitmap == null || bitmap.isRecycled) {
                    AppLog.d(tag, "Loading image bitmap: ${LogText.short(nextImage.uri)}")
                    val loaded = loader.loadBitmapWithTimeout(
                        nextImage.uri, media = nextImage
                    )
                    bitmap = loaded?.bitmap
                    rotateCw = loaded?.rotateCw
                } else {
                    AppLog.d(tag, "Using prefetched bitmap: ${nextImage.displayName} id=${nextImage.id}")
                }
                if (bitmap != null) {
                    // rotate= is the GPU quarter turn (see computeQuad): it is
                    // what replaced the old full-screen CPU rotation copy.
                    AppLog.d(
                        tag,
                        "Bitmap loaded: ${bitmap.width}x${bitmap.height} " +
                            "rotate=${rotateCw?.let { if (it) "cw" else "ccw" } ?: "none"}"
                    )
                    // Recycle old bitmap to avoid memory leak
                    val old = host.currentBitmap()
                    host.setCurrentBitmap(bitmap)
                    if (old != null && old !== bitmap && !old.isRecycled) {
                        old.recycle()
                    }
                    // Always use stopVideoAndRender for clean transition.
                    // Even if isVideoPlaying is false, the decode thread might
                    // still be running and its cleanup could interfere.
                    // rotateCw: the FILL/STRETCH orientation turn is done by
                    // the quad, so no full-screen copy was needed here.
                    host.renderer()?.stopVideoAndRender(bitmap, host.scaleMode(), rotateCw)
                    host.setLastDisplayedId(nextImage.id)
                    appliedThisSwitch = true
                } else {
                    AppLog.e(tag, "Failed to load bitmap for: ${nextImage.displayName} uri=${LogText.short(nextImage.uri)}")
                    // An image that cannot be decoded may simply be
                    // mis-typed: older builds stored SAF videos as IMAGE
                    // (extension-less display names on non-Xiaomi
                    // devices), which is exactly the "video shows black"
                    // bug. Repair the row and retry that same media once
                    // instead of blocklisting a perfectly good file.
                    if (recovery.repairMisTypedMedia(nextImage)) {
                        AppLog.w(
                            tag,
                            "Retrying ${nextImage.displayName} after repairing its media type"
                        )
                        host.setLastDisplayedId(0L)
                        switchQueue.request("media-type-repair", nextImage.id)
                    } else {
                        // A broken/deleted file must not leave the
                        // wallpaper black/stuck until the next timer tick:
                        // blocklist the id and schedule a recovery switch
                        // to a different media (bounded like the video
                        // recovery path). Stay inside the group that owns the
                        // screen: a screen-wide recovery could otherwise jump
                        // to a media the group's filter excludes.
                        host.setLastDisplayedId(0L)
                        recovery.noteFailed(nextImage.id, scopedGroupId)
                    }
                }
            }
        }
        // A healthy media was applied: drop THIS id from the failure
        // blocklist so a previously-broken file can be retried later (the
        // old `id !in failedMediaIds` guard made the cleanup dead code
        // exactly when the current media was the previously-broken one).
        // GIF is excluded (see MediaFailureRecovery.noteAppliedHealthy).
        if (appliedThisSwitch) {
            recovery.noteAppliedHealthy(nextImage.id, mediaType)
        }
        // Advance the switching cursor only for media that really reached
        // the screen. The old code wrote it before the decode, so a failed
        // (or later blocklisted) media was silently skipped for the rest of
        // the pass - the opposite of what the failure path claims.
        if (appliedThisSwitch) {
            // Whatever just came on screen replaces the video whose end we
            // were waiting for, so the held tick is no longer meaningful.
            host.setPendingVideoEndSwitch(false)
            host.setPendingVideoEndGroupId(0L)
            // 记住现在屏幕上是谁：「最近显示」回滚页靠这张表（手滑切走一张
            // 好图要能找回来）。和已移除的"最近 N 张不重复"无关。
            PickOptions.recordShown(db, HOME_SLOT, nextImage.id)
            try {
                dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
            } catch (_: Exception) {
            }
            // The media really reached the screen: advance the pick seed so
            // the NEXT pick (and its preview) draws a fresh value. Doing it
            // only here is what keeps 下一张预览 and this switch agreeing.
            try {
                dao.incrementLong(SettingsKeys.PICK_SEQ)
            } catch (_: Exception) {
            }
            // Per-group cursor: the group's own rhythm continues from THIS
            // media (lastMediaId is the reference the group-scoped
            // SEQUENTIAL/RANDOM/SHUFFLE picks use).
            if (scopedGroupId > 0L) {
                try {
                    val scheduleDao = db.groupScheduleDao()
                    scheduleDao.ensureRow(scopedGroupId, HOME_SLOT)
                    scheduleDao.updateLastMedia(
                        scopedGroupId, HOME_SLOT, nextImage.id, System.currentTimeMillis()
                    )
                } catch (_: Exception) {
                }
            }
        }
        // SHUFFLE: record the item as shown when THIS switch applied it.
        // (Still not at pick/prefetch time: a prefetched media whose cache was
        // invalidated, or whose apply failed, must stay available.) The flag is
        // per switch, so a following switch overwriting `lastDisplayedId` can no
        // longer make this media look "never shown" - that was how a pass came
        // to deal an image it had already displayed.
        if (appliedThisSwitch && switchMode == SwitchMode.SHUFFLE) {
            if (scopedGroupId > 0L) {
                // The group's deck is persisted straight away (one row),
                // exactly like the screen-wide one below - and NOT through
                // pendingShuffleIds, which belongs to the screen-wide deck.
                GroupPick.recordShown(db, HOME_SLOT, scopedGroupId, nextImage.id)
            } else {
                // Persist the pass progress NOW, not only in onDestroy: MIUI
                // kills and recreates the wallpaper engine often, and a
                // stale/missing "already shown" set made the rebuilt deck deal
                // images that had already been shown before the pass finished.
                picker.noteShuffleShown(imageDao, nextImage.id)
            }
        }
        if (host.lastDisplayedId() == nextImage.id && !fadeHandledByFirstFrame) {
            host.maybeFade()
        }
    }

    private companion object {
        private val HOME_SLOT = WallpaperTarget.SLOT_HOME

        /** Any → GIF settle delay (see the GIF branch). */
        private const val SWITCH_SETTLE_DELAY_MS = 30L

        /** Bounded retries when the picked media is blocklisted as broken. */
        private const val FAILED_MEDIA_RETRY_LIMIT = 5
    }
}
