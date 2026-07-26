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
import java.io.InputStream

/**
 * 壁纸引擎核心 - 负责加载、缩放、设置壁纸
 */
class WallpaperEngine(private val context: Context) {

    private val wallpaperManager = WallpaperManager.getInstance(context)
    private val db = AppDatabase.getInstance(context)

    /**
     * 切换到下一张壁纸
     */
    suspend fun switchToNext(): Boolean = withContext(Dispatchers.IO) {
        try {
            val settingsDao = db.settingsDao()
            val imageDao = db.wallpaperImageDao()

            // 获取启用的分组
            val enabledGroups = db.wallpaperGroupDao().getEnabledGroupsSync()
            if (enabledGroups.isEmpty()) {
                Log.w(TAG, "没有启用的分组")
                return@withContext false
            }

            // 确定切换模式（取第一个启用分组的模式）
            val primaryGroup = enabledGroups.first()
            val lastImageId = settingsDao.getLong(SettingsKeys.LAST_IMAGE_ID)

            val nextImage = when (primaryGroup.switchMode) {
                SwitchMode.RANDOM -> {
                    imageDao.getRandomImageExcluding(lastImageId)
                }
                SwitchMode.SEQUENTIAL -> {
                    getNextSequential(imageDao, settingsDao)
                }
                SwitchMode.SHUFFLE -> {
                    getNextShuffle(imageDao, settingsDao, lastImageId)
                }
            }

            if (nextImage == null) {
                Log.w(TAG, "没有可用的壁纸图片")
                return@withContext false
            }

            // 获取缩放模式
            val scaleMode = try {
                ScaleMode.valueOf(
                    settingsDao.getString(SettingsKeys.SERVICE_ENABLED, ScaleMode.FIT.name)
                )
            } catch (_: Exception) {
                ScaleMode.FIT
            }
            // 实际取分组的 scaleMode
            val actualScaleMode = primaryGroup.scaleMode

            // 设置壁纸
            val success = setWallpaper(nextImage.uri, actualScaleMode)
            if (success) {
                settingsDao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                Log.d(TAG, "壁纸切换成功: ${nextImage.displayName}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "切换壁纸失败", e)
            false
        }
    }

    /**
     * 顺序获取下一张
     */
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

    /**
     * 洗牌模式 - 随机不重复
     */
    private suspend fun getNextShuffle(
        imageDao: WallpaperImageDao,
        settingsDao: SettingsDao,
        excludeId: Long
    ): WallpaperImage? {
        val image = imageDao.getRandomImageExcluding(excludeId)
        if (image == null) {
            // 所有图片都用过了，重置
            return imageDao.getRandomImage()
        }
        return image
    }

    /**
     * 设置壁纸，支持缩放模式
     */
    private fun setWallpaper(uriString: String, scaleMode: ScaleMode): Boolean {
        return try {
            val uri = Uri.parse(uriString)

            when (scaleMode) {
                ScaleMode.FIT -> {
                    // 适应 - 直接设置，系统默认行为
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        wallpaperManager.setStream(stream)
                    }
                }
                ScaleMode.FILL, ScaleMode.STRETCH -> {
                    // 需要先解码再缩放
                    val scaled = decodeAndScale(uri, scaleMode)
                    if (scaled != null) {
                        wallpaperManager.setBitmap(scaled)
                        scaled.recycle()
                    } else {
                        // fallback
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            wallpaperManager.setStream(stream)
                        }
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
     * 解码并缩放图片
     */
    private fun decodeAndScale(uri: Uri, scaleMode: ScaleMode): Bitmap? {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            // 先获取图片尺寸
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            val imgW = options.outWidth
            val imgH = options.outHeight
            if (imgW <= 0 || imgH <= 0) return null

            // 计算 inSampleSize
            var sampleSize = 1
            while (imgW / sampleSize > screenW * 2 || imgH / sampleSize > screenH * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            val result = when (scaleMode) {
                ScaleMode.FILL -> {
                    // 填充：裁剪填满屏幕，保持比例
                    val imgRatio = decoded.width.toFloat() / decoded.height.toFloat()
                    val screenRatio = screenW.toFloat() / screenH.toFloat()

                    val cropW: Int
                    val cropH: Int
                    if (imgRatio > screenRatio) {
                        // 图片更宽，裁剪宽度
                        cropH = decoded.height
                        cropW = (cropH * screenRatio).toInt()
                    } else {
                        // 图片更高，裁剪高度
                        cropW = decoded.width
                        cropH = (cropW / screenRatio).toInt()
                    }

                    val cropX = (decoded.width - cropW) / 2
                    val cropY = (decoded.height - cropH) / 2

                    val cropped = Bitmap.createBitmap(decoded, cropX, cropY, cropW, cropH)
                    val scaled = Bitmap.createScaledBitmap(cropped, screenW, screenH, true)
                    if (cropped !== scaled) cropped.recycle()
                    if (decoded !== cropped) decoded.recycle()
                    scaled
                }
                ScaleMode.STRETCH -> {
                    // 拉伸：强制拉伸到屏幕尺寸
                    val scaled = Bitmap.createScaledBitmap(decoded, screenW, screenH, true)
                    if (decoded !== scaled) decoded.recycle()
                    scaled
                }
                else -> decoded
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "解码缩放图片失败", e)
            null
        }
    }

    companion object {
        private const val TAG = "WallpaperEngine"
    }
}
