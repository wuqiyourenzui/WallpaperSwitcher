package com.wallpaperswitcher.engine

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 壁纸引擎核心 - 负责加载、缩放、设置壁纸
 * 性能优化：RGB_565、激进降采样、直接流式写入
 */
class WallpaperEngine(private val context: Context) {

    private val wallpaperManager = WallpaperManager.getInstance(context)
    private val db = AppDatabase.getInstance(context)

    // 缓存屏幕尺寸，避免每次获取
    private var cachedScreenW = 0
    private var cachedScreenH = 0

    private fun getScreenSize(): Pair<Int, Int> {
        if (cachedScreenW == 0 || cachedScreenH == 0) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            cachedScreenW = metrics.widthPixels
            cachedScreenH = metrics.heightPixels
        }
        return Pair(cachedScreenW, cachedScreenH)
    }

    /**
     * 切换到下一张壁纸
     */
    suspend fun switchToNext(): Boolean = withContext(Dispatchers.IO) {
        try {
            val settingsDao = db.settingsDao()
            val imageDao = db.wallpaperImageDao()

            val enabledGroups = db.wallpaperGroupDao().getEnabledGroupsSync()
            if (enabledGroups.isEmpty()) {
                return@withContext false
            }

            val primaryGroup = enabledGroups.first()
            val lastImageId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID)

            val nextImage = when (primaryGroup.switchMode) {
                SwitchMode.RANDOM -> imageDao.getRandomImageExcluding(lastImageId)
                SwitchMode.SEQUENTIAL -> getNextSequential(imageDao, settingsDao)
                SwitchMode.SHUFFLE -> getNextShuffle(imageDao, settingsDao, lastImageId)
            }

            if (nextImage == null) {
                Log.w(TAG, "没有可用的壁纸图片")
                return@withContext false
            }

            val success = setWallpaperFast(nextImage.uri, primaryGroup.scaleMode)
            if (success) {
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "切换壁纸失败", e)
            false
        }
    }

    private suspend fun getNextSequential(
        imageDao: WallpaperImageDao,
        settingsDao: SettingsDao
    ): WallpaperImage? {
        val images = imageDao.getSequentialImages()
        if (images.isEmpty()) return null
        val currentIndex = settingsDao.getLong(SettingsKeys.SEQUENTIAL_INDEX).toInt()
        val nextIndex = (currentIndex + 1) % images.size
        settingsDao.setLong(SettingsKeys.SEQUENTIAL_INDEX, nextIndex.toLong())
        return images[nextIndex]
    }

    private suspend fun getNextShuffle(
        imageDao: WallpaperImageDao,
        settingsDao: SettingsDao,
        excludeId: Long
    ): WallpaperImage? {
        return imageDao.getRandomImageExcluding(excludeId) ?: imageDao.getRandomImage()
    }

    /**
     * 快速设置壁纸
     * FIT 模式：直接流式写入，零内存开销
     * FILL/STRETCH 模式：低质量快速解码缩放
     */
    private fun setWallpaperFast(uriString: String, scaleMode: ScaleMode): Boolean {
        return try {
            val uri = Uri.parse(uriString)

            if (scaleMode == ScaleMode.FIT) {
                // 最快路径：直接流式写入，系统处理缩放
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    wallpaperManager.setStream(stream)
                }
            } else {
                // FILL/STRETCH：快速解码缩放
                val bitmap = decodeFast(uri, scaleMode)
                if (bitmap != null) {
                    wallpaperManager.setBitmap(bitmap)
                    bitmap.recycle()
                } else {
                    // 回退到直接流式
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        wallpaperManager.setStream(stream)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "设置壁纸失败: $uriString", e)
            false
        }
    }

    /**
     * 快速解码图片
     * - RGB_565（无 alpha，内存减半）
     * - 激进降采样（目标不超过屏幕 1.5 倍）
     * - 直接裁剪/缩放到屏幕尺寸
     */
    private fun decodeFast(uri: Uri, scaleMode: ScaleMode): Bitmap? {
        return try {
            val (screenW, screenH) = getScreenSize()

            // 第一遍：只读尺寸
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }

            val imgW = boundsOptions.outWidth
            val imgH = boundsOptions.outHeight
            if (imgW <= 0 || imgH <= 0) return null

            // 激进降采样：目标不超过屏幕 1.5 倍
            var sampleSize = 1
            while (imgW / sampleSize > screenW * 3 / 2 || imgH / sampleSize > screenH * 3 / 2) {
                sampleSize *= 2
            }

            // 第二遍：解码
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // 内存减半，壁纸不需要 alpha
            }

            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            // 直接缩放到屏幕尺寸
            val result = when (scaleMode) {
                ScaleMode.FILL -> cropFill(decoded, screenW, screenH)
                ScaleMode.STRETCH -> {
                    val scaled = Bitmap.createScaledBitmap(decoded, screenW, screenH, true)
                    if (decoded !== scaled) decoded.recycle()
                    scaled
                }
                else -> decoded
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "快速解码失败", e)
            null
        }
    }

    /**
     * 填充裁剪：居中裁剪后缩放到屏幕尺寸
     */
    private fun cropFill(src: Bitmap, screenW: Int, screenH: Int): Bitmap {
        val imgRatio = src.width.toFloat() / src.height.toFloat()
        val screenRatio = screenW.toFloat() / screenH.toFloat()

        val cropW: Int
        val cropH: Int
        if (imgRatio > screenRatio) {
            cropH = src.height
            cropW = (cropH * screenRatio).toInt()
        } else {
            cropW = src.width
            cropH = (cropW / screenRatio).toInt()
        }

        val cropX = ((src.width - cropW) / 2).coerceAtLeast(0)
        val cropY = ((src.height - cropH) / 2).coerceAtLeast(0)

        val safeCropW = cropW.coerceAtMost(src.width - cropX)
        val safeCropH = cropH.coerceAtMost(src.height - cropY)

        val cropped = Bitmap.createBitmap(src, cropX, cropY, safeCropW, safeCropH)
        val scaled = Bitmap.createScaledBitmap(cropped, screenW, screenH, true)
        if (cropped !== scaled) cropped.recycle()
        if (src !== cropped) src.recycle()
        return scaled
    }

    companion object {
        private const val TAG = "WallpaperEngine"
    }
}
