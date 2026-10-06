package com.wallpaperswitcher.wallpaper

/**
 * All GLSL programs used by [WallpaperRenderer], split out of the renderer so
 * shader tweaking (FSR1 / Anime4K / FXAA / denoise branches) does not touch
 * the rendering code. Verbatim copies of the previous companion constants.
 */
internal object GlShaders {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val IMAGE_FRAGMENT_SHADER = """
            // 画质增强 needs highp: the bicubic weights are the FRACTION of a
            // texel coordinate that reaches a few thousand, and mediump (often
            // fp16 on mobile) quantises that fraction away - which showed up as
            // heavy aliasing/jaggies. Falls back to mediump only on the rare
            // GLES2 device without highp (the program then still compiles).
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            varying highp vec2 vTexCoord;
            #else
            precision mediump float;
            varying mediump vec2 vTexCoord;
            #endif
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform vec2 uSrcTexel;
            uniform float uSharp;
            uniform float uEnhance;
            uniform float uDenoise;
            uniform float uEnhanceMode;
            uniform vec2 uEasuScale;
            uniform float uAlpha;

            vec4 cubicWeights(float t) {
                float t2 = t * t;
                float t3 = t2 * t;
                return vec4(
                    -0.5 * t3 + t2 - 0.5 * t,
                     1.5 * t3 - 2.5 * t2 + 1.0,
                    -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                     0.5 * t3 - 0.5 * t2
                );
            }

            // 画质增强: Catmull-Rom bicubic with 4 hardware-bilinear taps. The
            // pair decomposition mirrors WallpaperGeometry.cubicPairs (unit
            // tested); it only runs while a low-res source is magnified, so the
            // default path keeps its original cost.
            vec4 bicubic4(vec2 uv, vec2 texel) {
                vec2 c = uv / texel;
                vec2 i0 = floor(c);
                vec2 t = c - i0;
                vec4 wx = cubicWeights(t.x);
                vec4 wy = cubicWeights(t.y);
                float wAx = wx.x + wx.y;
                float wBx = wx.z + wx.w;
                float wAy = wy.x + wy.y;
                float wBy = wy.z + wy.w;
                float pAx = wAx > 0.0001 ? wx.y / wAx : 0.5;
                float pBx = wBx > 0.0001 ? wx.w / wBx : 0.5;
                float pAy = wAy > 0.0001 ? wy.y / wAy : 0.5;
                float pBy = wBy > 0.0001 ? wy.w / wBy : 0.5;
                vec2 lo = texel * 0.5;
                vec2 hi = vec2(1.0) - lo;
                vec4 acc = vec4(0.0);
                acc += (wAx * wAy) * texture2D(uTexture, clamp(vec2(i0.x - 1.0 + pAx, i0.y - 1.0 + pAy) * texel, lo, hi));
                acc += (wBx * wAy) * texture2D(uTexture, clamp(vec2(i0.x + 1.0 + pBx, i0.y - 1.0 + pAy) * texel, lo, hi));
                acc += (wAx * wBy) * texture2D(uTexture, clamp(vec2(i0.x - 1.0 + pAx, i0.y + 1.0 + pBy) * texel, lo, hi));
                acc += (wBx * wBy) * texture2D(uTexture, clamp(vec2(i0.x + 1.0 + pBx, i0.y + 1.0 + pBy) * texel, lo, hi));
                return acc;
            }

            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            float dist2(vec3 a, vec3 b) { vec3 d = a - b; return dot(d, d); }

            // ===== 画质增强的放大算法（只在 uEnhanceMode > 0 时使用）=====
            //
            // FSR1 EASU: port of AMD FidelityFX FSR 1.0 [EASU] (MIT). The
            // reference gathers 2x2 quads with textureGather; the same 12
            // texels are addressed directly here, so it runs in the existing
            // GLES2 single pass. [RCAS] below is the single-pass adaptation.
            //
            // Anime4K: single-pass port of "Upscale: Original x2" from
            // bloc97/Anime4K v4 (MIT): luma sobel -> polynomial refinement
            // value -> blend along the gradient direction.
            float easuLuma(vec4 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }

            void easuSet(inout vec2 dir, inout float len, float w,
                         float lA, float lB, float lC, float lD, float lE) {
                float lenX = max(abs(lD - lC), abs(lC - lB));
                lenX = 1.0 / max(lenX, 1.0 / 32768.0);
                float dirX = lD - lB;
                dir.x += dirX * w;
                lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
                len += lenX * lenX * w;
                float lenY = max(abs(lE - lC), abs(lC - lA));
                lenY = 1.0 / max(lenY, 1.0 / 32768.0);
                float dirY = lE - lA;
                dir.y += dirY * w;
                lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
                len += lenY * lenY * w;
            }

            void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len,
                         float lob, float clp, vec3 c) {
                vec2 v = vec2(off.x * dir.x + off.y * dir.y,
                              off.x * (-dir.y) + off.y * dir.x);
                v *= len;
                float d2 = min(dot(v, v), clp);
                float wB = (2.0 / 5.0) * d2 - 1.0;
                float wA = lob * d2 - 1.0;
                wB *= wB;
                wA *= wA;
                wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
                float w = wB * wA;
                aC += c * w;
                aW += w;
            }

            vec4 easuSample(vec2 uv, vec2 texel, vec2 scale) {
                vec2 ip = uv / (texel * max(scale, vec2(0.0001)));
                vec2 pp = ip * scale + (0.5 * scale - 0.5);
                vec2 fp = floor(pp);
                pp -= fp;
                vec2 slo = texel * 0.5;
                vec2 shi = vec2(1.0) - slo;
                // 12-tap kernel:  b c / e f g h / i j k l / n o.
                vec4 tB = texture2D(uTexture, clamp((fp + vec2( 0.0, -1.0)) * texel, slo, shi));
                vec4 tC = texture2D(uTexture, clamp((fp + vec2( 1.0, -1.0)) * texel, slo, shi));
                vec4 tE = texture2D(uTexture, clamp((fp + vec2(-1.0,  0.0)) * texel, slo, shi));
                vec4 tF = texture2D(uTexture, clamp(fp * texel, slo, shi));
                vec4 tG = texture2D(uTexture, clamp((fp + vec2( 1.0,  0.0)) * texel, slo, shi));
                vec4 tH = texture2D(uTexture, clamp((fp + vec2( 2.0,  0.0)) * texel, slo, shi));
                vec4 tI = texture2D(uTexture, clamp((fp + vec2(-1.0,  1.0)) * texel, slo, shi));
                vec4 tJ = texture2D(uTexture, clamp((fp + vec2( 0.0,  1.0)) * texel, slo, shi));
                vec4 tK = texture2D(uTexture, clamp((fp + vec2( 1.0,  1.0)) * texel, slo, shi));
                vec4 tL = texture2D(uTexture, clamp((fp + vec2( 2.0,  1.0)) * texel, slo, shi));
                vec4 tN = texture2D(uTexture, clamp((fp + vec2( 0.0,  2.0)) * texel, slo, shi));
                vec4 tO = texture2D(uTexture, clamp((fp + vec2( 1.0,  2.0)) * texel, slo, shi));
                float bL = easuLuma(tB); float cL = easuLuma(tC);
                float eL = easuLuma(tE); float fL = easuLuma(tF);
                float gL = easuLuma(tG); float hL = easuLuma(tH);
                float iL = easuLuma(tI); float jL = easuLuma(tJ);
                float kL = easuLuma(tK); float lL = easuLuma(tL);
                float nL = easuLuma(tN); float oL = easuLuma(tO);
                vec2 dir = vec2(0.0);
                float len = 0.0;
                easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
                easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
                easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
                easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
                float dirR = dot(dir, dir);
                if (dirR < (1.0 / 32768.0)) {
                    dir = vec2(1.0, 0.0);
                } else {
                    dir *= inversesqrt(dirR);
                }
                len *= 0.5;
                len *= len;
                float stretch = dot(dir, dir) *
                    (1.0 / max(max(abs(dir.x), abs(dir.y)), 0.0001));
                vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
                float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
                float clp = 1.0 / max(lob, 0.0001);
                vec3 min4 = min(min(tF.rgb, tG.rgb), min(tJ.rgb, tK.rgb));
                vec3 max4 = max(max(tF.rgb, tG.rgb), max(tJ.rgb, tK.rgb));
                vec3 aC = vec3(0.0);
                float aW = 0.0;
                easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, tB.rgb);
                easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, tC.rgb);
                easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, tI.rgb);
                easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, tJ.rgb);
                easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, tF.rgb);
                easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, tE.rgb);
                easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, tK.rgb);
                easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, tL.rgb);
                easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, tH.rgb);
                easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, tG.rgb);
                easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, tO.rgb);
                easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, tN.rgb);
                vec3 pix = min(max4, max(min4, aC / max(aW, 0.0001)));
                return vec4(pix, tF.a);
            }

            float animePoly(float x) {
                return 11.68129591 * x * x * x * x * x
                     - 42.46906057 * x * x * x * x
                     + 60.28286266 * x * x * x
                     - 41.84451327 * x * x
                     + 14.05517353 * x
                     - 1.08152193;
            }

            vec4 anime4kSample(vec2 uv, vec2 texel) {
                vec2 slo = texel * 0.5;
                vec2 shi = vec2(1.0) - slo;
                float tl = easuLuma(texture2D(uTexture, clamp(uv + vec2(-texel.x, -texel.y), slo, shi)));
                float tt = easuLuma(texture2D(uTexture, clamp(uv + vec2( 0.0, -texel.y), slo, shi)));
                float tr = easuLuma(texture2D(uTexture, clamp(uv + vec2( texel.x, -texel.y), slo, shi)));
                float ll = easuLuma(texture2D(uTexture, clamp(uv + vec2(-texel.x,  0.0), slo, shi)));
                float rr = easuLuma(texture2D(uTexture, clamp(uv + vec2( texel.x,  0.0), slo, shi)));
                float bl = easuLuma(texture2D(uTexture, clamp(uv + vec2(-texel.x,  texel.y), slo, shi)));
                float bb = easuLuma(texture2D(uTexture, clamp(uv + vec2( 0.0,  texel.y), slo, shi)));
                float br = easuLuma(texture2D(uTexture, clamp(uv + vec2( texel.x,  texel.y), slo, shi)));
                float xg = (tr - tl) + 2.0 * (rr - ll) + (br - bl);
                float yg = (tl + 2.0 * tt + tr) - (bl + 2.0 * bb + br);
                float norm = clamp(sqrt(xg * xg + yg * yg), 0.0, 1.0);
                float dval = clamp(animePoly(norm) * 0.5, 0.0, 1.0);
                // The reference blends onto the BILINEAR-upscaled frame (its
                // pass outputs at 2x). Blending onto Catmull-Rom instead was
                // one of the two reasons this mode looked over-sharp with heavy
                // jaggies.
                vec4 base = texture2D(uTexture, uv);
                if (dval < 0.1 || norm <= 0.001) return base;
                // Reference: one OUTPUT pixel; at its 2x output that is half a
                // source texel. Shifting a whole source texel (the old value)
                // doubled the edge push and aliased.
                vec2 blendStep = texel * 0.5;
                vec4 xval = texture2D(uTexture,
                    clamp(uv + vec2(-sign(xg) * blendStep.x, 0.0), slo, shi));
                vec4 yval = texture2D(uTexture,
                    clamp(uv + vec2(0.0, -sign(yg) * blendStep.y), slo, shi));
                float ratio = abs(xg) / (abs(xg) + abs(yg) + 0.0001);
                vec4 avg = ratio * xval + (1.0 - ratio) * yval;
                return avg * dval + base * (1.0 - dval);
            }

            vec3 rcasFilter(vec3 e, vec3 b, vec3 d, vec3 f, vec3 h, float sharpness) {
                float bL = easuLuma(vec4(b, 1.0));
                float dL = easuLuma(vec4(d, 1.0));
                float eL = easuLuma(vec4(e, 1.0));
                float fL = easuLuma(vec4(f, 1.0));
                float hL = easuLuma(vec4(h, 1.0));
                float mxL = max(max(bL, dL), max(fL, hL));
                float mnL = min(min(bL, dL), min(fL, hL));
                float nz = 0.25 * (bL + dL + fL + hL) - eL;
                nz = clamp(abs(nz) / max(mxL - mnL, 0.0001), 0.0, 1.0);
                nz = 1.0 - 0.5 * nz;
                vec3 mn4 = min(min(b, d), min(f, h));
                vec3 mx4 = max(max(b, d), max(f, h));
                vec3 hitMin = min(mn4, e) / (4.0 * max(mx4, 0.0001) + 0.0001);
                vec3 hitMax = (vec3(1.0) - max(mx4, e)) /
                              (4.0 * mn4 - 4.0 + 0.0001);
                vec3 lobe = max(-hitMin, hitMax);
                float lobeS = max(-0.1875,
                    min(max(max(lobe.r, lobe.g), lobe.b), 0.0)) * sharpness;
                // FSR_RCAS_DENOISE: scale the lobe by the noise detector.
                lobeS *= nz;
                float rcpL = 1.0 / (4.0 * lobeS + 1.0);
                return (lobeS * (b + d + f + h) + e) * rcpL;
            }

            void main() {
                if (uEnhance > 0.001) {
                    vec4 e;
                    if (uEnhanceMode > 1.5) {
                        e = anime4kSample(vTexCoord, uSrcTexel);
                    } else if (uEnhanceMode > 0.5) {
                        e = easuSample(vTexCoord, uSrcTexel, uEasuScale);
                    } else {
                        e = bicubic4(vTexCoord, uSrcTexel);
                    }
                    // Anti-aliasing at SOURCE resolution: at 3-4x magnification
                    // the source's own stair-steps are what gets enlarged, so a
                    // small cross-blur of the neighbouring source texels smooths
                    // diagonal edges. The blend grows with uEnhance and stays
                    // negligible below ~2x.
                    vec2 slo = uSrcTexel * 0.5;
                    vec2 shi = vec2(1.0) - slo;
                    vec4 s0 = texture2D(uTexture, clamp(vTexCoord + vec2(-uSrcTexel.x, 0.0), slo, shi));
                    vec4 s1 = texture2D(uTexture, clamp(vTexCoord + vec2(uSrcTexel.x, 0.0), slo, shi));
                    vec4 s2 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, -uSrcTexel.y), slo, shi));
                    vec4 s3 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, uSrcTexel.y), slo, shi));
                    vec4 blur = (s0 + s1 + s2 + s3) * 0.25;
                    vec4 t0 = texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0));
                    vec4 t1 = texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0));
                    vec4 t2 = texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y));
                    vec4 t3 = texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y));
                    // 降噪分支: colour-distance weighted neighbour average. The
                    // strength comes from the CPU-side quality probe (images)
                    // or a magnification-scaled fixed value (video); near an
                    // edge the far side's weight collapses, so edges stay put.
                    float w0 = 1.0 / (1.0 + 60.0 * dist2(s0.rgb, e.rgb));
                    float w1 = 1.0 / (1.0 + 60.0 * dist2(s1.rgb, e.rgb));
                    float w2 = 1.0 / (1.0 + 60.0 * dist2(s2.rgb, e.rgb));
                    float w3 = 1.0 / (1.0 + 60.0 * dist2(s3.rgb, e.rgb));
                    vec4 den = (e + s0 * w0 + s1 * w1 + s2 * w2 + s3 * w3) /
                               (1.0 + w0 + w1 + w2 + w3);
                    e = mix(e, den, uDenoise);
                    // A light source-space smoothing, then FXAA-lite: find the
                    // edge direction from the screen-pixel luma neighbours and
                    // blend ALONG the edge - this is what removes magnified
                    // stair-steps without blurring the edge itself.
                    e = mix(e, (e + blur) * 0.5, 0.05 * uEnhance);
                    // 细节增强（源像素尺度）: the fine sharpening below works at
                    // ONE SCREEN pixel - at 4-5x magnification that is only
                    // 0.2-0.25 of a source texel, so it cannot fight the blur
                    // that upscaling produces. This unsharp uses the 1-source-
                    // texel neighbourhood (s0..s3) instead, pulling the
                    // transitions BETWEEN source pixels apart again.
                    float detailAmount = 0.60 * uEnhance;
                    if (uEnhanceMode > 1.5) detailAmount *= 0.75;
                    e.rgb = clamp(e.rgb + (e.rgb - blur.rgb) * detailAmount, 0.0, 1.0);
                    // 第二尺度：2 个源纹素半径的局部对比（"通透感"）。高倍率下
                    // 人眼对中频对比最敏感，这一层比 1 纹素的细部 unsharp 更显眼。
                    vec2 wide2 = uSrcTexel * 2.0;
                    vec4 c0 = texture2D(uTexture, clamp(vTexCoord + vec2(-wide2.x, 0.0), slo, shi));
                    vec4 c1 = texture2D(uTexture, clamp(vTexCoord + vec2(wide2.x, 0.0), slo, shi));
                    vec4 c2 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, -wide2.y), slo, shi));
                    vec4 c3 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, wide2.y), slo, shi));
                    vec4 wideAvg = (c0 + c1 + c2 + c3) * 0.25;
                    float wideAmount = 0.25 * uEnhance;
                    if (uEnhanceMode > 1.5) wideAmount *= 0.75;
                    e.rgb = clamp(e.rgb + (e.rgb - wideAvg.rgb) * wideAmount, 0.0, 1.0);
                    float lM = luma(e.rgb);
                    float lW = luma(t0.rgb);
                    float lE = luma(t1.rgb);
                    float lN = luma(t2.rgb);
                    float lS = luma(t3.rgb);
                    float lMin = min(min(lN, lS), min(min(lW, lE), lM));
                    float lMax = max(max(lN, lS), max(max(lW, lE), lM));
                    float lContrast = lMax - lMin;
                    if (lContrast > 0.05) {
                        vec4 along = (abs(lE - lW) > abs(lS - lN))
                            ? (t2 + t3) * 0.5
                            : (t0 + t1) * 0.5;
                        // Anime4K pushes edges itself; give the edge smoothing
                        // more weight there so its stair-steps get flattened.
                        float aaBoost = uEnhanceMode > 1.5 ? 1.6 : 1.0;
                        e = mix(e, along,
                            min(0.6, (0.25 + 0.5 * lContrast) * aaBoost) * uEnhance);
                    }
                    if (uSharp <= 0.001) {
                        gl_FragColor = clamp(vec4(e.rgb, uAlpha), 0.0, 1.0);
                        return;
                    }
                    if (uEnhanceMode > 0.5 && uEnhanceMode < 1.5) {
                        // FSR1: RCAS replaces the generic unsharp (single-pass
                        // adaptation of the reference's second pass).
                        vec3 rcas = rcasFilter(
                            e.rgb, t2.rgb, t0.rgb, t1.rgb, t3.rgb,
                            clamp(uSharp * 2.5, 0.0, 1.0));
                        gl_FragColor = clamp(vec4(rcas, uAlpha), 0.0, 1.0);
                        return;
                    }
                    // Contrast-adaptive sharpening (RCAS-style): high-contrast
                    // edges - exactly where over-sharpening reads as jaggies and
                    // halos - get 40% of the clarity amount, flat areas keep it.
                    // The cap guarantees the enhanced path is never sharper than
                    // the normal one.
                    float eL = dot(e.rgb, vec3(0.299, 0.587, 0.114));
                    float a0 = dot(t0.rgb, vec3(0.299, 0.587, 0.114));
                    float a1 = dot(t1.rgb, vec3(0.299, 0.587, 0.114));
                    float a2 = dot(t2.rgb, vec3(0.299, 0.587, 0.114));
                    float a3 = dot(t3.rgb, vec3(0.299, 0.587, 0.114));
                    float mn = min(min(min(a0, a1), min(a2, a3)), eL);
                    float mx = max(max(max(a0, a1), max(a2, a3)), eL);
                    float amp = sqrt(clamp(mn / max(mx, 0.0001), 0.0, 1.0));
                    // 高倍放大（360p -> 3.2K 这类 4~5x）只用放大算法会显得"平"，
                    // 细节增强要随放大倍数加强；同时把上限从 0.6 提到 0.95。
                    float enhSharp = uSharp * (1.0 + 0.7 * uEnhance);
                    float sharpCap =
                        mix(0.6, 0.95, clamp((uEnhance - 0.5) / 0.5, 0.0, 1.0));
                    // Anime4K already enhances edges; halve the generic sharpening
                    // on top of it so the mode stops looking over-sharp.
                    float sharpScale = uEnhanceMode > 1.5 ? 0.5 : 1.0;
                    float esharp = min(enhSharp * (0.40 + 0.60 * amp), sharpCap) * sharpScale;
                    if (esharp <= 0.001) {
                        gl_FragColor = clamp(vec4(e.rgb, uAlpha), 0.0, 1.0);
                        return;
                    }
                    vec4 es = e * (1.0 + 4.0 * esharp)
                           - (t0 + t1 + t2 + t3) * esharp;
                    gl_FragColor = clamp(vec4(es.rgb, uAlpha), 0.0, 1.0);
                    return;
                }
                // Mild unsharp mask. uSharp == 0.0 keeps the original pixel
                // exactly (used for downscaled/native media and the black
                // background). The early return also skips the 4 neighbor
                // fetches, so normal/high-res wallpapers cost exactly the
                // same GPU bandwidth as before sharpening was added.
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uSharp <= 0.001) {
                    gl_FragColor = vec4(c.rgb, uAlpha);
                    return;
                }
                vec4 s = c * (1.0 + 4.0 * uSharp)
                       - (texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y))) * uSharp;
                gl_FragColor = clamp(vec4(s.rgb, uAlpha), 0.0, 1.0);
            }
        """

