package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperGeometryTest {

    private val eps = 1e-4f

    private fun assertNear(expected: Float, actual: Float) {
        assertEquals(expected, actual, eps)
    }

    private fun quadAt(q: FloatArray, index: Int): Pair<Float, Float> =
        q[index * 4] to q[index * 4 + 1]

    private fun uvAt(q: FloatArray, index: Int): Pair<Float, Float> =
        q[index * 4 + 2] to q[index * 4 + 3]

    /** Screen corner (vertex 0 = bottom-left) where the image's [u],[v] lands. */
    private fun cornerOf(q: FloatArray, u: Float, v: Float): String {
        for (i in 0 until 4) {
            val (qu, qv) = uvAt(q, i)
            if (kotlin.math.abs(qu - u) < eps && kotlin.math.abs(qv - v) < eps) {
                val (x, y) = quadAt(q, i)
                return when {
                    x < 0f && y < 0f -> "bottom-left"
                    x > 0f && y < 0f -> "bottom-right"
                    x < 0f && y > 0f -> "top-left"
                    else -> "top-right"
                }
            }
        }
        return "missing"
    }

    @Test
    fun fitLetterboxesPortraitImageOnPortraitScreen() {
        // 4:3 image on a portrait screen (aspect 0.75): FIT leaves top/bottom
        // bars -> quad is narrower than the viewport.
        val q = WallpaperGeometry.computeQuad(
            4000f, 3000f, 1536f, 2048f, ScaleMode.FIT
        )
        assertNear(-1f, q[0])
        assertNear(1f, q[4])
        assertNear(-0.5625f, q[1])
        assertNear(-0.5625f, q[5])
        assertNear(0.5625f, q[9])
        assertFalse(WallpaperGeometry.quadCoversScreen(q))
    }

    @Test
    fun fitCoversScreenWhenAspectMatches() {
        // Same 4:3 image on a landscape screen with the same aspect: fullscreen.
        val q = WallpaperGeometry.computeQuad(
            4000f, 3000f, 2048f, 1536f, ScaleMode.FIT
        )
        assertNear(-1f, q[0])
        assertNear(-1f, q[1])
        assertTrue(WallpaperGeometry.quadCoversScreen(q))
    }

    @Test
    fun fitAfterRotationRecomputesCorrectly() {
        // The exact regression case: a landscape image shown on a portrait
        // screen is letterboxed; when the surface rotates to landscape the
        // same image must re-fit to fullscreen.
        val portrait = WallpaperGeometry.computeQuad(
            4000f, 3000f, 1536f, 2048f, ScaleMode.FIT
        )
        val landscape = WallpaperGeometry.computeQuad(
            4000f, 3000f, 2048f, 1536f, ScaleMode.FIT
        )
        assertFalse(WallpaperGeometry.quadCoversScreen(portrait))
        assertTrue(WallpaperGeometry.quadCoversScreen(landscape))
    }

    @Test
    fun fillAlwaysCoversScreen() {
        val landscapeImage = WallpaperGeometry.computeQuad(
            4000f, 3000f, 1536f, 2048f, ScaleMode.FILL
        )
        assertTrue(WallpaperGeometry.quadCoversScreen(landscapeImage))

        val portraitImage = WallpaperGeometry.computeQuad(
            3000f, 4000f, 2048f, 1536f, ScaleMode.FILL
        )
        assertTrue(WallpaperGeometry.quadCoversScreen(portraitImage))
        // Portrait image on a landscape screen crops top/bottom: dh > 1.
        assertTrue(kotlin.math.abs(portraitImage[5]) > 1f)
    }

    @Test
    fun stretchAlwaysCoversScreen() {
        val q = WallpaperGeometry.computeQuad(
            100f, 5000f, 1536f, 2048f, ScaleMode.STRETCH
        )
        assertTrue(WallpaperGeometry.quadCoversScreen(q))
        assertNear(-1f, q[0])
        assertNear(-1f, q[1])
        assertNear(1f, q[4])
        assertNear(-1f, q[5])
    }

    @Test
    fun invalidSizesReturnFullscreenFallback() {
        val q = WallpaperGeometry.computeQuad(0f, 0f, 1536f, 2048f, ScaleMode.FIT)
        assertTrue(WallpaperGeometry.quadCoversScreen(q))
    }

    @Test
    fun videoQuadUsesSurfaceTextureTexcoords() {
        // Video texcoords are flipped vs image texcoords: v=0 at the bottom.
        val image = WallpaperGeometry.computeQuad(
            4000f, 3000f, 2048f, 1536f, ScaleMode.FIT
        )
        val video = WallpaperGeometry.computeVideoQuad(
            4000f, 3000f, 2048f, 1536f, ScaleMode.FIT
        )
        assertNear(0f, image[2])   // image bottom-left u
        assertNear(1f, image[3])   // image bottom-left v
        assertNear(0f, video[2])   // video bottom-left u
        assertNear(0f, video[3])   // video bottom-left v
        // Same geometry: positions match vertex for vertex.
        for (i in 0 until 4) {
            val (qx, qy) = quadAt(image, i)
            val (vx, vy) = quadAt(video, i)
            assertNear(qx, vx)
            assertNear(qy, vy)
        }
    }

    // ---- FILL/STRETCH "rotate the image 90° to match the screen" ----

    @Test
    fun clockwiseRotationSwapsTheAspect() {
        // 2:1 landscape image on a 1:2 portrait screen, FILL: the rotated quad
        // must be portrait (taller than wide), i.e. it covers the viewport.
        val q = WallpaperGeometry.computeQuad(
            2000f, 1000f, 1440f, 3200f, ScaleMode.FILL, rotateCw = true
        )
        assertTrue(WallpaperGeometry.quadCoversScreen(q))
        // FIT of the rotated 1:2 content on a 1440x3200 (0.45) screen: the
        // width fills the viewport and the height becomes (0.45 / 0.5) = 0.9 of
        // the half-height, i.e. the same 0.5 aspect in screen space.
        val fit = WallpaperGeometry.computeQuad(
            2000f, 1000f, 1440f, 3200f, ScaleMode.FIT, rotateCw = true
        )
        val (blx, bly) = quadAt(fit, 0)
        assertNear(-1f, blx)     // fills the width (vertex 0 is bottom-left)...
        assertNear(0.9f, -bly)   // ...and keeps the rotated 1:2 aspect
    }

    @Test
    fun clockwiseRotationMapsImageCornersClockwise() {
        val q = WallpaperGeometry.computeQuad(
            2000f, 1000f, 1440f, 3200f, ScaleMode.FILL, rotateCw = true
        )
        assertEquals("top-right", cornerOf(q, 0f, 0f))     // image top-left
        assertEquals("bottom-right", cornerOf(q, 1f, 0f))  // image top-right
        assertEquals("bottom-left", cornerOf(q, 1f, 1f))   // image bottom-right
        assertEquals("top-left", cornerOf(q, 0f, 1f))      // image bottom-left
    }

    @Test
    fun counterClockwiseRotationMapsImageCornersCounterClockwise() {
        val q = WallpaperGeometry.computeQuad(
            2000f, 1000f, 1440f, 3200f, ScaleMode.FILL, rotateCw = false
        )
        assertEquals("bottom-left", cornerOf(q, 0f, 0f))
        assertEquals("top-left", cornerOf(q, 1f, 0f))
        assertEquals("top-right", cornerOf(q, 1f, 1f))
        assertEquals("bottom-right", cornerOf(q, 0f, 1f))
    }

    @Test
    fun rotatedQuadStillUsesAllFourTexels() {
        for (cw in listOf(true, false)) {
            val q = WallpaperGeometry.computeQuad(
                4000f, 3000f, 1536f, 2048f, ScaleMode.STRETCH, rotateCw = cw
            )
            val corners = (0 until 4).map { uvAt(q, it) }.toSet()
            assertEquals(
                setOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f),
                corners
            )
        }
    }

    @Test
    fun noRotationKeepsThePlainQuad() {
        val plain = WallpaperGeometry.computeQuad(4000f, 3000f, 1536f, 2048f, ScaleMode.FILL)
        val explicit = WallpaperGeometry.computeQuad(
            4000f, 3000f, 1536f, 2048f, ScaleMode.FILL, rotateCw = null
        )
        assertTrue(plain.contentEquals(explicit))
    }

    // --- bitmapCoversQuad: "is the decoded bitmap big enough, or must the media
    // be decoded again?" (the MIUI picker's double surface turn, see docs 4.9.39)

    @Test
    fun coversQuadRequiresTheBitmapToFillItsOnScreenFootprint() {
        // 1:1 FIT on a matching screen: enough.
        assertTrue(
            WallpaperGeometry.bitmapCoversQuad(1080, 1920, 1080, 1920, ScaleMode.FIT, null)
        )
        // Same aspect but decoded for a SMALLER screen: on this one it would be
        // shown at 1080x1920 px, i.e. upscaled - decode it again (that is the
        // target of the display-aware decode).
        assertFalse(
            WallpaperGeometry.bitmapCoversQuad(540, 960, 1080, 1920, ScaleMode.FIT, null)
        )
        // A 2% quantisation slack must not force a pointless decode.
        assertTrue(
            WallpaperGeometry.bitmapCoversQuad(1065, 1893, 1080, 1920, ScaleMode.FIT, null)
        )
    }

    @Test
    fun coversQuadSwapsTheAxesWhenTheMediaIsTurned90Degrees() {
        // The tablet case from docs 4.9.39: a 3840x2559 photo on a 2136x3200
        // (portrait) surface with auto-rotation ON. Turned 90°, its HEIGHT feeds
        // the screen's width, so it covers the quad - comparing bitmaps and
        // screen on the same axis (the old bug) rejected it and re-decoded a
        // 39MB photo on every orientation change.
        assertTrue(
            WallpaperGeometry.bitmapCoversQuad(3840, 2559, 2136, 3200, ScaleMode.FIT, true)
        )
        assertTrue(
            WallpaperGeometry.bitmapCoversQuad(3840, 2559, 2136, 3200, ScaleMode.FIT, false)
        )
        // Without the turn the quad is much wider than the photo is tall, so the
        // same bitmap genuinely IS too small and must be decoded again.
        assertFalse(
            WallpaperGeometry.bitmapCoversQuad(1920, 1279, 3200, 2136, ScaleMode.FIT, null)
        )
        // The turned 1920x1279 is still too small for a 3200x2136 landscape
        // surface (its height feeds the 3200 width): re-decode stays correct.
        assertFalse(
            WallpaperGeometry.bitmapCoversQuad(1920, 1279, 3200, 2136, ScaleMode.FIT, true)
        )
    }

    @Test
    fun coversQuadFollowsFillCropping() {
        // FILL crops: the quad is larger than the screen on one axis, so the
        // bitmap has to be at least that large.
        assertTrue(
            WallpaperGeometry.bitmapCoversQuad(4000, 3000, 1080, 1920, ScaleMode.FILL, null)
        )
        assertFalse(
            WallpaperGeometry.bitmapCoversQuad(1000, 4000, 1080, 1920, ScaleMode.FILL, null)
        )
    }

    @Test
    fun coversQuadRejectsDegenerateInput() {
        assertFalse(WallpaperGeometry.bitmapCoversQuad(0, 100, 1080, 1920, ScaleMode.FIT, null))
        assertFalse(WallpaperGeometry.bitmapCoversQuad(100, 100, 0, 0, ScaleMode.FIT, null))
    }
}
