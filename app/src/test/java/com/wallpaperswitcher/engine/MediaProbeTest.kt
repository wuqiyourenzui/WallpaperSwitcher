package com.wallpaperswitcher.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "is this row permanently gone?" decision drops the user's media rows, so
 * the message classification is pinned: a permission failure must never be
 * mistaken for a deleted file (that deleted 92 rows on the test tablet - every
 * one of them still present in the gallery).
 */
class MediaProbeTest {

    @Test
    fun permissionMessagesAreRecognised() {
        // Thrown by ContentResolver.openInputStream when READ_MEDIA_* is missing.
        assertTrue(
            MediaProbe.looksLikePermissionDenial(
                "com.wallpaperswitcher has no access to content://media/external/images/media/628 forWrite = false"
            )
        )
        assertTrue(MediaProbe.looksLikePermissionDenial("Permission denied"))
        assertTrue(MediaProbe.looksLikePermissionDenial("/data/x.jpg: open failed: EACCES (Permission denied)"))
        assertTrue(MediaProbe.looksLikePermissionDenial("ACCESS_DENIED"))
    }

    @Test
    fun ordinaryMissingFileMessagesStayPermanent() {
        assertFalse(MediaProbe.looksLikePermissionDenial("No such file or directory"))
        assertFalse(MediaProbe.looksLikePermissionDenial("open failed: ENOENT (No such file or directory)"))
        assertFalse(MediaProbe.looksLikePermissionDenial(null))
        assertFalse(MediaProbe.looksLikePermissionDenial(""))
        assertFalse(MediaProbe.looksLikePermissionDenial("unable to open file"))
    }
}
