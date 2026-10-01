package com.wallpaperswitcher.engine

import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.LogText

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.room.withTransaction
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SettingsDao
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.ShuffleShown
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.WallpaperImageDao
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setString
import com.wallpaperswitcher.data.setLong

/**
 * Applies wallpapers to the system wallpaper via [WallpaperManager].
 *
 * Used in "static wallpaper" mode: when the live wallpaper engine is NOT
 * running, scheduled switches and "set as wallpaper" actions go through here.
 * Videos and GIFs are rendered as their first frame.
 */
object WallpaperApplier {

    private const val TAG = "WallpaperApplier"
    /**
     * The user's "自动旋转适配" preferences, used for static (full-bleed) writes
     * too: the engine reads them on every live switch, and the static path used
     * to hard-code `true/true`, so a lock-screen image ignored the direction the
     * user picked (and a rotated media could be turned the wrong way).
     */
    suspend fun rotatePrefs(dao: SettingsDao): Pair<Boolean, Boolean> {
        val enabled = try {
            dao.getBool(SettingsKeys.ROTATE_MISMATCH_ENABLED, true)
        } catch (_: Exception) {
            true
        }
        val clockwise = try {
            dao.getBool(SettingsKeys.ROTATE_MISMATCH_CW, true)
        } catch (_: Exception) {
            true
        }
        return enabled to clockwise
    }
    // Static-mode media acquisition (video frame extraction / GIF first frame /
    // bitmap decode) can block indefinitely on a stuck cloud/SAF provider. A
    // stuck call used to leave staticApplyInProgress=true forever, silently
    // killing every later timed switch until the process restarted.
    private const val MEDIA_LOAD_TIMEOUT_MS = 15_000L
    /**
     * How long a write to one screen counts as "this media is already there"
     * (see [writtenRecently]). A group with a single media - or a shuffle deck
     * that just wrapped - would otherwise decode and re-apply the identical
     * image on every tick: a full decode plus a WallpaperManager write that
     * changes nothing on screen.
     */
    private const val WRITE_REPEAT_WINDOW_MS = 30 * 60_000L

    /**
     * JPEG quality used for the wallpaper stream handed to
     * [WallpaperManager.setStream] (see [writeWallpaper]).
     *
     * The lock screen displays the picture 1:1 at screen size, and the sources
     * are photos: 95 is visually lossless there while staying far cheaper to
     * encode than the PNG that `setBitmap()` would produce.
     */
    private const val WALLPAPER_JPEG_QUALITY = 95

    /**
     * How many encoded wallpapers are kept for reuse, how large one may be and
     * how long they stay valid (see [CachedJpeg]).
     *
     * Two entries cover the A -> B -> A pattern the timers produce (a small
     * group, a shuffle deck that wraps); the byte cap keeps the whole cache at a
     * few MB, and the TTL bounds how long a file replaced behind its URI can be
     * served from it (the size check below catches that case too).
     */
    private const val JPEG_CACHE_MAX_ENTRIES = 2
    private const val JPEG_CACHE_MAX_ENTRY_BYTES = 4 * 1024 * 1024
    private const val JPEG_CACHE_TTL_MS = 15 * 60_000L

    /**
     * The JPEG bytes a static write handed to [WallpaperManager.setStream], plus
     * the source size they were produced from.
     */
    private class CachedJpeg(
        val bytes: ByteArray,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val createdAt: Long
    )

