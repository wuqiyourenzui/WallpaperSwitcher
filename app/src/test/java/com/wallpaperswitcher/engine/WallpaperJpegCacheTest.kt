package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions behind the static applier's encoded-wallpaper cache
 * (see [WallpaperApplier.jpegCacheKey] and [WallpaperApplier.isCachedJpegUsable]).
 *
 * The cache skips a full-resolution decode + JPEG encode on a repeated write, so
 * a key that is too coarse would serve the WRONG pixels (e.g. after the user
 * flips the auto-rotate direction) - that is what these tests pin down.
 */
class WallpaperJpegCacheTest {

    private val uri = "content://media/external/images/media/42"
    private val ttl = 1_000L

    @Test
    fun keyIsStableForTheSameInputs() {
        assertEquals(
            WallpaperApplier.jpegCacheKey(uri, true, true, 0, 1000, 2000),
            WallpaperApplier.jpegCacheKey(uri, true, true, 0, 1000, 2000)
        )
    }

    @Test
    fun keyChangesWithEveryInputThatChangesThePixels() {
        val base = WallpaperApplier.jpegCacheKey(uri, true, true, 0, 1000, 2000)
        // 自动旋转适配 on/off ...
        assertNotEquals(base, WallpaperApplier.jpegCacheKey(uri, false, true, 0, 1000, 2000))
        // ... its direction ...
        assertNotEquals(base, WallpaperApplier.jpegCacheKey(uri, true, false, 0, 1000, 2000))
        // ... the stored EXIF rotation (a replaced file) ...
        assertNotEquals(base, WallpaperApplier.jpegCacheKey(uri, true, true, 90, 1000, 2000))
        // ... the screen the image is decoded for ...
        assertNotEquals(base, WallpaperApplier.jpegCacheKey(uri, true, true, 0, 2000, 1000))
        // ... and the media itself.
        assertNotEquals(
            base,
            WallpaperApplier.jpegCacheKey("content://media/external/images/media/43", true, true, 0, 1000, 2000)
        )
    }

    @Test
    fun freshEntryForAnUnchangedFileIsUsable() {
        assertTrue(
            WallpaperApplier.isCachedJpegUsable(
                cacheSourceW = 4000, cacheSourceH = 3000,
                rowW = 4000, rowH = 3000,
                ageMs = 10L, ttlMs = ttl
            )
        )
    }

    @Test
    fun entryOlderThanTheTtlIsRejected() {
        assertFalse(
            WallpaperApplier.isCachedJpegUsable(
                cacheSourceW = 4000, cacheSourceH = 3000,
                rowW = 4000, rowH = 3000,
                ageMs = ttl + 1, ttlMs = ttl
            )
        )
        // Exactly at the TTL it is still served.
        assertTrue(
            WallpaperApplier.isCachedJpegUsable(
                cacheSourceW = 4000, cacheSourceH = 3000,
                rowW = 4000, rowH = 3000,
                ageMs = ttl, ttlMs = ttl
            )
        )
    }

    @Test
    fun replacedFileWithDifferentSourceSizeIsRejected() {
        // The row now describes a different image (album edit / a different file
        // saved over the same URI): the cached bytes are stale.
        assertFalse(
            WallpaperApplier.isCachedJpegUsable(
                cacheSourceW = 4000, cacheSourceH = 3000,
                rowW = 1200, rowH = 1600,
                ageMs = 0L, ttlMs = ttl
            )
        )
    }

    @Test
    fun unknownDimensionsDoNotInvalidateTheEntry() {
        // Rows written by an older build have no stored size (0): the cache is
        // still valid - the TTL is what bounds it then.
        assertTrue(
            WallpaperApplier.isCachedJpegUsable(
                cacheSourceW = 0, cacheSourceH = 0,
                rowW = 4000, rowH = 3000,
                ageMs = 0L, ttlMs = ttl
            )
        )
        assertTrue(
            WallpaperApplier.isCachedJpegUsable(
                cacheSourceW = 4000, cacheSourceH = 3000,
                rowW = 0, rowH = 0,
                ageMs = 0L, ttlMs = ttl
            )
        )
    }
}
