package com.wallpaperswitcher.worker

import com.wallpaperswitcher.util.AppLog

import com.wallpaperswitcher.util.LogText

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.engine.MediaScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        // The periodic job and the app-open catch-up can fire at the same
        // moment (seen on the tablet: two scans of the same folders in the same
        // second). Both would then compute "these files are new" from the same
        // pre-insert state and insert every file twice, so serialize them.
        return scanMutex.withLock { runScan() }
    }

    private suspend fun runScan(): androidx.work.ListenableWorker.Result {
        return try {
            val context = applicationContext
            val db = AppDatabase.getInstance(context)
            if (!db.settingsDao().getBool(SettingsKeys.AUTO_SCAN_ENABLED, false)) {
                // Logged (not silent): otherwise "did auto-scan run?" cannot be
                // answered from an exported log.
                AppLog.d(TAG, "Auto-scan skipped: the setting is off")
                return androidx.work.ListenableWorker.Result.success()
            }

            val imageDao = db.wallpaperImageDao()
            val paths = imageDao.getScannedFolderPaths()
            if (paths.isEmpty()) {
                AppLog.d(TAG, "Auto-scan: no folder-imported media to re-scan")
                markRan(db)
                return androidx.work.ListenableWorker.Result.success()
            }

            // Nothing in the media store changed since the last completed run, so
            // re-querying every imported folder cannot find anything new: skip the
            // whole run with a single cheap call. SAF tree folders
            // (content://...) are not covered by the generation, so their presence
            // disables the shortcut.
            val allMediaStoreFolders = paths.all { !it.folderPath.startsWith("content://") }
            val generation = if (allMediaStoreFolders) {
                MediaScanner.currentGeneration(context)
            } else {
                Long.MIN_VALUE
            }
            if (generation != Long.MIN_VALUE &&
                db.settingsDao().getLong(SettingsKeys.AUTO_SCAN_LAST_GENERATION, -1L) == generation
            ) {
                AppLog.d(
                    TAG,
                    "Auto-scan skipped: media store unchanged (gen=$generation), " +
                        "${paths.size} folders"
                )
                markRan(db)
                return androidx.work.ListenableWorker.Result.success()
            }

            var inserted = 0
            // Fetch each group's URI set ONCE (not per folder): a group with
            // dozens of imported folders used to re-read all of its URIs for
            // every folder, which is O(folders x group media) on big libraries.
            val existingByGroup = mutableMapOf<Long, MutableSet<String>>()
            for (row in paths) {
                val existing = existingByGroup.getOrPut(row.groupId) {
                    imageDao.getUrisByGroup(row.groupId).toHashSet()
                }
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
                        folderPath = row.folderPath,
                        // Free decode metadata from the MediaStore projection.
                        width = it.width,
                        height = it.height,
                        rotationDegrees = it.rotationDegrees
                    )
                }
                if (newItems.isNotEmpty()) {
                    // Keep the in-memory set in sync so a second folder of the
                    // same group cannot insert a duplicate URI.
                    newItems.forEach { existing.add(it.uri) }
                    // One transaction per folder: 10k new files would otherwise
                    // commit 100 separate transactions. Chunking stays (100 rows
                    // per INSERT is under the 999 bound-variable limit of older
                    // SQLite builds), but all chunks commit atomically.
                    db.withTransaction {
                        newItems.chunked(100).forEach { batch ->
                            imageDao.insertAll(batch)
                            inserted += batch.size
                        }
                    }
                }
                AppLog.d(
                    TAG,
                    "Auto-scan folder ${LogText.folder(row.folderPath)}: ${media.size} items, " +
                        "${newItems.size} new"
                )
            }
            AppLog.d(TAG, "Auto-scan finished: $inserted new media from ${paths.size} folders")
            // Remember the generation this run observed: the next run skips
            // entirely while the store stays unchanged.
            if (generation != Long.MIN_VALUE) {
                db.settingsDao().setLong(SettingsKeys.AUTO_SCAN_LAST_GENERATION, generation)
            }
            // Recorded AFTER the scan: a run that dies halfway must not look
            // completed, otherwise the app-open catch-up would wait a whole
            // interval before retrying.
            markRan(db)
            androidx.work.ListenableWorker.Result.success()
        } catch (e: CancellationException) {
            // WorkManager stop request: never report as a retryable failure.
            throw e
        } catch (e: Throwable) {
            AppLog.e(TAG, "Auto-scan failed", e)
            // Deliberately NOT retry(): a permanent problem (media permission
            // revoked, provider gone, DB unavailable) would otherwise re-run
            // with WorkManager backoff forever, waking the device every few
            // minutes. The periodic schedule re-runs at the next interval, and
            // the app-open catch-up retries sooner when the user is present.
            androidx.work.ListenableWorker.Result.success()
        }
    }

    /** Remember when the scan ran (Settings shows it; the catch-up compares it). */
    private suspend fun markRan(db: AppDatabase) {
        try {
            db.settingsDao().setLong(SettingsKeys.AUTO_SCAN_LAST_RUN_AT, System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "FolderAutoScanWorker"
        /** Serializes concurrent scans inside this process. */
        private val scanMutex = Mutex()
    }
}
