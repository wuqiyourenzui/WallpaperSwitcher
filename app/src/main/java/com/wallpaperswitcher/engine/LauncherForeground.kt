package com.wallpaperswitcher.engine

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.wallpaperswitcher.util.AppLog

/**
 * 判断"桌面（launcher）当前是否在前台"。
 *
 * 悬浮按钮只在桌面显示：其它 App 打开时它也浮在别人界面上面（用户报告）。
 * WallpaperService 的 visibility 回调在这台 ROM 上会抖动（桌面静止时一直报
 * false），所以只能靠 UsageStats 查"最近一个切到前台的包是不是 launcher"。
 *
 * 需要用户在系统设置里给"使用情况访问"权限（`PACKAGE_USAGE_STATS` appop）。
 * 没有权限时**一律返回 false**（宁可不显示，也不要浮在别人的 App 上）。
 */
internal object LauncherForeground {

    private const val TAG = "LauncherForeground"
    private const val LOOKBACK_MS = 60_000L

    private var cachedLauncherPkg: String? = null

    fun hasPermission(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    /** launcher 的包名（解析 HOME intent）。 */
    fun launcherPackage(context: Context): String? {
        cachedLauncherPkg?.let { return it }
        val pkg = try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        } catch (_: Throwable) {
            null
        }
        cachedLauncherPkg = pkg
        return pkg
    }

    /**
     * true = 桌面在前台。无权限 / 查不到事件时返回 false（安全侧：不显示按钮）。
     */
    fun isLauncherForeground(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val launcher = launcherPackage(context) ?: return false
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - LOOKBACK_MS, now)
            val event = UsageEvents.Event()
            var lastForegroundPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    lastForegroundPkg = event.packageName
                }
            }
            lastForegroundPkg == launcher
        } catch (t: Throwable) {
            AppLog.w(TAG, "queryEvents failed: ${t.javaClass.simpleName}")
            false
        }
    }
}
