package com.wallpaperswitcher.engine

import android.content.Context
import android.graphics.Bitmap
import com.wallpaperswitcher.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * 离线 NN 超分（静态图，4x）：TensorFlow Lite + ESRGAN。
 *
 * 模型就是 TensorFlow 官方 super_resolution 示例用的那个（50x50 -> 200x200，
 * float32 RGB 0..255），由 `download.gradle` 指向 Google 官方地址。这里**不把模型
 * 打进 APK**，首次使用时下载到 `files/nn/esrgan.tflite`（4.99MB）——一来少 5MB
 * 安装包，二来避免再分发模型文件。
 *
 * 推理分块：50x50 一块，输出 200x200 贴回 4x 位图；**最后一行/列的块贴边对齐**
 * （而不是补黑边），这样既不会写出界，也不会在边缘引入黑边污染。
 *
 * GPU delegate 优先（官方示例的默认），构造或首次推理失败时自动退回 CPU 4 线程。
 */
object NnUpscaler {

    private const val TAG = "NnUpscaler"
    private const val MODEL_URL =
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/esrgan/ESRGAN.tflite"
    internal const val MODEL_BYTES = 4_993_712L
    internal const val PATCH = 50
    internal const val SCALE = 4
    internal const val OUT_PATCH = PATCH * SCALE

    /**
     * 超过这个输入面积就拒绝：4x 输出的位图会太大（例：640x480 -> 2560x1920 =
     * 19.6MB 位图，还能接受；再大就明显不值得且容易 OOM）。360p（640x360）在
     * 范围内，正是要处理的低清素材。
     */
    const val MAX_INPUT_PIXELS = 640L * 480L

    private val mutex = Mutex()

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null

    data class Output(val file: File, val uri: String)

    /** 结果文件的确定性路径：同一张源图重复超分直接复用。 */
    fun outputFile(context: Context, sourceUri: String): Output {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(sourceUri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "nn").apply { mkdirs() }
        val file = File(dir, "${hash}_x4.jpg")
        return Output(file, "file://${file.absolutePath}")
    }

    /** 模型是否已经下载好。 */
    fun modelReady(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() == MODEL_BYTES
    }

