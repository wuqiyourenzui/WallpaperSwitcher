package com.wallpaperswitcher.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.wallpaperswitcher.util.AppLog
import java.io.FileNotFoundException

/**
 * Shared "can this media still be read?" probe.
 *
 * The live engine and the static applier each carried their own copy of the
 * same FileNotFoundException / SecurityException / IllegalArgumentException
 * classification, and the two drifted apart over time. The answer decides
 * whether a media row is dropped as permanently gone or kept for a later retry.
 */
object MediaProbe {

    /** One-shot log flag for the "no read permission" diagnosis (see below). */
    private val permissionWarningLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * True when [uriStr] cannot be opened because the FILE itself is gone
     * (deleted or moved). Those failures are permanent, so the caller drops the
     * row instead of re-reading it on every tick (the device log showed a single
     * missing photo being re-read 50+ times, which is the "the app keeps
     * touching my photos" report).
     *
     * A permission failure is explicitly NOT "gone": it only means this app may
     * not read the file right now - typically the READ_MEDIA_* permission was
     * never granted, or the system reset it after an update. Treating that as
     * "deleted" made the switch loops DELETE the user's media rows one by one
     * (92 rows on the test tablet, every one of them still present in the
     * gallery). The row is kept and the failure is logged instead.
     *
     * Transient problems (a busy provider, a decode timeout, low memory) return
     * false as well, so the media is kept.
     */
    fun isGone(context: Context, uriStr: String): Boolean {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriStr))?.close()
            false
        } catch (e: FileNotFoundException) {
            // Providers report a missing read permission as a FileNotFoundException
            // too ("Permission denied"), so the message decides.
            if (looksLikePermissionDenial(e.message)) {
                logPermissionWarningIfNeeded(context)
                false
            } else {
                true
            }
        } catch (_: SecurityException) {
            logPermissionWarningIfNeeded(context)
            false
        } catch (_: IllegalArgumentException) {
            // A malformed/unsupported URI never becomes readable, but it is no
            // proof that the user's file is gone either: keep the row and let the
            // manual "清理失效" action decide.
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when this app currently holds a read permission for media owned by
     * other apps. MediaStore-imported rows (content://media/...) need it; SAF /
     * document imports carry their own per-URI grant and keep working without it.
     */
    fun hasReadMediaPermission(context: Context): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                // "选择部分照片" partial grant (Android 14+).
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.any { permission ->
            try {
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Whether a provider error message means "you may not read this" rather
     * than "this file is gone". Providers differ: some throw a
     * FileNotFoundException whose message says "Permission denied", others a
     * SecurityException like "has no access to content://... forWrite = false".
     * Getting this wrong deletes the user's media rows, so it is unit-tested.
     */
    internal fun looksLikePermissionDenial(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return "permission" in text || "denied" in text || "forwrite" in text
    }

    /** Explain the failure once per process instead of on every tick. */
    private fun logPermissionWarningIfNeeded(context: Context) {
        if (hasReadMediaPermission(context)) return
        if (!permissionWarningLogged.compareAndSet(false, true)) return
        AppLog.w(
            "MediaProbe",
            "缺少照片/视频读取权限：媒体库里的图片无法读取，媒体条目已保留（不会删除）。" +
                "在系统设置里授予本应用照片/视频权限后即可正常切换。"
        )
    }
}
