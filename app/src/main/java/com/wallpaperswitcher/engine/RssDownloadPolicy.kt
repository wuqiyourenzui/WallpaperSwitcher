package com.wallpaperswitcher.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.getString
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.data.setString
import com.wallpaperswitcher.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 订阅下载策略: decides whether the subscription importer may download right
 * now, and keeps today's byte counter.
 *
 * Two user-facing policies (设置 → 存储与流量):
 *  - 仅 Wi-Fi 下载 ([SettingsKeys.RSS_WIFI_ONLY]): metered networks are blocked;
 *  - 每日下载上限 ([SettingsKeys.RSS_DAILY_LIMIT_MB], 0 = unlimited): once the
 *    counter reaches the cap, further imports are refused until the next day.
 *
 * An import that already started is allowed to finish (the cap is checked
 * before it begins); its bytes are added to the counter when it ends. The pure
 * [decide] core is unit-tested; everything else is IO glue around the settings
 * DAO and ConnectivityManager.
 */
object RssDownloadPolicy {

    private const val TAG = "RssDownloadPolicy"
    private const val MB = 1024L * 1024L

    enum class Reason { OK, WIFI_ONLY, DAILY_LIMIT }

    data class Decision(val allowed: Boolean, val reason: Reason)

    /** Pure policy core (unit-tested): no Android types involved. */
    internal fun decide(
        wifiOnly: Boolean,
        metered: Boolean,
        usedBytes: Long,
        limitBytes: Long,
    ): Decision = when {
        wifiOnly && metered -> Decision(false, Reason.WIFI_ONLY)
        limitBytes > 0L && usedBytes >= limitBytes -> Decision(false, Reason.DAILY_LIMIT)
        else -> Decision(true, Reason.OK)
    }

    /** Whether a download may start now (also rolls the day over). */
    suspend fun check(context: Context): Decision = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.getInstance(context).settingsDao()
            val used = rollDay(dao)
            decide(
                wifiOnly = dao.getBool(SettingsKeys.RSS_WIFI_ONLY, false),
                metered = isMetered(context),
                usedBytes = used,
                limitBytes = dao.getLong(SettingsKeys.RSS_DAILY_LIMIT_MB, 0L) * MB,
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "check failed: ${t.javaClass.simpleName} - allowing the import")
            Decision(true, Reason.OK)
        }
    }

    /** Adds the bytes of a finished import to today's counter. */
    suspend fun record(context: Context, bytes: Long) {
        if (bytes <= 0L) return
        withContext(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getInstance(context).settingsDao()
                val used = rollDay(dao)
                dao.setLong(SettingsKeys.RSS_DOWNLOAD_BYTES, used + bytes)
            } catch (t: Throwable) {
                AppLog.w(TAG, "record failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /** Today's used bytes (also rolls the day over). 0 when unreadable. */
    suspend fun usedTodayBytes(context: Context): Long = withContext(Dispatchers.IO) {
        try {
            rollDay(AppDatabase.getInstance(context).settingsDao())
        } catch (_: Throwable) {
            0L
        }
    }

    /** Resets the counter when the stored day is not today; returns today's use. */
    private suspend fun rollDay(
        dao: com.wallpaperswitcher.data.SettingsDao,
    ): Long {
        val today = dayStamp()
        val used = dao.getLong(SettingsKeys.RSS_DOWNLOAD_BYTES, 0L)
        if (dao.getString(SettingsKeys.RSS_DOWNLOAD_DATE) == today) return used
        dao.setString(SettingsKeys.RSS_DOWNLOAD_DATE, today)
        dao.setLong(SettingsKeys.RSS_DOWNLOAD_BYTES, 0L)
        return 0L
    }

    private fun dayStamp(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * True when the active network is metered. No active network / unknown
     * capability reports `false` (not metered): the download itself will fail
     * with a network error then, which is the honest message.
     */
    private fun isMetered(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> false
            else -> !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    } catch (_: Throwable) {
        false
    }
}