    /**
     * 确保模型存在（首次约 4.99MB，从 Google 官方地址下载）。返回 false 表示
     * 下载/校验失败，调用方提示用户而不是让超分"静默失败"。
     */
    suspend fun ensureModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "nn").apply { mkdirs() }
        val target = File(dir, "esrgan.tflite")
        if (target.isFile && target.length() == MODEL_BYTES) return@withContext true
        target.delete()
        val tmp = File(dir, "esrgan.tflite.part")
        try {
            val request = Request.Builder().url(MODEL_URL).build()
            RssHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "model download failed: http ${response.code}")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output, 256 * 1024)
                    }
                }
            }
            if (tmp.length() != MODEL_BYTES) {
                AppLog.w(TAG, "model size mismatch: ${tmp.length()} != $MODEL_BYTES")
                tmp.delete()
                return@withContext false
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            AppLog.d(TAG, "model ready (${target.length()} bytes)")
            true
        } catch (t: Throwable) {
            tmp.delete()
            AppLog.w(TAG, "model download failed: ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * 对 [source] 做 4x NN 超分（后台线程，进程内串行）。输入超过
     * [MAX_INPUT_PIXELS]、模型缺失或推理失败时返回 null。
     */
    suspend fun upscale(
        context: Context,
        source: Bitmap,
        onProgress: (Float) -> Unit = {},
    ): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                val w = source.width
                val h = source.height
                if (w <= 0 || h <= 0) return@withLock null
                if (w.toLong() * h > MAX_INPUT_PIXELS) return@withLock null
                val model = modelFile(context)
                if (!model.isFile) return@withLock null
                var interp = interpreter ?: createInterpreter(model).also { interpreter = it }
                val srcPixels = IntArray(w * h)
                source.getPixels(srcPixels, 0, w, 0, 0, w, h)
                val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)
                val input = ByteBuffer.allocateDirect(PATCH * PATCH * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                val output = ByteBuffer.allocateDirect(OUT_PATCH * OUT_PATCH * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                val tilePixels = IntArray(OUT_PATCH * OUT_PATCH)
                val xs = tileOrigins(w, PATCH)
                val ys = tileOrigins(h, PATCH)
                val total = (xs.size * ys.size).coerceAtLeast(1)
                var done = 0
                for (y0 in ys) {
                    for (x0 in xs) {
                        // The model contract (official sample): float RGB 0..255.
                        input.clear()
                        for (j in 0 until PATCH) {
                            val sy = (y0 + j).coerceAtMost(h - 1)
                            for (i in 0 until PATCH) {
                                val sx = (x0 + i).coerceAtMost(w - 1)
                                val color = srcPixels[sy * w + sx]
                                input.putFloat(((color shr 16) and 0xFF).toFloat())
                                input.putFloat(((color shr 8) and 0xFF).toFloat())
                                input.putFloat((color and 0xFF).toFloat())
                            }
                        }
                        input.rewind()
                        output.rewind()
                        try {
                            interp.run(input, output)
                        } catch (t: Throwable) {
                            // GPU delegates can build fine and still fail on the
                            // first run on some drivers: rebuild on CPU and retry
                            // this tile once.
                            if (gpuDelegate == null) throw t
                            AppLog.w(TAG, "GPU run failed, switching to CPU: ${t.javaClass.simpleName}")
                            interp = rebuildCpuInterpreter(model)
                            input.rewind()
                            output.rewind()
                            interp.run(input, output)
                        }
                        output.rewind()
                        for (k in 0 until OUT_PATCH * OUT_PATCH) {
                            val r = output.getFloat().toInt().coerceIn(0, 255)
                            val g = output.getFloat().toInt().coerceIn(0, 255)
                            val b = output.getFloat().toInt().coerceIn(0, 255)
                            tilePixels[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        val outX = (x0 * SCALE).coerceAtMost(out.width - OUT_PATCH)
                        val outY = (y0 * SCALE).coerceAtMost(out.height - OUT_PATCH)
                        out.setPixels(tilePixels, 0, OUT_PATCH, outX, outY, OUT_PATCH, OUT_PATCH)
                        done++
                        onProgress(done.toFloat() / total)
                    }
                }
                out
            } catch (t: Throwable) {
                AppLog.w(TAG, "upscale failed: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
                null
            }
        }
    }

    /**
     * 分块起点：从 0 开始每 [patch] 一块；最后一块若会越界就**贴边对齐**
     * （与上一块重叠），而不是补边——补边会把黑/复制像素喂给模型，在边缘留下痕迹。
     */
    internal fun tileOrigins(size: Int, patch: Int = PATCH): List<Int> {
        if (size <= 0 || patch <= 0) return emptyList()
        if (size <= patch) return listOf(0)
        val origins = ArrayList<Int>(size / patch + 1)
        var origin = 0
        while (origin < size) {
            origins.add(origin)
            origin += patch
        }
        val last = origins.last()
        if (last + patch > size) origins[origins.size - 1] = size - patch
        return origins
    }

    private fun modelFile(context: Context) = File(context.filesDir, "nn/esrgan.tflite")

    private fun createInterpreter(model: File): Interpreter {
        val gpuOptions = Interpreter.Options().apply { setNumThreads(4) }
        val delegate = try {
            GpuDelegate().also { gpuOptions.addDelegate(it) }
        } catch (t: Throwable) {
            AppLog.w(TAG, "GPU delegate unavailable: ${t.javaClass.simpleName}")
            null
        }
        if (delegate != null) {
            try {
                gpuDelegate = delegate
                return Interpreter(model, gpuOptions)
            } catch (t: Throwable) {
                AppLog.w(TAG, "GPU interpreter failed: ${t.javaClass.simpleName}")
                try { delegate.close() } catch (_: Throwable) {}
                gpuDelegate = null
            }
        }
        return Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
    }

    private fun rebuildCpuInterpreter(model: File): Interpreter {
        try { interpreter?.close() } catch (_: Throwable) {}
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        gpuDelegate = null
        val cpu = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        interpreter = cpu
        return cpu
    }
}