    /**
     * media+screens size -> encoded wallpaper bytes. Access-ordered LRU.
     *
     * A static switch costs a full-resolution decode, a full-image JPEG encode
     * and (for media with alpha) a full-size ARGB flatten copy; a group with a
     * few images - or a shuffle deck coming around again - repeats exactly those
     * steps for pixels that did not change. Reusing the bytes turns that switch
     * into a single `setStream()` call.
     */
    private val jpegCache = object : LinkedHashMap<String, CachedJpeg>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedJpeg>?
        ): Boolean = size > JPEG_CACHE_MAX_ENTRIES
    }

    /**
     * Cache key of one static write: the media plus every input that changes the
     * encoded pixels (rotation preferences, the stored EXIF rotation and the
     * screen the image was decoded for). Pure, unit-tested.
     */
    internal fun jpegCacheKey(
        uri: String,
        rotateMismatch: Boolean,
        rotateClockwise: Boolean,
        rotationDegrees: Int,
        screenW: Int,
        screenH: Int
    ): String = "$uri|rm=$rotateMismatch|rc=$rotateClockwise|rot=$rotationDegrees|${screenW}x$screenH"

    /**
     * May a cached entry be reused for [rowW]x[rowH]? Pure, unit-tested.
     *
     * The size the bytes were made from is compared against the media row's
     * stored source size: a mismatch means the file behind the URI was replaced
     * (album edit, another image saved over it), which is the same condition
     * [BitmapUtils] re-reads on. A zero means "not stored by an older build" and
     * skips the check.
     */
    internal fun isCachedJpegUsable(
        cacheSourceW: Int,
        cacheSourceH: Int,
        rowW: Int,
        rowH: Int,
        ageMs: Long,
        ttlMs: Long = JPEG_CACHE_TTL_MS
    ): Boolean {
        if (ageMs > ttlMs) return false
        if (rowW > 0 && rowH > 0 && cacheSourceW > 0 && cacheSourceH > 0 &&
            (cacheSourceW != rowW || cacheSourceH != rowH)
        ) {
            return false
        }
        return true
    }

    /** The cached bytes for [key], or null when there is none / it is stale. */
    private fun cachedJpeg(key: String, image: WallpaperImage): ByteArray? {
        val entry = synchronized(jpegCache) { jpegCache[key] } ?: return null
        val age = SystemClock.elapsedRealtime() - entry.createdAt
        val usable = isCachedJpegUsable(
            entry.sourceWidth, entry.sourceHeight, image.width, image.height, age
        )
        if (!usable) {
            synchronized(jpegCache) { jpegCache.remove(key) }
            return null
        }
        return entry.bytes
    }

    /** Remember the bytes just encoded for [key] (bounded, best effort). */
    private fun rememberJpeg(key: String, bytes: ByteArray, image: WallpaperImage) {
        if (bytes.size > JPEG_CACHE_MAX_ENTRY_BYTES) return
        synchronized(jpegCache) {
            jpegCache[key] = CachedJpeg(
                bytes = bytes,
                sourceWidth = image.width,
                sourceHeight = image.height,
                createdAt = SystemClock.elapsedRealtime()
            )
        }
    }

    /**
     * Drop the encoded-wallpaper cache: called on memory pressure (one entry can
     * be a few MB, and the next switch simply re-decodes).
     */
    fun trimMemory() {
        synchronized(jpegCache) { jpegCache.clear() }
    }

    /**
     * Render a single media item as the system wallpaper. Static wallpapers
     * cannot animate, so videos/GIFs are applied as their first frame.
     *
     * @param which [WallpaperManager.FLAG_SYSTEM] (home), [FLAG_LOCK] (lock
     *   screen) or both OR-ed together.
     * @return true when the wallpaper was applied successfully.
     */
    fun apply(
        context: Context,
        image: WallpaperImage,
        which: Int = WallpaperManager.FLAG_SYSTEM,
        rotateMismatch: Boolean = true,
        rotateClockwise: Boolean = true
    ): Boolean {
        // Reuse the bytes of an identical earlier write when possible: this skips
        // the decode, the flatten copy and the JPEG encode, leaving one
        // setStream() call (see jpegCache).
        val metrics = BitmapUtils.getScreenMetrics(context)
        val cacheKey = jpegCacheKey(
            uri = image.uri,
            rotateMismatch = rotateMismatch,
            rotateClockwise = rotateClockwise,
            rotationDegrees = image.rotationDegrees,
            screenW = metrics.widthPixels,
            screenH = metrics.heightPixels
        )
        val cachedBytes = cachedJpeg(cacheKey, image)
        if (cachedBytes != null &&
            writeCachedWallpaper(context, cachedBytes, which, image.displayName)
        ) {
            AppLog.d(
                TAG,
                "Static wallpaper from cache (${cachedBytes.size / 1024}KB): ${image.displayName}"
            )
            return true
        }

        val bitmap = loadMediaBitmapWithTimeout(context, image, rotateMismatch, rotateClockwise)
        if (bitmap == null) {
            AppLog.e(TAG, "No bitmap for: ${image.displayName} uri=${LogText.short(image.uri)}")
            return false
        }

        var applied = false
        try {
            val manager = WallpaperManager.getInstance(context)
            // Diagnostic (same idea as the engine's "Bitmap loaded" line): the
            // lock screen displays this bitmap full-bleed, so its size is what
            // decides whether the result looks sharp or upscaled.
            AppLog.d(
                TAG,
                "Static bitmap: ${bitmap.width}x${bitmap.height} for ${image.displayName}"
            )
            // Only claim success when the framework accepted one of the two
            // writes (see writeWallpaper): `applied` drives both the cursor
            // advance and the "已设为壁纸" toast.
            applied = writeWallpaper(manager, bitmap, which, image.displayName, cacheKey, image)
            if (!applied) {
                AppLog.e(TAG, "Wallpaper write refused: ${image.displayName}")
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "apply failed for ${image.displayName}", e)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        if (applied) {
            val where = when (which) {
                WallpaperManager.FLAG_SYSTEM -> "home"
                WallpaperManager.FLAG_LOCK -> "lock"
                else -> "home+lock"
            }
            AppLog.d(TAG, "Wallpaper applied ($where): ${image.displayName}")
        } else {
            AppLog.e(TAG, "Wallpaper write refused (non-positive id): ${image.displayName}")
        }
        return applied
    }

    /**
     * Hand [bitmap] to the system wallpaper service.
     *
     * `setBitmap()` does not just pass the bitmap along: it PNG-encodes the
     * whole image **in this process** first (`WallpaperManager.setBitmap` ->
     * `fullImage.compress(Bitmap.CompressFormat.PNG, 90, fos)`, AOSP 14 line
     * 2102) and only then streams it to the service. On a 3K tablet that is
     * 2.2-3.5s of CPU per write for a 6.8MP lock wallpaper - measured as ~33%
     * of one core at a 10s lock interval, which is what made the timed lock
     * switch the single most expensive thing this app did (see the power
     * review in docs/TECHNICAL_DOCUMENTATION.md).
     *
     * `setStream()` copies the stream instead, so the same picture goes out as
     * a JPEG that costs a fraction of the encode time (and a fraction of the
     * written file size). The visible result is unchanged: the same bitmap
     * pixels, at the same resolution, in the same wallpaper slot.
     *
     * Any failure on the new path falls back to [WallpaperManager.setBitmap]
     * so a wallpaper write never depends on the encoding succeeding.
     */
    private fun writeWallpaper(
        manager: WallpaperManager,
        bitmap: Bitmap,
        which: Int,
        name: String,
        cacheKey: String,
        image: WallpaperImage
    ): Boolean {
        val jpeg = encodeJpegForWallpaper(bitmap)
        if (jpeg != null) {
            // Keep the bytes for the next identical write (see jpegCache).
            rememberJpeg(cacheKey, jpeg, image)
            try {
                java.io.ByteArrayInputStream(jpeg).use { stream ->
                    manager.setStream(stream, null, true, which)
                }
                return true
            } catch (t: Throwable) {
                AppLog.w(TAG, "setStream failed for $name, falling back to setBitmap", t)
            }
        }
        // setBitmap() reports its result as an Int - the id of the newly set
        // wallpaper - and uses a non-positive value when the framework refused it
        // (it can also throw, which writeCachedWallpaper already handles). The
        // result used to be dropped and `true` returned unconditionally, so a
        // refused write was indistinguishable from a success: the caller advanced
        // the cursor and told the user the wallpaper had been set while the screen
        // never changed.
        return manager.setBitmap(bitmap, null, true, which) > 0
    }

    /**
     * Write already-encoded wallpapers bytes.
     *
     * @return false when the framework refused the stream, so the caller decodes
     *   and re-encodes instead of failing the switch.
     */
    private fun writeCachedWallpaper(
        context: Context,
        bytes: ByteArray,
        which: Int,
        name: String
    ): Boolean = try {
        val manager = WallpaperManager.getInstance(context)
        java.io.ByteArrayInputStream(bytes).use { stream ->
            manager.setStream(stream, null, true, which)
        }
        true
    } catch (t: Throwable) {
        AppLog.w(TAG, "Cached wallpaper write failed for $name; re-decoding", t)
        false
    }

    /**
     * JPEG bytes for [bitmap], or null when the platform encoder refused them
     * (the caller then falls back to `setBitmap`).
     *
     * JPEG has no alpha channel, so a bitmap that carries transparency is first
     * drawn onto opaque black - the colour the wallpaper service composites it
     * over anyway. The encode is timed and logged because it is the step whose
     * cost decides whether a timed switch is cheap or expensive.
     */
    private fun encodeJpegForWallpaper(bitmap: Bitmap): ByteArray? {
        val startedAt = SystemClock.elapsedRealtime()
        var flattened: Bitmap? = null
        return try {
            val source = if (bitmap.hasAlpha()) {
                val target = Bitmap.createBitmap(
                    bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888
                )
                flattened = target
                val canvas = Canvas(target)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                target
            } else {
                bitmap
            }
            val out = java.io.ByteArrayOutputStream(1 shl 19)
            if (!source.compress(Bitmap.CompressFormat.JPEG, WALLPAPER_JPEG_QUALITY, out)) {
                AppLog.w(TAG, "JPEG encoder refused ${bitmap.width}x${bitmap.height}")
                null
            } else {
                val bytes = out.toByteArray()
                AppLog.d(
                    TAG,
                    "Wallpaper JPEG: ${bitmap.width}x${bitmap.height} " +
                        "${bytes.size / 1024}KB in ${SystemClock.elapsedRealtime() - startedAt}ms"
                )
                bytes
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "JPEG encoding failed", t)
            null
        } finally {
            flattened?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Acquire the wallpaper bitmap on a helper thread with a hard timeout.
     * MediaMetadataRetriever / BitmapFactory / ImageDecoder calls cannot be
     * interrupted by coroutine cancellation, so the caller abandons the thread
     * after [MEDIA_LOAD_TIMEOUT_MS]; if it ever finishes, its bitmap is
     * recycled. A timeout returns null and the caller reports failure, keeping
     * the static-switch guard from being stuck forever.
     */
    private fun loadMediaBitmapWithTimeout(
        context: Context,
        image: WallpaperImage,
        rotateMismatch: Boolean,
        rotateClockwise: Boolean
    ): Bitmap? {
        val result = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread({
            try {
                val bmp = when (mediaTypeOf(context, image)) {
                    MediaTypes.VIDEO -> FirstFrame.video(context, image.uri)
                    MediaTypes.GIF -> FirstFrame.gif(context, image.uri)
                    else -> BitmapUtils.loadBitmap(
                        // FILL, not FIT: a static wallpaper (lock screen, or the
                        // home screen while our live wallpaper is not active) is
                        // displayed FULL-BLEED, i.e. cropped to cover the screen.
                        // Decoding at the FIT rect produced a bitmap narrower than
                        // the screen for landscape images, which the lock screen
                        // then upscaled ~2.4x - the "锁屏壁纸显示不清晰" report.
                        context, image.uri, ScaleMode.FILL, null, null,
                        rotateMismatch, rotateClockwise,
                        // Stored decode metadata: one media read instead of three.
                        image.width, image.height, image.rotationDegrees
                    )
                }
                if (abandoned.get()) {
                    if (bmp != null && !bmp.isRecycled) bmp.recycle()
                } else {
                    result.set(bmp)
                    // Timed out between the check above and this store: the caller
                    // already returned null, so nobody else would recycle it.
                    if (abandoned.get()) {
                        val stored = result.getAndSet(null)
                        if (stored != null && !stored.isRecycled) stored.recycle()
                    }
                }
            } catch (_: Throwable) {}
        }, "StaticMediaLoad").apply {
            // Never keep the process alive because a provider is stuck.
            isDaemon = true
            start()
        }
        try {
            thread.join(MEDIA_LOAD_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            abandoned.set(true)
            thread.interrupt()
            return null
        }
        return result.get()
    }

    /**
     * Media type honouring the provider's real MIME type (see
     * [MediaTypes.resolveStored]): a row stored as IMAGE by an older build can
     * actually be a video/GIF (SAF returns extension-less display names on
     * non-Xiaomi devices). Falls back to the stored type when the provider
     * reports nothing useful.
     */
    private fun mediaTypeOf(context: Context, image: WallpaperImage): String {
        return MediaTypes.resolveStored(
            image.mediaType,
            MediaTypes.mimeOf(context, Uri.parse(image.uri))
        )
    }

    /**
     * Pick the next media for one screen (honoring the global switch mode) and
     * apply it there. Call from a background coroutine.
     *
     * [slot] is [WallpaperTarget.SLOT_HOME] or [WallpaperTarget.SLOT_LOCK]: the
     * pick only considers enabled groups whose 应用位置 includes that screen,
     * and the item is written to that screen's wallpaper slot. Each screen has
     * its own cursor / shuffle deck, so home and lock rotate independently.
     *
     * @param which wallpaper slot to write. Defaults to the slot's own screen.
     * @return the id of the media that was applied, or null when nothing could
     *   be applied (no media targets this screen, or the write failed). Use
     *   [applyNextOutcome] when the caller has to tell "written now" from
     *   "already on that screen".
     */
    suspend fun applyNext(
        context: Context,
        slot: String = WallpaperTarget.SLOT_HOME,
        which: Int = if (slot == WallpaperTarget.SLOT_LOCK) {
            WallpaperManager.FLAG_LOCK
        } else {
            WallpaperManager.FLAG_SYSTEM
        }
    ): Long? = applyNextOutcome(context, slot, which).imageId.takeIf { it > 0L }

    /** What one static tick actually did (see [applyNextOutcome]). */
    internal enum class StaticTickOutcome {
        /** A wallpaper was written to the screen. */
        APPLIED,
        /** That exact media was already on the screen: the re-write was skipped. */
        ALREADY_SHOWING,
        /** No enabled media targets this screen. */
        NO_MEDIA,
        /** The media could not be read or written. */
        FAILED
    }

    /**
     * Result of [applyNextOutcome]. [imageId] is > 0 only for [APPLIED] and
     * [ALREADY_SHOWING] (i.e. when the screen really shows that media), so a
     * caller can report "switched" and "was already showing" differently - the
     * log used to claim a switch even when nothing was written.
     */
    internal data class StaticTickResult(
        val outcome: StaticTickOutcome,
        val imageId: Long = 0L
    )

    /**
     * Same as [applyNext], but tells the caller WHY nothing was applied.
     */
    internal suspend fun applyNextOutcome(
        context: Context,
        slot: String = WallpaperTarget.SLOT_HOME,
        which: Int = if (slot == WallpaperTarget.SLOT_LOCK) {
            WallpaperManager.FLAG_LOCK
        } else {
            WallpaperManager.FLAG_SYSTEM
        }
    ): StaticTickResult {
        val db = AppDatabase.getInstance(context)
        // No "are there any enabled groups" query here: every pick query below
        // already filters by enabled group + slot and returns null, so the extra
        // full-table read only duplicated work the timer loop had done (the loop
        // still needs it for its "no enabled groups" stop rule).

        val lockSlot = slot == WallpaperTarget.SLOT_LOCK
        val lastIdKey =
            if (lockSlot) SettingsKeys.LAST_IMAGE_ID_LOCK else SettingsKeys.LAST_IMAGE_ID
        val imageDao = db.wallpaperImageDao()
        val shuffleDao = db.shuffleDao()
        val dao = db.settingsDao()
        val lastId = dao.getLong(lastIdKey)
        val mode = try {
            SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
        } catch (_: Exception) {
            SwitchMode.RANDOM
        }

        // SHUFFLE bookkeeping is persisted here (static mode has no in-memory
        // deck): the shown set is only written back AFTER a successful apply,
        // and the deck resets under exactly the same rule as the live engine.
        // The "all count" memo is NOT written here any more: nothing reads it
        // for the static path (only the live engine's log does, and it keeps its
        // own copy in SHUFFLE_ALL_COUNT).
        //
        // Since schema v6 the deck lives in the `shuffle_shown` table (one row per
        // shown media): reading it is one indexed scan and adding to it is a
        // single tiny INSERT, instead of decoding and re-encoding the whole deck
        // as a comma-separated string on every tick.
        var shuffleDeckCleared = false

        // Honor the global switch mode the same way the live engine does.
        val image = when (mode) {
            SwitchMode.RANDOM -> MediaPick.random(imageDao, slot, lastId)
            SwitchMode.SEQUENTIAL -> {
                val count = imageDao.countByEnabledGroups(slot)
                if (count == 0) null else {
                    // Item-based cursor, identical to the engine: continue after
                    // the last displayed id and wrap to the first when the deck
                    // is exhausted (deleted/disabled media never cause skips).
                    val img = if (lastId > 0L) {
                        imageDao.getSequentialImageFromEnabledGroupsAfter(slot, lastId)
                            ?: imageDao.getFirstFromEnabledGroups(slot)
                    } else {
                        imageDao.getFirstFromEnabledGroups(slot)
                    }
                    img ?: imageDao.getRandomImageFromEnabledGroups(slot)
                }
            }
            SwitchMode.SHUFFLE -> {
                val total = imageDao.countByEnabledGroups(slot)
                if (total == 0) null else {
                    val shown = shuffleDao.getShownIds(slot).toMutableSet()
                    // Only a finished pass restarts (see shouldResetShuffleDeck):
                    // an enabled-set change keeps the pass going, the next pick
                    // simply filters the enabled ids by the shown ones.
                    if (SwitchPicking.shouldResetShuffleDeck(shown.size, total)) {
                        shown.clear()
                        shuffleDeckCleared = true
                    }
                    // Random UNSEEN pick, filtering the slot's id list in memory
                    // (the pre-review implementation, restored on request).
                    var candidate = MediaPick.shuffleUnseen(
                        imageDao = imageDao,
                        slot = slot,
                        shownIds = shown,
                        excludeId = lastId
                    )
                    if (candidate == null) {
                        // The pass is over (or only one media exists): start a
                        // fresh one, like the engine (the pre-review fallback,
                        // restored on request).
                        shown.clear()
                        shuffleDeckCleared = true
                        candidate = imageDao.getRandomImageFromEnabledGroupsExcluding(slot, lastId)
                            ?: imageDao.getRandomImageFromEnabledGroups(slot)
                    }
                    candidate
                }
            }
        } ?: imageDao.getFirstFromEnabledGroups(slot) ?: run {
            // No media targets this screen: leave that wallpaper untouched.
            AppLog.d(TAG, "No media for $slot; skipping")
            return StaticTickResult(StaticTickOutcome.NO_MEDIA)
        }

        // The cursor advances for the media this tick picked, whether or not the
        // write below succeeds: a media that cannot be read must not pin the
        // screen to the same pick for the rest of the pass (the static path has
        // no failure blocklist like the live engine, so leaving the cursor
        // behind would make every following tick retry the same broken file).
        // The media is retried on the next pass; the row itself is kept unless
        // the file is really gone (see dropIfGone).
        dao.setLong(lastIdKey, image.id)
        // If this very media is already on that screen (small library, one-item
        // deck, shuffle wrap) re-decoding and re-writing it changes nothing and
        // costs a full decode + setBitmap.
        if (writtenRecently(dao, slot, image.id)) {
            AppLog.d(
                TAG,
                "Static $slot already shows ${image.displayName}; skipping re-write"
            )
            return StaticTickResult(StaticTickOutcome.ALREADY_SHOWING, image.id)
        }
        val (rotateMismatch, rotateClockwise) = rotatePrefs(dao)
        val applied = apply(context, image, which, rotateMismatch, rotateClockwise)
        if (!applied) {
            // The static (lock/home) loop used to retry the same unreadable
            // media on every timer tick. Drop the row when the file is really
            // gone (deleted/moved/permission revoked); transient failures keep
            // the row so a busy provider or an OOM is not treated as data loss.
            dropIfGone(context, image)
            return StaticTickResult(StaticTickOutcome.FAILED)
        }
        // The cursor advance, the "written" memo and the shuffle row all belong to
        // the same completed switch, so they commit as ONE transaction: the three
        // separate commits this used to be (2-3 settings rows + the deck write on
        // every tick) were pure SQLite journal/fsync work.
        db.withTransaction {
            recordWrite(dao, slot, image.id)
            if (mode == SwitchMode.SHUFFLE && image.id != 0L) {
                // One tiny row (plus one indexed DELETE when the pass restarted)
                // instead of re-writing the whole deck as a comma-separated string.
                if (shuffleDeckCleared) shuffleDao.clearSlot(slot)
                shuffleDao.insertShown(listOf(ShuffleShown(slot = slot, mediaId = image.id)))
            }
        }
        return StaticTickResult(StaticTickOutcome.APPLIED, image.id)
    }

    /**
     * Remember which media was written to [slot], and when. Shared by the timed
     * loops and the manual "set as wallpaper" path so [writtenRecently] sees the
     * truth for both screens (the lock memo used to live only in the switch
     * service).
     */
    internal suspend fun recordWrite(dao: SettingsDao, slot: String, imageId: Long) {
        try {
            val now = System.currentTimeMillis()
            if (slot == WallpaperTarget.SLOT_LOCK) {
                dao.setLong(SettingsKeys.LAST_LOCK_WRITE_ID, imageId)
                dao.setLong(SettingsKeys.LAST_LOCK_WRITE_AT, now)
            } else {
                dao.setLong(SettingsKeys.LAST_HOME_WRITE_ID, imageId)
                dao.setLong(SettingsKeys.LAST_HOME_WRITE_AT, now)
            }
        } catch (_: Exception) {
            // Bookkeeping only: never fail an already-applied wallpaper.
        }
    }

    /** True when [imageId] was written to [slot] within [WRITE_REPEAT_WINDOW_MS]. */
    internal suspend fun writtenRecently(
        dao: SettingsDao,
        slot: String,
        imageId: Long
    ): Boolean {
        if (imageId <= 0L) return false
        return try {
            val idKey =
                if (slot == WallpaperTarget.SLOT_LOCK) SettingsKeys.LAST_LOCK_WRITE_ID
                else SettingsKeys.LAST_HOME_WRITE_ID
            val atKey =
                if (slot == WallpaperTarget.SLOT_LOCK) SettingsKeys.LAST_LOCK_WRITE_AT
                else SettingsKeys.LAST_HOME_WRITE_AT
            val lastId = dao.getLong(idKey, 0L)
            val lastAt = dao.getLong(atKey, 0L)
            lastId == imageId &&
                System.currentTimeMillis() - lastAt < WRITE_REPEAT_WINDOW_MS
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Delete a media row whose file is permanently gone (see
     * [MediaProbe.isGone]). Called from the static switch loop after a failed
     * apply. Shares one implementation with the engine's self-heal and the UI
     * delete paths so every one of them forgets the same cursors (see
     * [dropGoneMedia]).
     */
    private suspend fun dropIfGone(context: Context, image: WallpaperImage) =
        dropGoneMedia(context, image, TAG)

}