        const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            varying highp vec2 vTexCoord;
            #else
            precision mediump float;
            varying mediump vec2 vTexCoord;
            #endif
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform vec2 uSrcTexel;
            uniform float uSharp;
            uniform float uEnhance;

            uniform float uDenoise;
            uniform float uEnhanceMode;
            uniform vec2 uEasuScale;
            vec4 cubicWeights(float t) {
                float t2 = t * t;
                float t3 = t2 * t;
                return vec4(
                    -0.5 * t3 + t2 - 0.5 * t,
                     1.5 * t3 - 2.5 * t2 + 1.0,
                    -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                     0.5 * t3 - 0.5 * t2
                );
            }

            vec4 bicubic4(vec2 uv, vec2 texel) {
                vec2 c = uv / texel;
                vec2 i0 = floor(c);
                vec2 t = c - i0;
                vec4 wx = cubicWeights(t.x);
                vec4 wy = cubicWeights(t.y);
                float wAx = wx.x + wx.y;
                float wBx = wx.z + wx.w;
                float wAy = wy.x + wy.y;
                float wBy = wy.z + wy.w;
                float pAx = wAx > 0.0001 ? wx.y / wAx : 0.5;
                float pBx = wBx > 0.0001 ? wx.w / wBx : 0.5;
                float pAy = wAy > 0.0001 ? wy.y / wAy : 0.5;
                float pBy = wBy > 0.0001 ? wy.w / wBy : 0.5;
                vec2 lo = texel * 0.5;
                vec2 hi = vec2(1.0) - lo;
                vec4 acc = vec4(0.0);
                acc += (wAx * wAy) * texture2D(uTexture, clamp(vec2(i0.x - 1.0 + pAx, i0.y - 1.0 + pAy) * texel, lo, hi));
                acc += (wBx * wAy) * texture2D(uTexture, clamp(vec2(i0.x + 1.0 + pBx, i0.y - 1.0 + pAy) * texel, lo, hi));
                acc += (wAx * wBy) * texture2D(uTexture, clamp(vec2(i0.x - 1.0 + pAx, i0.y + 1.0 + pBy) * texel, lo, hi));
                acc += (wBx * wBy) * texture2D(uTexture, clamp(vec2(i0.x + 1.0 + pBx, i0.y + 1.0 + pBy) * texel, lo, hi));
                return acc;
            }

            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            float dist2(vec3 a, vec3 b) { vec3 d = a - b; return dot(d, d); }

