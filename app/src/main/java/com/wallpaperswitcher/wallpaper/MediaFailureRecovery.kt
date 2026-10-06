package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.dropGoneMedia
import com.wallpaperswitcher.util.AppLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 媒体失败恢复：坏视频 / 坏 GIF / 解不开的图片要"记账 + 换一张"，以及失效文件的
 * 清理与"类型存错了"的修复，从 `LiveWallpaperService` 拆分出来（第 22 轮解构）。
 *
 * 以前这套规则散在引擎的 `failedMediaIds` / `recoveryFailCount` 两个字段和各条
 * 失败分支里（视频、GIF、图片各写一份，恢复次数的上限判断也各写一遍）。现在
 * 统一在这里：谁失败、失败几次、要不要换、什么时候把 id 从黑名单里放出来。
 */
internal class MediaFailureRecovery(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val tag: String,
    private val host: Host,
) {

    internal interface Host {
        fun lastDisplayedId(): Long
        fun setLastDisplayedId(id: Long)
        fun engineDestroyed(): Boolean
        fun isVisible(): Boolean
        fun surfaceReady(): Boolean
        fun switchInProgress(): Boolean
        fun renderer(): WallpaperRenderer?
        fun currentScaleMode(): ScaleMode
        fun mainHandler(): Handler

        /** 给"图片→视频"过渡留的那张图已经没有后继了，可以回收。 */
        fun releaseRetiredBitmap()

        /** 视频起不来时把首帧当成静态图铺上，并记住它属于哪个媒体。 */
        fun presentStillFrame(frame: Bitmap, mediaId: Long)

        fun loadFirstFrame(uri: String, positionUs: Long): Bitmap?

        /** 请求一次"换一张"的恢复切换（groupId > 0 = 留在该分组内）。 */
        fun requestRecoverySwitch(groupId: Long)
    }

    // Media ids that recently failed to start (e.g. a video with no video
    // track). Recovery switches skip these so one broken file can never
    // freeze the wallpaper until the next timer tick.
    private val failedMediaIds = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var recoveryFailCount = 0

    /** How many failures in a row may still trigger automatic recovery. */
    private val maxRecoveryFailures = 5

    fun isFailed(mediaId: Long): Boolean = mediaId in failedMediaIds

    fun failCount(): Int = recoveryFailCount

    /** 播放健康：把"连续失败"计数清零（黑名单不动，见 [noteAppliedHealthy]）。 */
    fun resetFailCount() {
        recoveryFailCount = 0
    }

    /**
     * A video failed to start (e.g. it was superseded by an even newer switch,
     * or the GL resources were torn down concurrently). `lastDisplayedId` must be
     * reset, otherwise the engine thinks the new media is already on screen and
     * the previous video's last frame stays frozen forever. A delayed redraw
     * retries the current media if nothing else started playing.
     */
    fun onVideoStartFailed() {
        AppLog.w(tag, "Video failed to start; scheduling recovery switch")
        // The image kept for the image->video transition has no successor to
        // wait for any more.
        host.releaseRetiredBitmap()
        val failedId = host.lastDisplayedId()
        if (failedId > 0L) failedMediaIds.add(failedId)
        // Same permanent-failure cleanup as the image and GIF paths: a video
        // whose provider entry is really gone has to be dropped from the
        // library, otherwise every new engine session re-picks it and fails
        // again. The tablet log showed exactly that: "Fleurdelys.mov"
        // (uri .../video/media/89951, which MediaStore no longer had) failed
        // with `No item at ...` while its five dead IMAGE siblings were all
        // removed by the image path. The probe only deletes rows whose file
        // is genuinely gone, so a video that merely lost a race with a newer
        // switch is kept.
        if (failedId > 0L) dropMediaIfGone(failedId)
        host.setLastDisplayedId(0L)
        recoveryFailCount++
        if (recoveryFailCount > maxRecoveryFailures) {
            AppLog.w(tag, "Too many consecutive video failures; pausing auto-recovery")
            return
        }
        scope.launch {
            // Non-Xiaomi devices can fail to produce video frames at all
            // (codec/SurfaceTexture quirks). Show the file's first frame
            // instead of leaving a black wallpaper, then fall back to the
            // normal recovery switch only if even that fails.
            val frame = try {
                if (failedId > 0L) {
                    db.wallpaperImageDao().getImageById(failedId)?.let {
                        host.loadFirstFrame(it.uri, 0L)
                    }
                } else null
            } catch (_: Exception) {
                null
            }
            if (host.engineDestroyed()) {
                if (frame != null && !frame.isRecycled) frame.recycle()
                return@launch
            }
            if (frame != null && host.renderer()?.isVideoPlaying != true) {
                host.mainHandler().post {
                    if (host.engineDestroyed()) {
                        if (!frame.isRecycled) frame.recycle()
                        return@post
                    }
                    host.presentStillFrame(frame, failedId)
                    AppLog.d(tag, "Video fallback first frame shown: ${frame.width}x${frame.height}")
                }
                return@launch
            }
            if (frame != null && !frame.isRecycled) frame.recycle()
            delay(1500L)
            if (host.isVisible() && host.surfaceReady() && !host.switchInProgress() &&
                host.renderer()?.isVideoPlaying != true
            ) {
                // Switch to a DIFFERENT media instead of retrying the same
                // broken file: RANDOM pick excludes the failed LAST_IMAGE_ID
                // and the failedMediaIds blocklist covers sequential/shuffle.
                host.requestRecoverySwitch(0L)
            }
        }
    }

    /**
     * A GIF failed to decode (both the animated drawable and the static
     * fallback). Mirrors the IMAGE failure path: blocklist the id and
     * schedule a recovery switch so a broken GIF never leaves the wallpaper
     * blank until the next timer tick.
     */
    fun onGifFailed(mediaId: Long) {
        if (mediaId <= 0L) return
        failedMediaIds.add(mediaId)
        if (host.lastDisplayedId() == mediaId) host.setLastDisplayedId(0L)
        recoveryFailCount++
        // Same permanent-failure cleanup as the image path: a GIF whose file
        // is gone must not be retried on every future timer tick.
        dropMediaIfGone(mediaId)
        if (recoveryFailCount <= maxRecoveryFailures) {
            AppLog.w(tag, "GIF failed to load (id=$mediaId), scheduling recovery switch")
            host.requestRecoverySwitch(0L)
        }
    }

    /**
     * An image/video that could not be loaded: blocklist it, count the failure
     * and ask for a recovery switch (bounded like the video path).
     *
     * [groupId] > 0 keeps the recovery inside the group that owns the screen: a
     * screen-wide recovery could otherwise jump to a media the group's filter
     * excludes.
     */
    fun noteFailed(mediaId: Long, groupId: Long) {
        failedMediaIds.add(mediaId)
        recoveryFailCount++
        // Permanently gone (deleted / moved / permission revoked): drop the row
        // so future sessions and timer ticks stop re-reading a dead URI.
        dropMediaIfGone(mediaId)
        if (recoveryFailCount <= maxRecoveryFailures) {
            host.requestRecoverySwitch(groupId)
        }
    }

    /**
     * A healthy media was applied: drop THIS id from the failure blocklist so a
     * previously-broken file can be retried later (the old `id !in failedMediaIds`
     * guard made the cleanup dead code exactly when the current media was the
     * previously-broken one).
     *
     * GIF is excluded: its decode is asynchronous (GifPlaybackController), so
     * success can only be known when the animation actually starts —
     * [markSuccess] handles that. Without this exclusion, every broken GIF
     * reset the recovery counter synchronously and the engine would
     * auto-recover forever when ALL GIFs in a group are broken.
     */
    fun noteAppliedHealthy(mediaId: Long, mediaType: String) {
        if (mediaType == MediaTypes.GIF) return
        failedMediaIds.remove(mediaId)
        if (failedMediaIds.isEmpty()) recoveryFailCount = 0
    }

    /**
     * A GIF actually started rendering (animated or static fallback).
     * Un-blocklist the id and reset the recovery counter so auto-recovery
     * can kick in again for genuinely broken files.
     */
    fun markSuccess(mediaId: Long) {
        if (mediaId <= 0L) return
        failedMediaIds.remove(mediaId)
        recoveryFailCount = 0
    }

    /**
     * Ask the content provider for the item's real MIME type and repair the
     * stored media type when the two disagree (a video stored as IMAGE - the
     * "video shows black" bug caused by SAF's extension-less display names).
     * Returns true when the row was repaired, so the caller can redraw the
     * now-correctly-typed media.
     */
    suspend fun repairMisTypedMedia(image: WallpaperImage): Boolean {
        val mime = MediaTypes.mimeOf(context, Uri.parse(image.uri))
        // null when the row is already motion media or the provider has
        // nothing better to say (see MediaTypes.repairFromMime).
        val repaired = MediaTypes.repairFromMime(image.mediaType, mime) ?: return false
        return try {
            db.wallpaperImageDao().updateMediaType(image.id, repaired)
            AppLog.w(
                tag,
                "Repaired mis-typed media ${image.displayName}: ${image.mediaType} -> $repaired"
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Delete a media row whose file is permanently gone (see
     * [com.wallpaperswitcher.engine.MediaProbe.isGone]) and rewind the home
     * cursor when it pointed at that row, so neither the timer nor a later
     * session can pick a dead entry again. Runs on the engine's IO scope: it is
     * called from load failures, never on the main thread.
     */
    private fun dropMediaIfGone(mediaId: Long) {
        if (mediaId <= 0L) return
        scope.launch {
            try {
                // Shared with the static applier and the UI delete paths, so
                // all of them forget the same cursors (see dropGoneMedia).
                val image = db.wallpaperImageDao().getImageById(mediaId) ?: return@launch
                dropGoneMedia(context, image, tag)
            } catch (t: Throwable) {
                AppLog.e(tag, "Failed to drop unreadable media id=$mediaId", t)
            }
        }
    }
}
