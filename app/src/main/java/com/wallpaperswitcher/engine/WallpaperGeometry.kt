package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
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
     * 过渡动画 travel of the SLIDE mode, in NDC units (2 = one full screen
     * width). A full-width slide starts with the media completely off-screen,
     * i.e. a black first frame; 0.36 (= 18% of the width) slides the picture in
     * over a thin dark strip instead.
     */
    const val TRANSITION_SLIDE_TRAVEL_NDC = 0.36f

    /**
     * Start scale of the ZOOM mode. 0.93 keeps the dark border thin (the old
     * 0.85 left 15% of the screen black on the first frame).
     */
    const val TRANSITION_ZOOM_START_SCALE = 0.93f

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

    /**
     * 过渡动画: transform a quad for the in-flight switch transition.
     *
     * [progress] is 0 at the start of the transition and 1 when it is settled;
     * 1 (or a mode without a geometry animation, e.g. the classic fade) returns
     * the quad unchanged, so the settled state is always the exact FIT / FILL /
     * STRETCH layout.
     *
     * - slide: the new media enters from the right edge;
     * - zoom: it grows from 85% around the centre.
     *
     * Kept here (not in the renderer) so the maths is unit-testable.
     */
    fun applyTransition(quad: FloatArray, mode: String, progress: Float): FloatArray {
        if (progress >= 1f || quad.size < 2) return quad
        val t = progress.coerceIn(0f, 1f)
        return when (mode) {
            SettingsKeys.SWITCH_TRANSITION_SLIDE -> {
                // A short, eased travel instead of a full-screen slide-in: the
                // media never starts fully off-screen (no black first frame).
                val dx = (1f - t) * TRANSITION_SLIDE_TRAVEL_NDC
                for (i in intArrayOf(0, 4, 8, 12)) {
                    if (i < quad.size) quad[i] += dx
                }
                quad
            }
            SettingsKeys.SWITCH_TRANSITION_ZOOM -> {
                val s = TRANSITION_ZOOM_START_SCALE +
                    (1f - TRANSITION_ZOOM_START_SCALE) * t
                for (i in intArrayOf(0, 4, 8, 12)) {
                    if (i < quad.size) quad[i] *= s
                }
                for (i in intArrayOf(1, 5, 9, 13)) {
                    if (i < quad.size) quad[i] *= s
                }
                quad
            }
            else -> quad
        }
    }

    /**
     * 静态图微动效 (Ken Burns): scale the quad around the viewport centre so a
     * still image very slowly zooms in and out.
     *
     * [phase] runs 0..1 over one full cycle; 0 / 1 (and every wrap) leave the
     * quad untouched, so the settled FILL / FIT / STRETCH layout is met exactly
     * once per cycle and a transition that starts on top of it starts from the
     * real layout. Only scaling is applied - translating the quad would expose
     * the black letterbox bands of a FIT layout at the edges.
     */
    fun applyKenBurns(quad: FloatArray, phase: Float, amplitude: Float): FloatArray {
        if (quad.size < 14 || amplitude <= 0f || !phase.isFinite()) return quad
        // Triangle wave: 0 -> 1 -> 0 over the cycle, so the zoom reverses
        // smoothly instead of jumping back to 1x at the wrap.
        val p = phase - kotlin.math.floor(phase)
        val tri = if (p < 0.5f) p * 2f else (1f - p) * 2f
        val s = 1f + amplitude * tri
        for (i in intArrayOf(0, 4, 8, 12)) if (i < quad.size) quad[i] *= s
        for (i in intArrayOf(1, 5, 9, 13)) if (i < quad.size) quad[i] *= s
        return quad
    }

    /**
     * 画质增强 (超分): how strongly a magnified source should be enhanced.
     *
     * Loosely matches the existing clarity curve (see WallpaperRenderer):
     * nothing below 1.25x (the source already has enough pixels), then a linear
     * ramp that reaches full strength at 4x magnification. The screen-pixel
     * factor follows the scale mode: FIT is limited by the smaller axis, FILL /
     * STRETCH magnify by the larger one.
     */
    fun enhancementStrength(
        sourceW: Float,
        sourceH: Float,
        screenW: Float,
        screenH: Float,
        scaleMode: ScaleMode,
        enabled: Boolean,
    ): Float {
        if (!enabled || sourceW <= 0f || sourceH <= 0f || screenW <= 0f || screenH <= 0f) {
            return 0f
        }
        val scaleX = screenW / sourceW
        val scaleY = screenH / sourceH
        val upscale = when (scaleMode) {
            ScaleMode.FIT -> minOf(scaleX, scaleY)
            ScaleMode.FILL, ScaleMode.STRETCH -> maxOf(scaleX, scaleY)
        }
        return ((upscale - 1.25f) / 2.75f).coerceIn(0f, 1f)
    }

    /**
     * Catmull-Rom weights for the four samples at -1, 0, 1, 2 around [t] in
     * 0..1 (the exact weights the shader's `cubicWeights` mirrors).
     */
    internal fun cubicWeights(t: Float): FloatArray {
        val t2 = t * t
        val t3 = t2 * t
        return floatArrayOf(
            -0.5f * t3 + t2 - 0.5f * t,
            1.5f * t3 - 2.5f * t2 + 1f,
            -1.5f * t3 + 2f * t2 + 0.5f * t,
            0.5f * t3 - 0.5f * t2,
        )
    }

    /**
     * The 1D pair decomposition behind the shader's 4-tap bicubic.
     *
     * Four Catmull-Rom taps can be reproduced with two hardware-bilinear taps:
     * the pair (-1, 0) is fetched at `posA` texels from the texel at -1 with
     * total weight `weightA`, the pair (1, 2) at `posB` from the texel at 1
     * with `weightB`. Returns `[posA, posB, weightA, weightB]`; the shader does
     * the same maths per axis and combines the four 2D taps. Kept here so the
     * decomposition can be unit-tested against [cubicWeights].
     */
    internal fun cubicPairs(t: Float): FloatArray {
        val w = cubicWeights(t)
        val weightA = w[0] + w[1]
        val weightB = w[2] + w[3]
        val posA = if (weightA > 0.0001f) w[1] / weightA else 0.5f
        val posB = if (weightB > 0.0001f) w[3] / weightB else 0.5f
        return floatArrayOf(posA, posB, weightA, weightB)
    }
}