            // ===== 画质增强的放大算法（只在 uEnhanceMode > 0 时使用）=====
            //
            // FSR1 EASU: port of AMD FidelityFX FSR 1.0 [EASU] (MIT). The
            // reference gathers 2x2 quads with textureGather; the same 12
            // texels are addressed directly here, so it runs in the existing
            // GLES2 single pass. [RCAS] below is the single-pass adaptation.
            //
            // Anime4K: single-pass port of "Upscale: Original x2" from
            // bloc97/Anime4K v4 (MIT): luma sobel -> polynomial refinement
            // value -> blend along the gradient direction.
            float easuLuma(vec4 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }

            void easuSet(inout vec2 dir, inout float len, float w,
                         float lA, float lB, float lC, float lD, float lE) {
                float lenX = max(abs(lD - lC), abs(lC - lB));
                lenX = 1.0 / max(lenX, 1.0 / 32768.0);
                float dirX = lD - lB;
                dir.x += dirX * w;
                lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
                len += lenX * lenX * w;
                float lenY = max(abs(lE - lC), abs(lC - lA));
                lenY = 1.0 / max(lenY, 1.0 / 32768.0);
                float dirY = lE - lA;
                dir.y += dirY * w;
                lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
                len += lenY * lenY * w;
            }

            void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len,
                         float lob, float clp, vec3 c) {
                vec2 v = vec2(off.x * dir.x + off.y * dir.y,
                              off.x * (-dir.y) + off.y * dir.x);
                v *= len;
                float d2 = min(dot(v, v), clp);
                float wB = (2.0 / 5.0) * d2 - 1.0;
                float wA = lob * d2 - 1.0;
                wB *= wB;
                wA *= wA;
                wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
                float w = wB * wA;
                aC += c * w;
                aW += w;
            }

            vec4 easuSample(vec2 uv, vec2 texel, vec2 scale) {
                vec2 ip = uv / (texel * max(scale, vec2(0.0001)));
                vec2 pp = ip * scale + (0.5 * scale - 0.5);
                vec2 fp = floor(pp);
                pp -= fp;
                vec2 slo = texel * 0.5;
                vec2 shi = vec2(1.0) - slo;
                // 12-tap kernel:  b c / e f g h / i j k l / n o.
                vec4 tB = texture2D(uTexture, clamp((fp + vec2( 0.0, -1.0)) * texel, slo, shi));
                vec4 tC = texture2D(uTexture, clamp((fp + vec2( 1.0, -1.0)) * texel, slo, shi));
                vec4 tE = texture2D(uTexture, clamp((fp + vec2(-1.0,  0.0)) * texel, slo, shi));
                vec4 tF = texture2D(uTexture, clamp(fp * texel, slo, shi));
                vec4 tG = texture2D(uTexture, clamp((fp + vec2( 1.0,  0.0)) * texel, slo, shi));
                vec4 tH = texture2D(uTexture, clamp((fp + vec2( 2.0,  0.0)) * texel, slo, shi));
                vec4 tI = texture2D(uTexture, clamp((fp + vec2(-1.0,  1.0)) * texel, slo, shi));
                vec4 tJ = texture2D(uTexture, clamp((fp + vec2( 0.0,  1.0)) * texel, slo, shi));
                vec4 tK = texture2D(uTexture, clamp((fp + vec2( 1.0,  1.0)) * texel, slo, shi));
                vec4 tL = texture2D(uTexture, clamp((fp + vec2( 2.0,  1.0)) * texel, slo, shi));
                vec4 tN = texture2D(uTexture, clamp((fp + vec2( 0.0,  2.0)) * texel, slo, shi));
                vec4 tO = texture2D(uTexture, clamp((fp + vec2( 1.0,  2.0)) * texel, slo, shi));
                float bL = easuLuma(tB); float cL = easuLuma(tC);
                float eL = easuLuma(tE); float fL = easuLuma(tF);
                float gL = easuLuma(tG); float hL = easuLuma(tH);
                float iL = easuLuma(tI); float jL = easuLuma(tJ);
                float kL = easuLuma(tK); float lL = easuLuma(tL);
                float nL = easuLuma(tN); float oL = easuLuma(tO);
                vec2 dir = vec2(0.0);
                float len = 0.0;
                easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
                easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
                easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
                easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
                float dirR = dot(dir, dir);
                if (dirR < (1.0 / 32768.0)) {
                    dir = vec2(1.0, 0.0);
                } else {
                    dir *= inversesqrt(dirR);
                }
                len *= 0.5;
                len *= len;
                float stretch = dot(dir, dir) *
                    (1.0 / max(max(abs(dir.x), abs(dir.y)), 0.0001));
                vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
                float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
                float clp = 1.0 / max(lob, 0.0001);
                vec3 min4 = min(min(tF.rgb, tG.rgb), min(tJ.rgb, tK.rgb));
                vec3 max4 = max(max(tF.rgb, tG.rgb), max(tJ.rgb, tK.rgb));
                vec3 aC = vec3(0.0);
                float aW = 0.0;
                easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, tB.rgb);
                easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, tC.rgb);
                easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, tI.rgb);
                easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, tJ.rgb);
                easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, tF.rgb);
                easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, tE.rgb);
                easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, tK.rgb);
                easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, tL.rgb);
                easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, tH.rgb);
                easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, tG.rgb);
                easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, tO.rgb);
                easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, tN.rgb);
                vec3 pix = min(max4, max(min4, aC / max(aW, 0.0001)));
                return vec4(pix, tF.a);
            }

            float animePoly(float x) {
                return 11.68129591 * x * x * x * x * x
                     - 42.46906057 * x * x * x * x
                     + 60.28286266 * x * x * x
                     - 41.84451327 * x * x
                     + 14.05517353 * x
                     - 1.08152193;
            }

            vec4 anime4kSample(vec2 uv, vec2 texel) {
                vec2 slo = texel * 0.5;
                vec2 shi = vec2(1.0) - slo;
                float tl = easuLuma(texture2D(uTexture, clamp(uv + vec2(-texel.x, -texel.y), slo, shi)));
                float tt = easuLuma(texture2D(uTexture, clamp(uv + vec2( 0.0, -texel.y), slo, shi)));
                float tr = easuLuma(texture2D(uTexture, clamp(uv + vec2( texel.x, -texel.y), slo, shi)));
                float ll = easuLuma(texture2D(uTexture, clamp(uv + vec2(-texel.x,  0.0), slo, shi)));
                float rr = easuLuma(texture2D(uTexture, clamp(uv + vec2( texel.x,  0.0), slo, shi)));
                float bl = easuLuma(texture2D(uTexture, clamp(uv + vec2(-texel.x,  texel.y), slo, shi)));
                float bb = easuLuma(texture2D(uTexture, clamp(uv + vec2( 0.0,  texel.y), slo, shi)));
                float br = easuLuma(texture2D(uTexture, clamp(uv + vec2( texel.x,  texel.y), slo, shi)));
                float xg = (tr - tl) + 2.0 * (rr - ll) + (br - bl);
                float yg = (tl + 2.0 * tt + tr) - (bl + 2.0 * bb + br);
                float norm = clamp(sqrt(xg * xg + yg * yg), 0.0, 1.0);
                float dval = clamp(animePoly(norm) * 0.5, 0.0, 1.0);
                // The reference blends onto the BILINEAR-upscaled frame (its
                // pass outputs at 2x). Blending onto Catmull-Rom instead was
                // one of the two reasons this mode looked over-sharp with heavy
                // jaggies.
                vec4 base = texture2D(uTexture, uv);
                if (dval < 0.1 || norm <= 0.001) return base;
                // Reference: one OUTPUT pixel; at its 2x output that is half a
                // source texel. Shifting a whole source texel (the old value)
                // doubled the edge push and aliased.
                vec2 blendStep = texel * 0.5;
                vec4 xval = texture2D(uTexture,
                    clamp(uv + vec2(-sign(xg) * blendStep.x, 0.0), slo, shi));
                vec4 yval = texture2D(uTexture,
                    clamp(uv + vec2(0.0, -sign(yg) * blendStep.y), slo, shi));
                float ratio = abs(xg) / (abs(xg) + abs(yg) + 0.0001);
                vec4 avg = ratio * xval + (1.0 - ratio) * yval;
                return avg * dval + base * (1.0 - dval);
            }

            vec3 rcasFilter(vec3 e, vec3 b, vec3 d, vec3 f, vec3 h, float sharpness) {
                float bL = easuLuma(vec4(b, 1.0));
                float dL = easuLuma(vec4(d, 1.0));
                float eL = easuLuma(vec4(e, 1.0));
                float fL = easuLuma(vec4(f, 1.0));
                float hL = easuLuma(vec4(h, 1.0));
                float mxL = max(max(bL, dL), max(fL, hL));
                float mnL = min(min(bL, dL), min(fL, hL));
                float nz = 0.25 * (bL + dL + fL + hL) - eL;
                nz = clamp(abs(nz) / max(mxL - mnL, 0.0001), 0.0, 1.0);
                nz = 1.0 - 0.5 * nz;
                vec3 mn4 = min(min(b, d), min(f, h));
                vec3 mx4 = max(max(b, d), max(f, h));
                vec3 hitMin = min(mn4, e) / (4.0 * max(mx4, 0.0001) + 0.0001);
                vec3 hitMax = (vec3(1.0) - max(mx4, e)) /
                              (4.0 * mn4 - 4.0 + 0.0001);
                vec3 lobe = max(-hitMin, hitMax);
                float lobeS = max(-0.1875,
                    min(max(max(lobe.r, lobe.g), lobe.b), 0.0)) * sharpness;
                // FSR_RCAS_DENOISE: scale the lobe by the noise detector.
                lobeS *= nz;
                float rcpL = 1.0 / (4.0 * lobeS + 1.0);
                return (lobeS * (b + d + f + h) + e) * rcpL;
            }

            void main() {
                if (uEnhance > 0.001) {
                    vec4 e;
                    if (uEnhanceMode > 1.5) {
                        e = anime4kSample(vTexCoord, uSrcTexel);
                    } else if (uEnhanceMode > 0.5) {
                        e = easuSample(vTexCoord, uSrcTexel, uEasuScale);
                    } else {
                        e = bicubic4(vTexCoord, uSrcTexel);
                    }
                    vec2 slo = uSrcTexel * 0.5;
                    vec2 shi = vec2(1.0) - slo;
                    vec4 s0 = texture2D(uTexture, clamp(vTexCoord + vec2(-uSrcTexel.x, 0.0), slo, shi));
                    vec4 s1 = texture2D(uTexture, clamp(vTexCoord + vec2(uSrcTexel.x, 0.0), slo, shi));
                    vec4 s2 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, -uSrcTexel.y), slo, shi));
                    vec4 s3 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, uSrcTexel.y), slo, shi));
                    vec4 blur = (s0 + s1 + s2 + s3) * 0.25;
                    vec4 t0 = texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0));
                    vec4 t1 = texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0));
                    vec4 t2 = texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y));
                    vec4 t3 = texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y));
                    // 降噪分支: colour-distance weighted neighbour average. The
                    // strength comes from the CPU-side quality probe (images)
                    // or a magnification-scaled fixed value (video); near an
                    // edge the far side's weight collapses, so edges stay put.
                    float w0 = 1.0 / (1.0 + 60.0 * dist2(s0.rgb, e.rgb));
                    float w1 = 1.0 / (1.0 + 60.0 * dist2(s1.rgb, e.rgb));
                    float w2 = 1.0 / (1.0 + 60.0 * dist2(s2.rgb, e.rgb));
                    float w3 = 1.0 / (1.0 + 60.0 * dist2(s3.rgb, e.rgb));
                    vec4 den = (e + s0 * w0 + s1 * w1 + s2 * w2 + s3 * w3) /
                               (1.0 + w0 + w1 + w2 + w3);
                    e = mix(e, den, uDenoise);
                    // A light source-space smoothing, then FXAA-lite: find the
                    // edge direction from the screen-pixel luma neighbours and
                    // blend ALONG the edge - this is what removes magnified
                    // stair-steps without blurring the edge itself.
                    e = mix(e, (e + blur) * 0.5, 0.05 * uEnhance);
                    // 细节增强（源像素尺度）: the fine sharpening below works at
                    // ONE SCREEN pixel - at 4-5x magnification that is only
                    // 0.2-0.25 of a source texel, so it cannot fight the blur
                    // that upscaling produces. This unsharp uses the 1-source-
                    // texel neighbourhood (s0..s3) instead, pulling the
                    // transitions BETWEEN source pixels apart again.
                    float detailAmount = 0.60 * uEnhance;
                    if (uEnhanceMode > 1.5) detailAmount *= 0.75;
                    e.rgb = clamp(e.rgb + (e.rgb - blur.rgb) * detailAmount, 0.0, 1.0);
                    // 第二尺度：2 个源纹素半径的局部对比（"通透感"）。高倍率下
                    // 人眼对中频对比最敏感，这一层比 1 纹素的细部 unsharp 更显眼。
                    vec2 wide2 = uSrcTexel * 2.0;
                    vec4 c0 = texture2D(uTexture, clamp(vTexCoord + vec2(-wide2.x, 0.0), slo, shi));
                    vec4 c1 = texture2D(uTexture, clamp(vTexCoord + vec2(wide2.x, 0.0), slo, shi));
                    vec4 c2 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, -wide2.y), slo, shi));
                    vec4 c3 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, wide2.y), slo, shi));
                    vec4 wideAvg = (c0 + c1 + c2 + c3) * 0.25;
                    float wideAmount = 0.25 * uEnhance;
                    if (uEnhanceMode > 1.5) wideAmount *= 0.75;
                    e.rgb = clamp(e.rgb + (e.rgb - wideAvg.rgb) * wideAmount, 0.0, 1.0);
                    float lM = luma(e.rgb);
                    float lW = luma(t0.rgb);
                    float lE = luma(t1.rgb);
                    float lN = luma(t2.rgb);
                    float lS = luma(t3.rgb);
                    float lMin = min(min(lN, lS), min(min(lW, lE), lM));
                    float lMax = max(max(lN, lS), max(max(lW, lE), lM));
                    float lContrast = lMax - lMin;
                    if (lContrast > 0.05) {
                        vec4 along = (abs(lE - lW) > abs(lS - lN))
                            ? (t2 + t3) * 0.5
                            : (t0 + t1) * 0.5;
                        // Anime4K pushes edges itself; give the edge smoothing
                        // more weight there so its stair-steps get flattened.
                        float aaBoost = uEnhanceMode > 1.5 ? 1.6 : 1.0;
                        e = mix(e, along,
                            min(0.6, (0.25 + 0.5 * lContrast) * aaBoost) * uEnhance);
                    }
                    if (uSharp <= 0.001) {
                        gl_FragColor = clamp(e, 0.0, 1.0);
                        return;
                    }
                    if (uEnhanceMode > 0.5 && uEnhanceMode < 1.5) {
                        // FSR1: RCAS replaces the generic unsharp (single-pass
                        // adaptation of the reference's second pass).
                        vec3 rcas = rcasFilter(
                            e.rgb, t2.rgb, t0.rgb, t1.rgb, t3.rgb,
                            clamp(uSharp * 2.5, 0.0, 1.0));
                        gl_FragColor = clamp(vec4(rcas, 1.0), 0.0, 1.0);
                        return;
                    }
                    float eL = dot(e.rgb, vec3(0.299, 0.587, 0.114));
                    float a0 = dot(t0.rgb, vec3(0.299, 0.587, 0.114));
                    float a1 = dot(t1.rgb, vec3(0.299, 0.587, 0.114));
                    float a2 = dot(t2.rgb, vec3(0.299, 0.587, 0.114));
                    float a3 = dot(t3.rgb, vec3(0.299, 0.587, 0.114));
                    float mn = min(min(min(a0, a1), min(a2, a3)), eL);
                    float mx = max(max(max(a0, a1), max(a2, a3)), eL);
                    float amp = sqrt(clamp(mn / max(mx, 0.0001), 0.0, 1.0));
                    // 高倍放大（360p -> 3.2K 这类 4~5x）只用放大算法会显得"平"，
                    // 细节增强要随放大倍数加强；同时把上限从 0.6 提到 0.95。
                    float enhSharp = uSharp * (1.0 + 0.7 * uEnhance);
                    float sharpCap =
                        mix(0.6, 0.95, clamp((uEnhance - 0.5) / 0.5, 0.0, 1.0));
                    // Anime4K already enhances edges; halve the generic sharpening
                    // on top of it so the mode stops looking over-sharp.
                    float sharpScale = uEnhanceMode > 1.5 ? 0.5 : 1.0;
                    float esharp = min(enhSharp * (0.40 + 0.60 * amp), sharpCap) * sharpScale;
                    if (esharp <= 0.001) {
                        gl_FragColor = clamp(e, 0.0, 1.0);
                        return;
                    }
                    vec4 es = e * (1.0 + 4.0 * esharp)
                           - (t0 + t1 + t2 + t3) * esharp;
                    gl_FragColor = clamp(es, 0.0, 1.0);
                    return;
                }
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uSharp <= 0.001) {
                    gl_FragColor = c;
                    return;
                }
                vec4 s = c * (1.0 + 4.0 * uSharp)
                       - (texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y))) * uSharp;
                gl_FragColor = clamp(s, 0.0, 1.0);
            }
        """

        /**
         * Pre-enhancement shader sources, kept as the automatic fallback.
         *
         * A wallpaper that fails to compile its shader is a black wallpaper, and
         * the enhancement path is opt-in: if a driver rejects the bicubic
         * version (old GPU, mediump quirk), the engine silently falls back to
         * these and keeps working - the uEnhance / uSrcTexel uniform locations
         * are simply -1 then and the extra uniforms are ignored.
         */
        const val IMAGE_FRAGMENT_SHADER_FALLBACK = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform float uSharp;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uSharp <= 0.001) {
                    gl_FragColor = vec4(c.rgb, uAlpha);
                    return;
                }
                vec4 s = c * (1.0 + 4.0 * uSharp)
                       - (texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y))) * uSharp;
                gl_FragColor = clamp(vec4(s.rgb, uAlpha), 0.0, 1.0);
            }
        """

        const val VIDEO_FRAGMENT_SHADER_FALLBACK = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform float uSharp;
            varying vec2 vTexCoord;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uSharp <= 0.001) {
                    gl_FragColor = c;
                    return;
                }
                vec4 s = c * (1.0 + 4.0 * uSharp)
                       - (texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y))) * uSharp;
                gl_FragColor = clamp(s, 0.0, 1.0);
            }
        """
}