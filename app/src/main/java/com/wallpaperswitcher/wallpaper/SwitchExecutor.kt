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

    /** 屏幕上当前这条媒体是不是视频（定时 tick 该不该让路，见 SwitchQueue.Host）。 */
    fun isVideoActive(): Boolean = video.active

    /** 视频播完再切：定时 tick 最多连续丢这么多次（兜底，见 [TIMER_DROP_LIMIT]）。 */
    val timerDropLimit: Int get() = TIMER_DROP_LIMIT

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

        /**
         * 连续被丢弃的定时 tick 数（见 [SwitchPicking.timerWithVideo]）：
         * 每完成一次视频 pass 清零，超过 [TIMER_DROP_LIMIT] 时放行一次，
         * 避免"视频永远不播完"把定时切换彻底挡住。
         */
        fun timerDropCount(): Int
        fun setTimerDropCount(value: Int)

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
        // 视频播完再切：定时 tick 撞上视频的丢弃判定已经在队列入口做掉了
        // （SwitchQueue.consumeSwitches → Host.skipTimedSwitchForVideo），
        // 能走到这里的定时切换都是"该切的那一次"：图片，或视频坏掉的兜底放行。
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
        // `var`: 顺序模式下这一组走完会自动推进到下一个分组（见下面的
        // "Sequential: group ... exhausted"），之后的游标写入必须落在新组上。
        var scopedGroupId = scopedGroup?.id ?: 0L
        // The group's 仅图片 / 仅视频 filter, shared by every pick and
        // fallback below. A fallback that forgot it could hand a video to a
        // 仅图片 group - which is exactly the reported "still switches to a
        // video" symptom.
        var scopedFilter = scopedGroup?.let {
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
            //
            // A cache built for the switch mode that was active when it was
            // decoded is stale after a mode change (RANDOM -> SEQUENTIAL): eating
            // it would show a random leftover as the "next in order". The
            // screen-wide branch below has always dropped it; this branch
            // forgot, so the first switch after a mode change could still be
            // the old mode's pick.
            prefetch.discardIfModeMismatch(switchMode)
            // The prefetch of this group may still be DECODING (the previous
            // switch started it); wait for it (bounded) like the screen-wide
            // branch does. Taking the cache before it lands would decode the
            // same image a second time, and the late store would then park the
            // image that is now on screen in the cache - one tap later that
            // entry would pick the current image and the switch would be
            // skipped as "already on screen" (a tap that does nothing).
            awaitPrefetchReady()
            val prefetched = prefetch.take()
            val cachedId = prefetched.imageId
            val cachedBmp = prefetched.bitmap
            var cached: WallpaperImage? = null
            if (prefetched.groupId == scopedGroupId && cachedId > 0L && cachedBmp != null) {
                val img = imageDao.getImageById(cachedId)
                if (img != null && img.groupId == scopedGroupId &&
                    // The displayed image itself is never a valid "next":
                    // re-applying it is a no-op for the user (see above).
                    img.id != host.lastDisplayedId() &&
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
            cached ?: run {
                // 顺序模式（本组走完 → 下一个分组）走 GroupPick 里的共享 walker，
                // 定时 / 手动 / 静态 / 预取 / 下一张预览因此永远落在同一张、同一
                // 分组。换组后必须把 scope 一起换掉：下面写游标、记 shown、
                // 失败重试的过滤都用它，否则新组的 `group_schedule.lastMediaId`
                // 一直是 0，每次进这一组都从第一张重来。
                if (switchMode == SwitchMode.SEQUENTIAL) {
                    val walk = GroupPick.pickSequential(
                        db, HOME_SLOT, scopedGroupId, lastId,
                        pickSeq = pickSeq,
                        favoriteWeight = favoriteWeight,
                        recentIds = recentIds,
                        // 顺序模式接**屏幕上这张**往下走：随机 / 洗牌期间分组游标
                        // 不推进（它们的 tick 是屏幕级的），只按游标会从上次顺序
                        // 模式的旧位置继续（用户反馈的那个 bug）。
                        displayedId = host.lastDisplayedId(),
                    )
                    if (walk == null) {
                        null
                    } else {
                        if (walk.groupId != scopedGroupId) {
                            AppLog.d(
                                tag,
                                "Sequential: group $scopedGroupId exhausted, " +
                                    "advancing to ${walk.groupId}",
                            )
                        }
                        scopedGroupId = walk.groupId
                        scopedFilter = walk.filter
                        walk.image
                    }
                } else {
                    GroupPick.pick(
                        db, HOME_SLOT, scopedGroupId, switchMode, lastId,
                        pickSeq = pickSeq,
                        filter = scopedFilter,
                        newestFirst = com.wallpaperswitcher.engine.GroupRules
                            .newestFirst(scopedGroup),
                        favoriteWeight = favoriteWeight,
                        recentIds = recentIds,
                    )
                }
            }
        } else {
            val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
            // Auto switch: consume the prefetch cache when it is still
            // valid, otherwise pick normally.
            // If a prefetch decode is already in flight (rapid double-tap
            // burst), briefly wait for it instead of launching a second
            // concurrent screen-size decode: parallel decodes spike memory
            // and CPU and make switching visibly stutter.
            awaitPrefetchReady()
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
                    img.id != host.lastDisplayedId() &&
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
            // 新内容已经上屏：上一个视频的播完计数与"定时被丢弃"的计数都作废
            // （下一次视频从 1 数起，图片则重新允许定时切换）。
            host.setTimerDropCount(0)
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

    /**
     * Bounded wait for an in-flight prefetch decode (see [PrefetchController]).
     *
     * Consuming the cache while the previous switch's prefetch is still
     * decoding decodes the same image a second time; the late store then parks
     * the image that is now on screen in the cache, and the tap after that
     * picks it and is skipped as "already on screen" - a tap that visibly does
     * nothing.
     *
     * Cap from on-device logs (25102RKBEC, 4K photos): prefetches finish in
     * 40-760ms, a tap that missed one decoded for 390-780ms. The prefetch
     * always starts earlier and it is the SAME image the tap would pick, so
     * waiting up to ~0.9s is almost always cheaper than decoding it again -
     * the old 250ms cap timed out on every slower image and made such taps
     * take a full extra decode.
     */
    private suspend fun awaitPrefetchReady() {
        if (!prefetch.isStoreInProgress()) return
        var waited = 0L
        while (waited < PREFETCH_WAIT_MS) {
            if (prefetch.hasCache() || !prefetch.isStoreInProgress()) return
            delay(PREFETCH_WAIT_STEP_MS)
            waited += PREFETCH_WAIT_STEP_MS
        }
    }

    private companion object {
        private val HOME_SLOT = WallpaperTarget.SLOT_HOME

        /** Any → GIF settle delay (see the GIF branch). */
        private const val SWITCH_SETTLE_DELAY_MS = 30L

        /** Bounded retries when the picked media is blocklisted as broken. */
        private const val FAILED_MEDIA_RETRY_LIMIT = 5

        /**
         * 视频播完再切开着时，最多连续丢弃这么多次定时 tick（12 次；按 10 秒
         * 间隔约 2 分钟）就放行一次——那是"视频一直播不完/起不来"的兜底，
         * 否则定时切换会被永久挡住。正常播放时每次 pass 都会把这个计数清零。
         */
        private const val TIMER_DROP_LIMIT = 12

        /** Cap for [awaitPrefetchReady]; see there. */
        private const val PREFETCH_WAIT_MS = 900L
        private const val PREFETCH_WAIT_STEP_MS = 10L
    }
}
