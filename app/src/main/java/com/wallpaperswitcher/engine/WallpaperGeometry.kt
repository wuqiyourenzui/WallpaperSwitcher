package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.ScaleMode
import kotlin.math.abs

/**
 * Pure screen-space quad math for the EGL renderer, kept free of Android
 * dependencies so the FIT/FILL/STRETCH behavior (including rotation) is unit
 * testable. A quad is 4 interleaved [x,y,u,v] vertices around
 * (-dw,-dh)..(dw,dh), where |x|,|y| == 1 means the quad touches the viewport
 * edge (i.e. covers the whole screen).
 */
object WallpaperGeometry {

    /**
     * Quad for a static image. v=0 is the image top (BitmapFactory uploads the
     * first row at v=0 and the renderer maps v=0 to the top vertices).
     *
     * @param rotateCw mirrors the engine's "image/video turned 90° to match the
     *   screen" feature: the pixels are NOT rotated (no full-screen CPU copy),
     *   the quad is emitted for the ROTATED content instead - the aspect is
     *   swapped and the texture coordinates are permuted so the image's
     *   top-left corner lands where a real 90° rotation would put it:
     *   clockwise -> top-right, counter-clockwise -> bottom-left.
     *   null keeps the plain, unrotated quad.
     */
    fun computeQuad(
        imgW: Float,
        imgH: Float,
        screenW: Float,
        screenH: Float,
        scaleMode: ScaleMode,
        rotateCw: Boolean? = null
    ): FloatArray {
        if (imgW <= 0 || imgH <= 0 || screenW <= 0 || screenH <= 0) {
            return floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)
        }
        // Displayed aspect: a rotated image shows its height as the width.
        val va = if (rotateCw == null) imgW / imgH else imgH / imgW
        val sa = screenW / screenH
        val (dw, dh) = when (scaleMode) {
            ScaleMode.FIT -> if (va > sa) Pair(1f, sa / va) else Pair(va / sa, 1f)
            ScaleMode.FILL -> if (va > sa) Pair(va / sa, 1f) else Pair(1f, sa / va)
            ScaleMode.STRETCH -> Pair(1f, 1f)
        }
        return when (rotateCw) {
            null -> floatArrayOf(-dw,-dh,0f,1f, dw,-dh,1f,1f, -dw,dh,0f,0f, dw,dh,1f,0f)
            // Clockwise: image top-left -> screen top-right.
            true -> floatArrayOf(-dw,-dh,1f,1f, dw,-dh,1f,0f, -dw,dh,0f,1f, dw,dh,0f,0f)
            // Counter-clockwise: image top-left -> screen bottom-left.
            false -> floatArrayOf(-dw,-dh,0f,0f, dw,-dh,0f,1f, -dw,dh,1f,0f, dw,dh,1f,1f)
        }
    }

    /**
     * Quad for a video frame (SurfaceTexture texcoords, v=1 at the top).
     * [vidW]/[vidH] are the ON-SCREEN orientation (rotation metadata already
     * applied), not the raw coded size.
     */
    fun computeVideoQuad(
        vidW: Float,
        vidH: Float,
        screenW: Float,
        screenH: Float,
        scaleMode: ScaleMode
    ): FloatArray {
        if (vidW <= 0 || vidH <= 0 || screenW <= 0 || screenH <= 0) {
            return floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)
        }
        val va = vidW / vidH
        val sa = screenW / screenH
        val (dw, dh) = when (scaleMode) {
            ScaleMode.FIT -> if (va > sa) Pair(1f, sa / va) else Pair(va / sa, 1f)
            ScaleMode.FILL -> if (va > sa) Pair(va / sa, 1f) else Pair(1f, sa / va)
            ScaleMode.STRETCH -> Pair(1f, 1f)
        }
        return floatArrayOf(-dw,-dh,0f,0f, dw,-dh,1f,0f, -dw,dh,0f,1f, dw,dh,1f,1f)
    }

    /**
     * Whether a decoded bitmap already provides at least the pixels the quad
     * needs on this screen - i.e. the existing bitmap can simply be re-presented
     * instead of decoding the media again (a tablet photo costs ~39MB / ~144ms
     * per decode, which is what made the MIUI picker's double surface turn
     * stutter, see the docs 4.9.39).
     *
     * A 90° turn swaps the axes TWICE and both have to be accounted for:
     * [computeQuad] derives the aspect from `imgH / imgW` (see `va`), so after a
     * turn the screen's horizontal extent is fed by the bitmap's HEIGHT.
     * Comparing `wd` against the on-screen width regardless of the turn rejects
     * every rotated photo and re-decodes it for nothing - the tablet log in
     * 4.9.39 cannot be produced without this swap.
     *
     * @param slack tolerance for the quantisation of decoded sizes
     *   (`inSampleSize` / density rounding), so an exact match never triggers a
     *   pointless decode.
     */
    fun bitmapCoversQuad(
        wd: Int,
        ht: Int,
        screenW: Int,
        screenH: Int,
        scaleMode: ScaleMode,
        rotateCw: Boolean?,
        slack: Float = 0.98f
    ): Boolean {
        if (wd <= 0 || ht <= 0 || screenW <= 0 || screenH <= 0) return false
        val quad = computeQuad(
            wd.toFloat(), ht.toFloat(), screenW.toFloat(), screenH.toFloat(),
            scaleMode, rotateCw
        )
        // NDC half-extents -> pixels (the full screen is 2 NDC units wide).
        val drawnW = abs(quad[4]) * screenW
        val drawnH = abs(quad[5]) * screenH
        val srcW = if (rotateCw != null) ht else wd
        val srcH = if (rotateCw != null) wd else ht
        return srcW >= drawnW * slack && srcH >= drawnH * slack
    }

    /**
     * Whether a quad (as produced by [computeQuad]/[computeVideoQuad]) covers
     * the whole viewport. FILL/STRETCH (and FIT with a matching aspect) always
     * cover it; a letterboxed FIT quad leaves black gaps.
     */
    fun quadCoversScreen(q: FloatArray): Boolean =
        q.size >= 2 && abs(q[0]) >= 1f && abs(q[1]) >= 1f
}
