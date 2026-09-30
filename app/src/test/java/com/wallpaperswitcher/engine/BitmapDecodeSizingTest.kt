package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sizing maths of the "decode only the pixels the screen can show" optimization
 * (see [BitmapUtils.displayTargetSize] / [BitmapUtils.decodeScale]).
 *
 * The device-found bug this locks down: BitmapFactory applies the
 * inDensity/inTargetDensity scale ON TOP of inSampleSize, so passing the full
 * source width as inDensity scaled every picture by `sample` too much (a
 * 6048x8064 photo decoded to 978x1304 instead of 1956x2608, which the runtime
 * then reported as "Stale media metadata" and re-decoded).
 */
class BitmapDecodeSizingTest {

    private val screenW = 1200
    private val screenH = 2608

    @Test
    fun fillTargetCoversTheScreenWithoutKeepingSourcePixels() {
        // 48MP photo, FILL on a 1200x2608 screen: cover scale = 2608/8064.
        val target = BitmapUtils.displayTargetSize(6048, 8064, screenW, screenH, ScaleMode.FILL)!!
        assertEquals(1956, target[0])
        assertEquals(2608, target[1])
    }

    @Test
    fun fitTargetShrinksToTheScreenButNeverEnlarges() {
        val target = BitmapUtils.displayTargetSize(6048, 8064, screenW, screenH, ScaleMode.FIT)!!
        assertEquals(1200, target[0])
        assertEquals(1600, target[1])
        // Already smaller than the screen: nothing to gain, keep the source.
        assertNull(BitmapUtils.displayTargetSize(800, 1200, screenW, screenH, ScaleMode.FILL))
    }

    @Test
    fun decodeScaleAccountsForInSampleSize() {
        // The real case: sample = 2 already halved 6048 to 3024, so the density
        // pair must be 3024 -> 1956 (ratio 0.6468), NOT 6048 -> 1956 (which
        // would have produced 978).
        assertEquals(3024 to 1956, BitmapUtils.decodeScale(srcW = 6048, sample = 2, targetW = 1956))
        // 6048 / 2 * (1956 / 3024) == 1956: the wanted output size.
        assertEquals(1956, 6048 / 2 * 1956 / 3024)
    }

    @Test
    fun decodeScaleIsSkippedWhenNothingWouldBeScaled() {
        assertNull(BitmapUtils.decodeScale(srcW = 6048, sample = 1, targetW = 6048))
        assertNull(BitmapUtils.decodeScale(srcW = 6048, sample = 2, targetW = 3024))
        assertNull(BitmapUtils.decodeScale(srcW = 6048, sample = 2, targetW = null))
    }

    @Test
    fun orientationRuleAppliesToFitAsWell() {
        // Landscape media on a portrait screen: turn it in every mode.
        assertTrue(
            BitmapUtils.wantsQuarterTurn(1920, 1080, screenW, screenH, true)
        )
        assertTrue(
            BitmapUtils.wantsQuarterTurn(1920, 1080, screenW, screenH, true)
        )
        // Already matching, square, or the feature off: leave it alone.
        assertFalse(
            BitmapUtils.wantsQuarterTurn(720, 1280, screenW, screenH, true)
        )
        assertFalse(
            BitmapUtils.wantsQuarterTurn(1000, 1000, screenW, screenH, true)
        )
        assertFalse(
            BitmapUtils.wantsQuarterTurn(1920, 1080, screenW, screenH, false)
        )
    }

    @Test
    fun rotatedTargetsUseTheSwappedDims() {
        // 4000x3000 (landscape) on the 1200x2608 portrait screen.
        // FIT unrotated fits the width (1200x900); turned it fits the height,
        // which is 1.78x more pixels on screen -> the decode target follows.
        val fitPlain = BitmapUtils.displayTargetSize(4000, 3000, screenW, screenH, ScaleMode.FIT)!!
        assertEquals(1200, fitPlain[0])
        assertEquals(900, fitPlain[1])
        val fitTurned = BitmapUtils.displayTargetSize(4000, 3000, screenW, screenH, ScaleMode.FIT, true)!!
        assertEquals(1600, fitTurned[0])
        assertEquals(1200, fitTurned[1])
        // FILL: the unrotated cover is the bigger of the two, so turning the
        // media is also what keeps the decode from overshooting.
        val fillPlain = BitmapUtils.displayTargetSize(4000, 3000, screenW, screenH, ScaleMode.FILL)!!
        // 4000 * (2608/3000) rounded, 3000 * (2608/3000):
        assertEquals(3477, fillPlain[0])
        assertEquals(2608, fillPlain[1])
        val fillTurned = BitmapUtils.displayTargetSize(4000, 3000, screenW, screenH, ScaleMode.FILL, true)!!
        assertEquals(2608, fillTurned[0])
        assertEquals(1956, fillTurned[1])
    }

    @Test
    fun staleMetadataToleranceCoversTheDecoderQuantisation() {
        // Real device: expected 1739x2608, decoded 1584x2376 (-8.9%) and
        // 1499x2250 (-13.8%) - both must pass, otherwise every switch of such a
        // photo logged "Stale media metadata" and re-read it three more times.
        assertTrue(kotlin.math.abs(1739 - 1584) <= BitmapUtils.decodeSizeTolerance(1739))
        assertTrue(kotlin.math.abs(2608 - 2376) <= BitmapUtils.decodeSizeTolerance(2608))
        assertTrue(kotlin.math.abs(1738 - 1499) <= BitmapUtils.decodeSizeTolerance(1738))
        // A really replaced file is off by a factor, not by a few percent.
        assertFalse(kotlin.math.abs(1956 - 776) <= BitmapUtils.decodeSizeTolerance(1956))
    }

    @Test
    fun displaySpanSwapsTheAxesWhenTheMediaIsTurned() {
        // Shared by the image target, the video decode cap and the GIF sizing.
        assertEquals(4000 to 3000, BitmapUtils.displaySpan(4000, 3000, rotated = false))
        assertEquals(3000 to 4000, BitmapUtils.displaySpan(4000, 3000, rotated = true))
    }
}
