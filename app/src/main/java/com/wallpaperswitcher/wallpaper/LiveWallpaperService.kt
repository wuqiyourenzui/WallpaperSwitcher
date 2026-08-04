package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.Surface
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
        const val EXTRA_TARGET_ID = "target_id"
    }

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var db: AppDatabase
        @Volatile private var surfaceReady = false
        @Volatile private var isVisible = false
        private val isSwitching = AtomicBoolean(false)
        private var currentBitmap: Bitmap? = null
        private var currentScaleMode: ScaleMode = ScaleMode.FIT

        // Shuffle tracking
        private val shuffleShownIds = ConcurrentHashMap.newKeySet<Long>()
        @Volatile private var shuffleAllCount = 0

        // Video state - ALL Canvas based, no EGL
        private var mediaCodecJob: Job? = null
        @Volatile private var videoPlaying = false
        @Volatile private var videoStopFlag = false
        private var lastVideoPtsUs = -1L
        private var videoSurfaceTexture: SurfaceTexture? = null
        private var videoBitmap: Bitmap? = null

        // GIF
        private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
        private var gifFrameRunnable: Runnable? = null
        private var gifBitmapBuffer: Bitmap? = null

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_SWITCH) {
                    val targetId = intent.getLongExtra(EXTRA_TARGET_ID, -1L)
                    Log.d(TAG, "Broadcast received, targetId=$targetId")
                    doSwitch("broadcast", if (targetId > 0) targetId else null)
                }
            }
        }

        private val gestureDetector = GestureDetector(
            applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    doSwitch("double-tap")
                    return true
                }
            }
        )

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
            val filter = IntentFilter(ACTION_SWITCH)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    applicationContext.registerReceiver(switchReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    applicationContext.registerReceiver(switchReceiver, filter)
                }
            } catch (_: Exception) {}
            Log.d(TAG, "Engine created")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            surfaceReady = true
            Log.d(TAG, "Surface created")
            drawCurrentImage()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {}

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            releaseAll()
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            Log.d(TAG, "Visibility: $visible")
            if (visible) {
                drawCurrentImage()
            } else {
                pauseMedia()
            }
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            releaseAll()
            scope.cancel()
            super.onDestroy()
        }

        // ======== Media control ========

        /**
         * Release ALL media resources. Canvas-based, no EGL to worry about.
         */
        private fun releaseAll() {
            videoPlaying = false
            videoStopFlag = true
            mediaCodecJob?.cancel()
            mediaCodecJob = null
            videoSurfaceTexture?.release()
            videoSurfaceTexture = null
            videoBitmap?.recycle()
            videoBitmap = null
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
            gifFrameRunnable = null
            try { gifDrawable?.stop() } catch (_: Exception) {}
            gifDrawable = null
            gifBitmapBuffer?.recycle()
            gifBitmapBuffer = null
        }

        /**
         * Stop video playback and wait for the codec thread to finish.
         * Prevents concurrent Canvas access.
         */
        private suspend fun stopVideoAndWait() {
            videoPlaying = false
            videoStopFlag = true
            val job = mediaCodecJob
            mediaCodecJob = null
            job?.cancel()
            try { job?.join() } catch (_: Exception) {}
            videoSurfaceTexture?.release()
            videoSurfaceTexture = null
            videoBitmap?.recycle()
            videoBitmap = null
        }

        private fun pauseMedia() {
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        }

        // ======== Switch logic ========

        private fun doSwitch(source: String, targetId: Long? = null) {
            if (!isSwitching.compareAndSet(false, true)) {
                Log.d(TAG, "Already switching, skip ($source)")
                return
            }
            Log.d(TAG, "doSwitch from $source, targetId=$targetId")

            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    val groupDao = db.wallpaperGroupDao()

                    val groups = groupDao.getEnabledGroupsSync()
                    if (groups.isEmpty()) {
                        Log.d(TAG, "No enabled groups")
                        return@launch
                    }

                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }

                    val nextImage = if (targetId != null && targetId > 0) {
                        imageDao.getImageById(targetId)
                    } else {
                        val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                        val switchMode = try {
                            SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                        } catch (_: Exception) { SwitchMode.RANDOM }
                        pickNextImage(switchMode, imageDao, lastId, dao)
                    }

                    if (nextImage == null) {
                        Log.d(TAG, "No next image")
                        return@launch
                    }

                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                    val mediaType = nextImage.mediaType ?: "IMAGE"
                    Log.d(TAG, "Switch to: ${nextImage.displayName} ($mediaType)")

                    when (mediaType) {
                        "VIDEO" -> {
                            stopVideoAndWait()
                            delay(30)
                            startVideoDecoder(nextImage.uri, currentScaleMode)
                        }
                        "GIF" -> {
                            stopVideoAndWait()
                            delay(30)
                            mainHandler.post { playGif(nextImage.uri, currentScaleMode) }
                        }
                        else -> {
                            stopVideoAndWait()
                            delay(30)
                            val bitmap = loadBitmap(nextImage.uri)
                            if (bitmap != null) {
                                mainHandler.post { showBitmap(bitmap, currentScaleMode) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "doSwitch error", e)
                } finally {
                    isSwitching.set(false)
                }
            }
        }

        private suspend fun pickNextImage(
            switchMode: SwitchMode,
            imageDao: WallpaperImageDao,
            lastId: Long,
            dao: SettingsDao
        ): WallpaperImage? {
            return when (switchMode) {
                SwitchMode.RANDOM -> {
                    imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                        ?: imageDao.getRandomImageFromEnabledGroups()
                }
                SwitchMode.SEQUENTIAL -> {
                    val count = imageDao.countByEnabledGroups()
                    if (count == 0) null
                    else {
                        val idx = dao.getLong(SettingsKeys.SEQUENTIAL_INDEX).toInt()
                        val next = idx % count
                        dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, (next + 1).toLong())
                        imageDao.getSequentialImageFromEnabledGroups(next)
                            ?: imageDao.getRandomImageFromEnabledGroups()
                    }
                }
                SwitchMode.SHUFFLE -> {
                    val totalCount = imageDao.countByEnabledGroups()
                    if (totalCount == 0) null
                    else {
                        if (shuffleAllCount != totalCount || shuffleShownIds.size >= totalCount) {
                            shuffleShownIds.clear()
                            shuffleAllCount = totalCount
                        }
                        var attempts = 0
                        var candidate: WallpaperImage? = null
                        while (attempts < 10 && candidate == null) {
                            val img = imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups()
                            if (img != null && img.id !in shuffleShownIds) {
                                candidate = img
                            } else if (img != null && shuffleShownIds.size >= totalCount) {
                                shuffleShownIds.clear()
                                candidate = img
                            }
                            attempts++
                        }
                        candidate?.also { shuffleShownIds.add(it.id) }
                    }
                }
            }
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            if (isSwitching.get()) return
            if (videoPlaying && mediaCodecJob?.isActive == true) return

            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }
                    val image = if (imageId > 0) db.wallpaperImageDao().getImageById(imageId) else null

                    if (image != null) {
                        when (image.mediaType ?: "IMAGE") {
                            "VIDEO" -> {
                                startVideoDecoder(image.uri, currentScaleMode)
                                return@launch
                            }
                            "GIF" -> {
                                mainHandler.post { playGif(image.uri, currentScaleMode) }
                                return@launch
                            }
                            else -> {
                                val bitmap = loadBitmap(image.uri)
                                if (bitmap != null) {
                                    mainHandler.post { showBitmap(bitmap, currentScaleMode) }
                                    return@launch
                                }
                            }
                        }
                    }
                    mainHandler.post { showDefault() }
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                }
            }
        }

        // ======== Video via MediaCodec → Bitmap → Canvas ========
        // No OpenGL, no EGL, no SurfaceTexture/Surface conflict with Canvas

        private fun startVideoDecoder(uriStr: String, scaleMode: ScaleMode) {
            videoPlaying = true
            videoStopFlag = false
            lastVideoPtsUs = -1L

            mediaCodecJob = scope.launch {
                try {
                    playVideoLoop(uriStr, scaleMode)
                } catch (e: Exception) {
                    Log.e(TAG, "Video playback error: ${e.message}")
                } finally {
                    videoPlaying = false
                }
            }
        }

        /**
         * Play video in a loop. Restarts automatically when reaching EOS.
         * Uses Canvas for ALL rendering - no EGL conflict with images/GIF.
         */
        private suspend fun playVideoLoop(uriStr: String, scaleMode: ScaleMode) {
            while (currentCoroutineContext().isActive && surfaceReady && !videoStopFlag) {
                if (!isVisible) {
                    delay(100)
                    continue
                }
                playVideoOnce(uriStr, scaleMode)
                // Loop: if EOS reached, restart from beginning
                if (!videoStopFlag && surfaceReady) {
                    delay(16) // Brief pause between loops
                }
            }
        }

        /**
         * Play video once using MediaCodec → SurfaceTexture → Bitmap → Canvas.
         * All rendering through Canvas. No EGL at all.
         */
        private suspend fun playVideoOnce(uriStr: String, scaleMode: ScaleMode) {
            lastVideoPtsUs = -1L
            if (!surfaceReady) return

            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                val uri = Uri.parse(uriStr)
                extractor = MediaExtractor()
                extractor.setDataSource(applicationContext, uri, null)

                // Find video track
                var trackIndex = -1
                var mime = ""
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val m = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/")) { trackIndex = i; mime = m; break }
                }
                if (trackIndex < 0) {
                    Log.e(TAG, "No video track in: $uriStr")
                    return
                }

                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val fps = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)

                // Create SurfaceTexture for MediaCodec output
                // We don't render via EGL - we convert to Bitmap and use Canvas
                videoSurfaceTexture?.release()
                videoSurfaceTexture = SurfaceTexture(0)
                videoBitmap?.recycle()
                videoBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                val inputSurface = Surface(videoSurfaceTexture!!)
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, inputSurface, null, 0)
                codec.start()
                inputSurface.release()

                Log.d(TAG, "Video started: $mime ${width}x${height} ${fps}fps")

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                val frameIntervalMs = (1000L / fps.coerceIn(1, 60))

                while (currentCoroutineContext().isActive && surfaceReady && !videoStopFlag && isVisible) {
                    // Feed compressed data to decoder
                    if (!inputDone) {
                        val inputIdx = codec.dequeueInputBuffer(10000L)
                        if (inputIdx >= 0) {
                            val buf = codec.getInputBuffer(inputIdx) ?: continue
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                // End of stream - signal loop restart
                                codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Get decoded frame
                    val outputIdx = codec.dequeueOutputBuffer(info, 10000L)
                    if (outputIdx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            codec.releaseOutputBuffer(outputIdx, false)
                            Log.d(TAG, "Video EOS, will loop")
                            return // Return to playVideoLoop for restart
                        }

                        // Update SurfaceTexture with new frame
                        codec.releaseOutputBuffer(outputIdx, true)
                        videoSurfaceTexture?.updateTexImage()

                        // Convert SurfaceTexture frame to Bitmap
                        val bmp = videoBitmap
                        val st = videoSurfaceTexture
                        if (bmp != null && st != null) {
                            val canvas = Canvas(bmp)
                            val texMatrix = FloatArray(16)
                            st.getTransformMatrix(texMatrix)
                            // Draw the OES texture to our Bitmap canvas
                            // Use a simple approach: draw the texture via a temporary GL context
                            // Actually, we need a different approach for pure Canvas mode
                            drawSurfaceTextureToBitmap(st, bmp)
                            // Now draw the Bitmap to wallpaper Canvas
                            showBitmapDirect(bmp, scaleMode)
                        }

                        // Frame pacing
                        val currentPtsUs = info.presentationTimeUs
                        if (lastVideoPtsUs >= 0) {
                            val deltaUs = currentPtsUs - lastVideoPtsUs
                            val deltaMs = deltaUs / 1000
                            if (deltaMs in 1..frameIntervalMs * 2) {
                                delay(deltaMs)
                            } else {
                                delay(frameIntervalMs)
                            }
                        }
                        lastVideoPtsUs = currentPtsUs
                    } else {
                        delay(1)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "playVideoOnce error: ${e.message}")
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
            }
        }

        /**
         * Draw SurfaceTexture content to Bitmap using a temporary EGL context.
         * This is a mini OpenGL render just for the pixel readback,
         * completely separate from the wallpaper Canvas.
         */
        private fun drawSurfaceTextureToBitmap(st: SurfaceTexture, dst: Bitmap) {
            try {
                // Use OpenGL to render SurfaceTexture to a temporary FBO,
                // then read pixels back to Bitmap
                val w = dst.width
                val h = dst.height

                // Minimal EGL setup for offscreen rendering
                val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val ver = IntArray(2)
                EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)

                val cfgAttr = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, 4, EGL14.EGL_SURFACE_TYPE, 4,
                    EGL14.EGL_NONE
                )
                val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val num = IntArray(1)
                EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, num, 0)
                val cfg = cfgs[0] ?: run {
                    EGL14.eglTerminate(eglDisplay)
                    return
                }

                val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                val eglCtx = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

                // Pbuffer surface for offscreen rendering
                val pbufAttr = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
                val pbufSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfg, pbufAttr)
                EGL14.eglMakeCurrent(eglDisplay, pbufSurface, pbufSurface, eglCtx)

                // Create OES texture
                val texIds = IntArray(1)
                android.opengl.GLES20.glGenTextures(1, texIds, 0)
                android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0])
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)

                // Attach texture to SurfaceTexture
                st.attachToGLContext(texIds[0])
                st.updateTexImage()

                // Simple shader to render OES texture
                val vs = """
                    attribute vec4 aPos;
                    attribute vec2 aTex;
                    varying vec2 vTex;
                    void main() { gl_Position = aPos; vTex = aTex; }
                """
                val fs = """
                    #extension GL_OES_EGL_image_external : require
                    precision mediump float;
                    varying vec2 vTex;
                    uniform samplerExternalOES uTex;
                    void main() { gl_FragColor = texture2D(uTex, vTex); }
                """
                val vsId = loadShader(android.opengl.GLES20.GL_VERTEX_SHADER, vs)
                val fsId = loadShader(android.opengl.GLES20.GL_FRAGMENT_SHADER, fs)
                val prog = android.opengl.GLES20.glCreateProgram()
                android.opengl.GLES20.glAttachShader(prog, vsId)
                android.opengl.GLES20.glAttachShader(prog, fsId)
                android.opengl.GLES20.glLinkProgram(prog)
                android.opengl.GLES20.glDeleteShader(vsId)
                android.opengl.GLES20.glDeleteShader(fsId)

                android.opengl.GLES20.glViewport(0, 0, w, h)
                android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
                android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
                android.opengl.GLES20.glUseProgram(prog)

                val vb = java.nio.ByteBuffer.allocateDirect(32).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                vb.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); vb.position(0)
                val tb = java.nio.ByteBuffer.allocateDirect(32).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                tb.put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); tb.position(0)

                val pLoc = android.opengl.GLES20.glGetAttribLocation(prog, "aPos")
                val tLoc = android.opengl.GLES20.glGetAttribLocation(prog, "aTex")
                val uLoc = android.opengl.GLES20.glGetUniformLocation(prog, "uTex")
                android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0])
                android.opengl.GLES20.glUniform1i(uLoc, 0)
                android.opengl.GLES20.glEnableVertexAttribArray(pLoc)
                android.opengl.GLES20.glVertexAttribPointer(pLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 0, vb)
                android.opengl.GLES20.glEnableVertexAttribArray(tLoc)
                android.opengl.GLES20.glVertexAttribPointer(tLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 0, tb)
                android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)

                // Read pixels from GL framebuffer to Bitmap
                val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
                android.opengl.GLES20.glReadPixels(0, 0, w, h, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, buf)
                buf.position(0)
                dst.copyPixelsFromBuffer(buf)

                // Cleanup
                st.detachFromGLContext()
                android.opengl.GLES20.glDeleteTextures(1, texIds, 0)
                android.opengl.GLES20.glDeleteProgram(prog)
                EGL14.eglDestroySurface(eglDisplay, pbufSurface)
                EGL14.eglDestroyContext(eglDisplay, eglCtx)
                EGL14.eglTerminate(eglDisplay)
            } catch (e: Exception) {
                Log.e(TAG, "drawSurfaceTextureToBitmap error: ${e.message}")
            }
        }

        private fun loadShader(type: Int, src: String): Int {
            val s = android.opengl.GLES20.glCreateShader(type)
            android.opengl.GLES20.glShaderSource(s, src)
            android.opengl.GLES20.glCompileShader(s)
            return s
        }

        /**
         * Draw Bitmap directly to wallpaper Canvas.
         * Used for video frames that have been converted from SurfaceTexture.
         */
        private fun showBitmapDirect(bitmap: Bitmap, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val m = getMetrics()
                val dest = calcDestRect(
                    bitmap.width.toFloat(), bitmap.height.toFloat(),
                    m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode
                )
                canvas.drawBitmap(bitmap, null, dest, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        // ======== GIF via Canvas ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) playGif28(uriStr, scaleMode)
                else loadBitmap(uriStr)?.let { showBitmap(it, scaleMode) }
            } catch (e: Exception) {
                loadBitmap(uriStr)?.let { showBitmap(it, scaleMode) }
            }
        }

        @android.annotation.TargetApi(28)
        private fun playGif28(uriStr: String, scaleMode: ScaleMode) {
            val source = ImageDecoder.createSource(contentResolver, Uri.parse(uriStr))
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                gifDrawable = drawable
                drawable.repeatCount = -1
                drawable.start()

                val frameW = drawable.intrinsicWidth.coerceAtLeast(1)
                val frameH = drawable.intrinsicHeight.coerceAtLeast(1)
                gifBitmapBuffer?.recycle()
                gifBitmapBuffer = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)

                val runnable = object : Runnable {
                    override fun run() {
                        if (!surfaceReady || !isVisible || gifDrawable == null) return
                        try {
                            val bmp = gifBitmapBuffer ?: return
                            bmp.eraseColor(Color.TRANSPARENT)
                            val cv = Canvas(bmp)
                            drawable.draw(cv)
                            showBitmapDirect(bmp, scaleMode)
                        } catch (_: Exception) {}
                        mainHandler.postDelayed(this, 33)
                    }
                }
                gifFrameRunnable = runnable
                mainHandler.post(runnable)
            }
        }

        // ======== Image rendering via Canvas ========

        private fun showBitmap(bitmap: Bitmap, scaleMode: ScaleMode = ScaleMode.FIT) {
            if (!surfaceReady) return
            try {
                currentBitmap?.recycle()
                currentBitmap = bitmap
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val m = getMetrics()
                val dest = calcDestRect(
                    bitmap.width.toFloat(), bitmap.height.toFloat(),
                    m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode
                )
                canvas.drawBitmap(bitmap, null, dest, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        private fun calcDestRect(bw: Float, bh: Float, sw: Float, sh: Float, scaleMode: ScaleMode): RectF {
            return when (scaleMode) {
                ScaleMode.FIT -> {
                    val r = bw / bh; val sr = sw / sh
                    val dw: Float; val dh: Float
                    if (r > sr) { dw = sw; dh = dw / r } else { dh = sh; dw = dh * r }
                    RectF((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f)
                }
                ScaleMode.FILL -> {
                    val r = bw / bh; val sr = sw / sh
                    val dw: Float; val dh: Float
                    if (r < sr) { dw = sw; dh = dw / r } else { dh = sh; dw = dh * r }
                    RectF((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f)
                }
                ScaleMode.STRETCH -> RectF(0f, 0f, sw, sh)
            }
        }

        private fun showDefault() {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.DKGRAY)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER
                }
                val m = getMetrics()
                canvas.drawText("Wallpaper Switcher", m.widthPixels / 2f, m.heightPixels / 2f, p)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        // ======== Utilities ========

        private fun loadBitmap(uriStr: String): Bitmap? {
            return com.wallpaperswitcher.engine.BitmapUtils.loadBitmap(applicationContext, uriStr)
        }

        private fun getMetrics(): android.util.DisplayMetrics {
            return com.wallpaperswitcher.engine.BitmapUtils.getScreenMetrics(applicationContext)
        }

        private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
            return if (containsKey(key)) getInteger(key) else default
        }
    }
}
