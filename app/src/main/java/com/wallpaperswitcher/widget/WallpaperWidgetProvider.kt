package com.wallpaperswitcher.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.util.logCoroutineFailures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面小组件: two one-tap actions on the home screen itself.
 *
 * - 切换: the same "switch now" as the home screen button / the QS tile;
 * - 暂停 / 继续: toggles the 一键暂停 hold (24h when pausing) and re-renders
 *   the button label from the stored state.
 */
class WallpaperWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + logCoroutineFailures(TAG)
    )

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        scope.launch {
            val views = buildViews(context, pausedNow(context))
            fillStatus(context, views)
            try {
                for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
            } catch (t: Throwable) {
                AppLog.e(TAG, "widget update failed", t)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIDGET_SWITCH -> {
                AppLog.d(TAG, "Widget: switch now")
                WallpaperSwitchService.switchNow(context)
            }
            ACTION_WIDGET_TOGGLE_PAUSE -> {
                scope.launch {
                    try {
                        val dao = AppDatabase.getInstance(context).settingsDao()
                        val now = System.currentTimeMillis()
                        val paused = dao.getLong(SettingsKeys.PAUSE_UNTIL) > now
                        if (paused) {
                            dao.setLong(SettingsKeys.PAUSE_UNTIL, 0L)
                            AppLog.d(TAG, "Widget: resume")
                        } else {
                            dao.setLong(SettingsKeys.PAUSE_STARTED_AT, now)
                            dao.setLong(SettingsKeys.PAUSE_UNTIL, now + PAUSE_WIDGET_DURATION_MS)
                            AppLog.d(TAG, "Widget: pause")
                        }
                        WallpaperSwitchService.poke(context)
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "widget pause toggle failed", t)
                    }
                    refreshAll(context)
                }
            }
        }
        super.onReceive(context, intent)
    }

    /** True while the 一键暂停 hold is active (the pause button then reads 继续). */
    private suspend fun pausedNow(context: Context): Boolean = try {
        AppDatabase.getInstance(context).settingsDao()
            .getLong(SettingsKeys.PAUSE_UNTIL) > System.currentTimeMillis()
    } catch (_: Exception) {
        false
    }

    private fun buildViews(context: Context, paused: Boolean): RemoteViews {
        val localized = AppLocale.localized(context)
        val views = RemoteViews(context.packageName, R.layout.widget_wallpaper)
        views.setTextViewText(R.id.widget_title, localized.getString(R.string.widget_switch))
        views.setTextViewText(R.id.widget_switch, localized.getString(R.string.widget_switch))
        // The button shows what tapping it will do: 继续 while paused.
        views.setTextViewText(
            R.id.widget_pause,
            localized.getString(
                if (paused) R.string.widget_resume else R.string.widget_pause
            )
        )
        views.setOnClickPendingIntent(R.id.widget_switch, pending(context, ACTION_WIDGET_SWITCH, 1))
        views.setOnClickPendingIntent(
            R.id.widget_pause,
            pending(context, ACTION_WIDGET_TOGGLE_PAUSE, 2)
        )
        return views
    }

    /**
     * Current wallpaper name + "下次切换" countdown.
     *
     * The countdown is an [android.widget.Chronometer] in count-down mode: the
     * widget itself ticks it, so no per-second update from the app is needed
     * (widget updates are expensive and rate-limited).
     */
    private suspend fun fillStatus(context: Context, views: RemoteViews) {
        try {
            val db = AppDatabase.getInstance(context)
            val dao = db.settingsDao()
            val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
            val name = if (lastId > 0L) {
                db.wallpaperImageDao().getImageById(lastId)?.displayName.orEmpty()
            } else {
                ""
            }
            views.setTextViewText(
                R.id.widget_current,
                name.ifEmpty { AppLocale.localized(context).getString(R.string.widget_current_unknown) }
            )
            val interval = dao.getLong(SettingsKeys.GLOBAL_INTERVAL_MS, 60_000L)
            val anchor = dao.getLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, 0L)
            if (anchor > 0L) {
                views.setViewVisibility(R.id.widget_next, android.view.View.VISIBLE)
                views.setChronometerCountDown(R.id.widget_next, true)
                views.setChronometer(
                    R.id.widget_next,
                    anchor + interval,
                    null,
                    true
                )
            } else {
                views.setViewVisibility(R.id.widget_next, android.view.View.GONE)
            }
        } catch (_: Exception) {
            views.setViewVisibility(R.id.widget_next, android.view.View.GONE)
        }
    }

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, WallpaperWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "WallpaperWidget"
        const val ACTION_WIDGET_SWITCH = "com.wallpaperswitcher.ACTION_WIDGET_SWITCH"
        const val ACTION_WIDGET_TOGGLE_PAUSE =
            "com.wallpaperswitcher.ACTION_WIDGET_TOGGLE_PAUSE"
        private const val PAUSE_WIDGET_DURATION_MS = 24L * 60 * 60 * 1000

        /**
         * Re-render every placed widget (label of the pause button, and after a
         * pause/resume that came from the widget itself).
         */
        suspend fun refreshAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, WallpaperWidgetProvider::class.java)
                )
                if (ids.isEmpty()) return
                val provider = WallpaperWidgetProvider()
                val views = provider.buildViews(context, provider.pausedNow(context))
                provider.fillStatus(context, views)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (t: Throwable) {
                AppLog.e(TAG, "refreshAll failed", t)
            }
        }
    }
}
