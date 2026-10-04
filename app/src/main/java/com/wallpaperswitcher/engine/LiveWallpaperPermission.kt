package com.wallpaperswitcher.engine

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.wallpaperswitcher.util.AppLog

/**
 * HyperOS/MIUI's 「动态壁纸服务」 switch (app-op 10045).
 *
 * The system live-wallpaper screen ([android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER]
 * → `com.android.wallpaper.livepicker/.LiveWallpaperChange`) checks the
 * *wallpaper app's* app-op 10045 before it shows anything and calls `finish()`
 * the moment it is not exactly `MODE_ALLOWED`:
 *
 * ```
 * // decompiled LiveWallpaperChange.init() (Xiaomi Pad 25053RP5CC, HyperOS)
 * if (withoutChangePermission()) {
 *     Log.w("CHANGE_LIVE_WALLPAPER", "No permission to change wall paper");
 *     finish();
 *     return;
 * }
 * // withoutChangePermission(): skips system packages, then
 * // appOps.checkOpNoThrow(10045, info.uid, packageName) != MODE_ALLOWED
 * ```
 *
 * `startActivity` itself succeeds, so the app sees nothing: the picker window
 * flashes for ~20ms and the user is back on the group grid with no error and
 * no way to set the wallpaper. The switch lives under
 * 权限管理 → 其他权限 → 动态壁纸服务 (「设置相关」 group) and is off for a fresh
 * install (verified by reading `appops get` before/after enabling it).
 *
 * The op number is MIUI-private: on any other ROM 10045 either does not exist
 * (the read throws) or means something else, so both cases report "allowed".
 */
object LiveWallpaperPermission {

    private const val TAG = "LiveWallpaperPermission"

    /** MIUI's app-op behind 权限管理 → 其他权限 → 动态壁纸服务. */
    private const val MIUI_OP_LIVE_WALLPAPER = 10045

    private const val MIUI_PERM_EDITOR_ACTION = "miui.intent.action.APP_PERM_EDITOR"
    private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
    /** The app-permission page; 「其他权限」 (and thus the switch) is a row in it. */
    private const val MIUI_PERM_EDITOR =
        "com.miui.permcenter.permissions.PermissionsEditorActivity"
    /** Older MIUI builds used this class name. */
    private const val MIUI_PERM_EDITOR_LEGACY =
        "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
    private const val EXTRA_PKGNAME = "extra_pkgname"

    /**
     * Whether the system live-wallpaper picker will actually stay on screen.
     *
     * `false` only when this really is a MIUI/HyperOS ROM AND the op is
     * readable AND its mode is not `MODE_ALLOWED` - every uncertain case
     * (non-Xiaomi device, hidden op, SecurityException) reports `true`, so the
     * caller keeps the old behaviour instead of blocking a working device.
     */
    fun isSystemPickerAllowed(context: Context): Boolean =
        !isBlocked(
            manufacturer = Build.MANUFACTURER,
            opMode = if (isMiui()) miuiOpMode(context) else null
        )

    /**
     * Pure decision core (unit-tested): MIUI blocks unless the op is explicitly
     * allowed. `opMode == null` means "could not read it" and must never block.
     */
    internal fun isBlocked(manufacturer: String?, opMode: Int?): Boolean {
        if (!manufacturer.equals("Xiaomi", ignoreCase = true)) return false
        if (opMode == null) return false
        return opMode != AppOpsManager.MODE_ALLOWED
    }

    /** App-op 10045, or null when the ROM does not have it / forbids the read. */
    private fun miuiOpMode(context: Context): Int? {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return null
        return try {
            // The (int, int, String) overload still exists at runtime (it is
            // what the ROM's own picker calls) but is no longer exposed by the
            // SDK - only the String-op overload is - and MIUI's op 10045 has no
            // public name, so the int form is reached reflectively.
            val checkOp = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                String::class.java
            )
            checkOp.invoke(
                appOps,
                MIUI_OP_LIVE_WALLPAPER,
                context.applicationInfo.uid,
                context.packageName
            ) as? Int
        } catch (t: Throwable) {
            AppLog.d(TAG, "MIUI live-wallpaper app-op not readable: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * `ro.miui.ui.version.name` is the canonical MIUI/HyperOS marker; it needs
     * reflection because `android.os.SystemProperties` is hidden. Falls back to
     * the brand when reflection is unavailable.
     */
    private fun isMiui(): Boolean {
        val version: String? = try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val getter = systemProperties.getMethod("get", String::class.java)
            getter.invoke(null, "ro.miui.ui.version.name") as? String
        } catch (_: Throwable) {
            null
        }
        if (!version.isNullOrBlank()) return true
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    }

    /**
     * Opens the page that holds the 动态壁纸服务 switch: MIUI's per-app
     * permission editor (the user taps 「其他权限」 there). Falls back to the
     * plain app-details page when the security center is missing or renamed.
     *
     * @return whether a page was opened.
     */
    fun openPermissionEditor(context: Context): Boolean {
        for (editor in listOf(MIUI_PERM_EDITOR, MIUI_PERM_EDITOR_LEGACY)) {
            val intent = Intent(MIUI_PERM_EDITOR_ACTION).apply {
                setClassName(MIUI_SECURITY_CENTER, editor)
                putExtra(EXTRA_PKGNAME, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startSafely(context, intent)) return true
        }
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(context, details)
    }

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        AppLog.d(TAG, "cannot open ${intent.component}: ${t.javaClass.simpleName}")
        false
    }
}
