package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaScannerTest {

    @Test
    fun escapeLikeEscapesWildcardsAndBackslash() {
        assertEquals("DCIM/Camera", MediaScanner.escapeLike("DCIM/Camera"))
        assertEquals("100\\%\\_done", MediaScanner.escapeLike("100%_done"))
        assertEquals("a\\\\b", MediaScanner.escapeLike("a\\b"))
    }

    @Test
    fun isSupportedMediaAcceptsImagesVideosGifsAndHeic() {
        for (ext in listOf(
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif",
            "mp4", "mkv", "webm", "avi", "mov", "3gp"
        )) {
            assertTrue("$ext should be supported", MediaScanner.isSupportedMedia("file.$ext"))
        }
        assertFalse(MediaScanner.isSupportedMedia("file.txt"))
        assertFalse(MediaScanner.isSupportedMedia("file.exe"))
        assertTrue(MediaScanner.isSupportedMedia("PHOTO.JPG"))
    }

    @Test
    fun detectMediaTypeClassifiesByExtension() {
        assertEquals("GIF", MediaScanner.detectMediaType("anim.gif"))
        assertEquals("VIDEO", MediaScanner.detectMediaType("clip.mp4"))
        assertEquals("VIDEO", MediaScanner.detectMediaType("clip.mkv"))
        assertEquals("VIDEO", MediaScanner.detectMediaType("clip.webm"))
        assertEquals("VIDEO", MediaScanner.detectMediaType("clip.mov"))
        assertEquals("IMAGE", MediaScanner.detectMediaType("photo.jpg"))
        assertEquals("IMAGE", MediaScanner.detectMediaType("photo.heic"))
    }
}
