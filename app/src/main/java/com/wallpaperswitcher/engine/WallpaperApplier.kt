package com.wallpaperswitcher.engine

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setLong

/**
 * Applies wallpapers to the system wallpaper via [WallpaperManager].
 *
 * Used in "static wallpaper" mode: when the live wallpaper engine is NOT
 * running, scheduled switches and "set as wallpaper" actions go through here.
 * Videos and GIFs are rendered as their first frame.
 */
object WallpaperApplier {

    private const val TAG = "WallpaperApplier"

    /**
     * Render a single media item as the system wallpaper.
     *
     * @return true when the wallpaper was applied successfully.
     */
    fun apply(context: Context, image: WallpaperImage): Boolean {
        val bitmap = when (image.mediaType) {
            "VIDEO" -> videoFrame(context, image.uri)
            "GIF" -> gifFirstFrame(context, image.uri)
            else -> BitmapUtils.loadBitmap(context, image.uri)
        }
        if (bitmap == null) {
            Log.e(TAG, "No bitmap for: ${image.displayName} uri=${image.uri}")
            return false
        }

        var applied = false
        try {
            applied = WallpaperManager.getInstance(context).setBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "apply failed for ${image.displayName}", e)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        if (applied) {
            Log.d(TAG, "Wallpaper applied: ${image.displayName}")
        } else {
            Log.e(TAG, "WallpaperManager.setBitmap returned false: ${image.displayName}")
        }
        return applied
    }

    /**
     * Pick the next media (honoring the global switch mode) and apply it.
     * Call from a background coroutine.
     */
    suspend fun applyNext(context: Context): Boolean {
        val db = AppDatabase.getInstance(context)
        val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
        if (groups.isEmpty()) return false

        val imageDao = db.wallpaperImageDao()
        val dao = db.settingsDao()
        val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
        val mode = try {
            SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
        } catch (_: Exception) {
            SwitchMode.RANDOM
        }

        val image = when (mode) {
            SwitchMode.RANDOM, SwitchMode.SHUFFLE -> {
                imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                    ?: imageDao.getRandomImageFromEnabledGroups()
            }
            SwitchMode.SEQUENTIAL -> {
                val count = imageDao.countByEnabledGroups()
                if (count == 0) null else {
                    val idx = dao.getLong(SettingsKeys.SEQUENTIAL_INDEX).toInt()
                    val next = idx % count
                    imageDao.getSequentialImageFromEnabledGroups(next)?.also {
                        dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, (next + 1).toLong())
                    }
                }
            }
        } ?: imageDao.getFirstFromEnabledGroups() ?: return false

        dao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
        return apply(context, image)
    }

    private fun videoFrame(context: Context, uriStr: String): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.parse(uriStr))
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime()
        } catch (e: Exception) {
            Log.e(TAG, "videoFrame failed: $uriStr", e)
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    private fun gifFirstFrame(context: Context, uriStr: String): Bitmap? {
        val uri = Uri.parse(uriStr)
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    drawable.draw(Canvas(bmp))
                    bmp
                } finally {
                    try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
            } else {
                BitmapUtils.loadBitmap(context, uriStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "gifFirstFrame failed: $uriStr", e)
            null
        }
    }
}
