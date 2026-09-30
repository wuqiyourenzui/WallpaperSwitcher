package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media-kind rules are shared by the scanner, the import path, the static
 * applier and the engine's self-heal - the "video shows as a black wallpaper"
 * bug came from those copies disagreeing, so the rules are pinned here.
 */
class MediaTypesTest {

    @Test
    fun supportedNamesCoverEveryAcceptedExtension() {
        for (ext in MediaTypes.supportedExtensions) {
            assertTrue("$ext should be supported", MediaTypes.isSupportedName("file.$ext"))
        }
        assertTrue(MediaTypes.isSupportedName("PHOTO.JPG"))
        assertFalse(MediaTypes.isSupportedName("file.txt"))
        assertFalse(MediaTypes.isSupportedName("no_extension"))
    }

    @Test
    fun nameClassification() {
        assertEquals(MediaTypes.GIF, MediaTypes.fromName("anim.gif"))
        assertEquals(MediaTypes.GIF, MediaTypes.fromName("ANIM.GIF"))
        assertEquals(MediaTypes.VIDEO, MediaTypes.fromName("clip.mp4"))
        assertEquals(MediaTypes.VIDEO, MediaTypes.fromName("clip.mkv"))
        assertEquals(MediaTypes.VIDEO, MediaTypes.fromName("clip.mov"))
        assertEquals(MediaTypes.IMAGE, MediaTypes.fromName("photo.jpg"))
        assertEquals(MediaTypes.IMAGE, MediaTypes.fromName("photo.heic"))
        // No extension (SAF hands these out on non-Xiaomi devices): IMAGE is the
        // safe default, the provider MIME fixes it up in that case.
        assertEquals(MediaTypes.IMAGE, MediaTypes.fromName("msf:1000000024"))
    }

    @Test
    fun mimeWinsOverTheName() {
        // A video whose display name has no usable extension must not become IMAGE.
        assertEquals(
            MediaTypes.VIDEO,
            MediaTypes.fromMimeOrName("video/mp4", "msf:1000000024")
        )
        assertEquals(MediaTypes.GIF, MediaTypes.fromMimeOrName("image/gif", "weird"))
        assertEquals(MediaTypes.IMAGE, MediaTypes.fromMimeOrName("image/png", "weird"))
        // Useless MIME: fall back to the extension.
        assertEquals(
            MediaTypes.VIDEO,
            MediaTypes.fromMimeOrName("application/octet-stream", "clip.mp4")
        )
        assertEquals(MediaTypes.IMAGE, MediaTypes.fromMimeOrName(null, "photo.jpg"))
    }

    @Test
    fun repairOnlyChangesRowsThatAreActuallyMisTyped() {
        // Stored IMAGE but the provider says video/GIF: the mismatch that made
        // SAF-imported media display black.
        assertEquals(MediaTypes.VIDEO, MediaTypes.repairFromMime(MediaTypes.IMAGE, "video/mp4"))
        assertEquals(MediaTypes.GIF, MediaTypes.repairFromMime(MediaTypes.IMAGE, "image/gif"))
        // Already correct / motion: never rewritten.
        assertNull(MediaTypes.repairFromMime(MediaTypes.VIDEO, "video/mp4"))
        assertNull(MediaTypes.repairFromMime(MediaTypes.GIF, "image/gif"))
        // A still image keeps its type, and an unknown MIME is no evidence.
        assertNull(MediaTypes.repairFromMime(MediaTypes.IMAGE, "image/png"))
        assertNull(MediaTypes.repairFromMime(MediaTypes.IMAGE, null))
    }

    @Test
    fun resolveStoredKeepsMotionMedia() {
        assertEquals(MediaTypes.VIDEO, MediaTypes.resolveStored(MediaTypes.VIDEO, "image/png"))
        assertEquals(MediaTypes.GIF, MediaTypes.resolveStored(MediaTypes.GIF, null))
        assertEquals(MediaTypes.VIDEO, MediaTypes.resolveStored(MediaTypes.IMAGE, "video/hevc"))
        assertEquals(MediaTypes.IMAGE, MediaTypes.resolveStored(MediaTypes.IMAGE, "image/webp"))
        // No MIME: keep what the row already says.
        assertEquals(MediaTypes.IMAGE, MediaTypes.resolveStored(MediaTypes.IMAGE, null))
    }

    @Test
    fun motionMediaIsWhatTheLockScreenMustSkip() {
        assertTrue(MediaTypes.isMotion(MediaTypes.VIDEO))
        assertTrue(MediaTypes.isMotion(MediaTypes.GIF))
        assertFalse(MediaTypes.isMotion(MediaTypes.IMAGE))
    }
}
