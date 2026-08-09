package com.wallpaperswitcher.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.engine.MediaScanner

/**
 * Periodic task: re-scans every folder that was imported with
 * "from folder" and inserts newly added images/videos into the group the
 * folder belongs to. Runs at the interval configured in Settings.
 */
class FolderAutoScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return try {
            val context = applicationContext
            val db = AppDatabase.getInstance(context)
            if (!db.settingsDao().getBool(SettingsKeys.AUTO_SCAN_ENABLED, false)) {
                return androidx.work.ListenableWorker.Result.success()
            }

            val imageDao = db.wallpaperImageDao()
            val paths = imageDao.getScannedFolderPaths()
            if (paths.isEmpty()) return androidx.work.ListenableWorker.Result.success()

            var inserted = 0
            for (row in paths) {
                val existing = imageDao.getUrisByGroup(row.groupId).toHashSet()
                val media = if (row.folderPath.startsWith("content://")) {
                    // Imported via the system folder picker (SAF tree URI).
                    MediaScanner.queryDocumentFolder(context, row.folderPath)
                } else {
                    // Imported via the scanned-folder list (MediaStore path).
                    MediaScanner.queryFolderMedia(context, row.folderPath)
                }
                val newItems = media.filter { it.uri !in existing }.map {
                    WallpaperImage(
                        groupId = row.groupId,
                        uri = it.uri,
                        displayName = it.displayName,
                        mediaType = it.mediaType,
                        isFromFolder = true,
                        folderPath = row.folderPath
                    )
                }
                if (newItems.isNotEmpty()) {
                    // 100 rows per INSERT stays under the 999 bound-variable
                    // limit of older SQLite builds (8 columns x 100 = 800).
                    newItems.chunked(100).forEach { batch ->
                        imageDao.insertAll(batch)
                        inserted += batch.size
                    }
                }
            }
            Log.d(TAG, "Auto-scan finished: $inserted new media")
            androidx.work.ListenableWorker.Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "Auto-scan failed", e)
            androidx.work.ListenableWorker.Result.retry()
        }
    }

    companion object {
        private const val TAG = "FolderAutoScanWorker"
    }
}
