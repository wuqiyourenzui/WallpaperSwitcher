package com.wallpaperswitcher.wallpaper

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.view.Surface
import com.wallpaperswitcher.util.AppLog

/**
 * EGL context + window-surface lifecycle, from `WallpaperRenderer` (逻辑逐字搬移).
 * All access on the render thread only. The renderer supplies the wallpaper
 * [Surface] and the GL-resource rebuild callbacks.
 */
internal class EglSurfaceHolder(
    private val tag: String,
    private val host: Host,
) {

    internal interface Host {
        fun surface(): Surface
        fun surfaceReady(): Boolean
        fun setContextReady(ready: Boolean)
        fun setSurfaceReady(ready: Boolean)
        /** Create the GL programs/buffers when they are not valid yet. */
        fun ensureGlResources()
        /** A brand-new GL context is current: every old GL object is dead. */
        fun onContextRecreated()
        /** A surface is current and sized: publish it to the renderer. */
        fun onSurfaceCreated(width: Int, height: Int)
        /** Post a surface-retry task on the render handler. */
        fun postRetry(delayMs: Long, block: () -> Unit)
    }

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    /**
     * True only when THIS renderer's `eglInitialize` call succeeded.
     *
     * Every renderer in the process gets the same handle from
     * `eglGetDisplay(EGL_DEFAULT_DISPLAY)`, and a home engine and a preview engine can
     * coexist. [setupContext] keeps that handle when `eglInitialize` fails, so
     * without this flag [release] would call `eglTerminate` on a display this
     * instance never initialised - an unbalanced init/terminate pair that can drop a
     * sibling engine's last reference and kill its GL context (frozen wallpaper).
     */
    private var eglInitializedHere = false

    val hasSurface: Boolean get() = eglSurface != EGL14.EGL_NO_SURFACE

    fun setupContext() {
        // A second setupContext() (surface recreated after a failed
        // eglMakeCurrent below, engine restart, preview dialog) used to create a
        // NEW context while the previous one was still alive: release() only
        // destroys the last, so every failed cycle leaked one EGL context on a
        // driver that was already misbehaving.
        teardownContext()
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return
        // From here on this instance owns the display and must terminate it (see
        // eglInitializedHere).
        eglInitializedHere = true

        // Some devices/GPUs (e.g. Xiaomi/HyperOS tablets) reject the first
        // RGBA8888 + ES2 window config and eglCreateWindowSurface then fails,
        // which left the wallpaper black. Try a few configs in order:
        // RGBA8888 -> RGB888 (no alpha) -> RGB565, and use the first that
        // eglChooseConfig accepts.
        val configCandidates = arrayOf(
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 5, EGL14.EGL_GREEN_SIZE, 6, EGL14.EGL_BLUE_SIZE, 5,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
            )
        )
        eglConfig = null
        for (attribs in configCandidates) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
            if (configs[0] != null) {
                eglConfig = configs[0]
                break
            }
        }
        if (eglConfig == null) {
            AppLog.e(tag, "No usable EGL config found")
            return
        }

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            AppLog.e(tag, "EGL context creation failed")
            return
        }
        host.setContextReady(true)

        val surface = host.surface()
        if (surface.isValid) createSurface()
    }

    fun createSurface(attempt: Int = 0) {
        val surface = host.surface()
        if (!surface.isValid) {
            // The Surface is transiently invalid while the display rotates on
            // some devices. Retry briefly instead of leaving the wallpaper
            // black until the next surface event.
            if (attempt < 3) {
                host.postRetry(200L) { createSurface(attempt + 1) }
            } else {
                AppLog.e(tag, "Surface invalid after $attempt retries")
            }
            return
        }

        // A delayed retry scheduled during an EARLIER rotation can run after a
        // newer surface was already created successfully. Recreating it then
        // would only flicker/re-tear the healthy surface, so skip stale
        // retries when the renderer is already ready.
        if (attempt > 0 && host.surfaceReady() && hasSurface) {
            return
        }

        if (hasSurface) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        val attribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            AppLog.e(tag, "eglCreateWindowSurface failed (attempt $attempt): ${EGL14.eglGetError()}")
            if (attempt < 3) {
                host.postRetry(200L) { createSurface(attempt + 1) }
            }
            return
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            // Destroy the old context before creating a new one to avoid leak
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                host.setContextReady(false)
                return
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                host.setContextReady(false)
                return
            }
            // A new GL context invalidates every object the old one created. The video
            // side has to be dropped with it: surfaceTexture / codecSurface / videoTexId
            // still named objects of the destroyed context, and reuseGl() only checks
            // that they are non-zero, so it kept them - updateTexImage() then failed on
            // every frame and the video stayed frozen on its last frame until a media
            // switch. This is a no-op when they are already cleared.
            host.onContextRecreated()
        }

        host.ensureGlResources()

        val qr = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, qr, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, qr, 1)
        GLES20.glViewport(0, 0, qr[0], qr[1])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        host.onSurfaceCreated(qr[0], qr[1])
        AppLog.d(tag, "EGL surface: ${qr[0]}x${qr[1]}")
    }

    fun destroySurface() {
        if (hasSurface) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    /**
     * Drop the surface + context of THIS holder (and the display when this
     * instance initialised it) without touching the host.
     *
     * `release()` is the teardown path; this is the reusable half so that
     * [setupContext] can start from a clean slate instead of leaking whatever
     * the previous (possibly half-built) attempt left behind.
     */
    private fun teardownContext() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglConfig = null
            return
        }
        EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        // Only terminate a display THIS holder initialised: the handle is shared
        // process-wide (see eglInitializedHere), so terminating one we failed to
        // initialise would unbalance the pair and could take a sibling engine's
        // context down with it.
        if (eglInitializedHere) EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        eglInitializedHere = false
    }

    /** `eglSwapBuffers` on the current display/surface pair. */
    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    /** Renderer teardown: destroy surface/context, terminate our own display. */
    fun release() {
        // Tell the host first: everything after this point is "no GL context",
        // and a repaint that slipped in must not believe it can draw.
        host.setContextReady(false)
        host.setSurfaceReady(false)
        teardownContext()
    }
}
