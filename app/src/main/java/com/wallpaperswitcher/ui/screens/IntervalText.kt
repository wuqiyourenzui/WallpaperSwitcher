package com.wallpaperswitcher.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wallpaperswitcher.R

/**
 * Localised unit a decomposed interval part is rendered in.
 *
 * The *arithmetic* (which unit, how many) lives in the pure [intervalParts] /
 * [agoParts] functions so it stays unit-testable; only the labels come from
 * resources, which is what makes the same numbers readable in every language.
 */
enum class IntervalUnit(@StringRes val labelRes: Int) {
    SECONDS(R.string.interval_seconds),
    MINUTES(R.string.interval_minutes),
    HOURS(R.string.interval_hours),
    DAYS(R.string.interval_days),

    /**
     * Secondary part of a two-part interval ("2 小时 **30 分**"): a short label,
     * and in English a leading space so "2h 30m" reads properly.
     */
    MINUTES_SHORT(R.string.interval_minutes_short),
    HOURS_SHORT(R.string.interval_hours_short);

    /** Resource used when the same unit is rendered as "5 分钟前". */
    @get:StringRes
    val agoLabelRes: Int
        get() = when (this) {
            MINUTES, MINUTES_SHORT -> R.string.ago_minutes
            HOURS, HOURS_SHORT -> R.string.ago_hours
            else -> R.string.ago_days
        }
}

/** An interval split into "2 小时" + "30 分" (secondaryUnit null when exact). */
data class IntervalParts(
    val primaryValue: Long,
    val primaryUnit: IntervalUnit,
    val secondaryValue: Long = 0L,
    val secondaryUnit: IntervalUnit? = null
)

fun intervalParts(ms: Long): IntervalParts {
    // Clamped: intervals are always >= 10s, but a corrupt/negative stored value
    // must still render as a sane label instead of "-1秒".
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        totalSeconds < 60 -> IntervalParts(totalSeconds, IntervalUnit.SECONDS)
        minutes < 60 -> IntervalParts(minutes, IntervalUnit.MINUTES)
        hours < 24 -> IntervalParts(
            hours,
            IntervalUnit.HOURS,
            minutes % 60,
            if (minutes % 60 > 0) IntervalUnit.MINUTES_SHORT else null
        )
        else -> IntervalParts(
            days,
            IntervalUnit.DAYS,
            hours % 24,
            if (hours % 24 > 0) IntervalUnit.HOURS_SHORT else null
        )
    }
}

/** Decomposed age of a past wall-clock timestamp (0 = never). */
sealed interface AgoParts {
    /** The reference timestamp was never recorded. */
    data object Never : AgoParts
    /** Under a minute. */
    data object JustNow : AgoParts
    /** [value] [unit] ago; [unit] is MINUTES / HOURS / DAYS. */
    data class Count(val value: Long, val unit: IntervalUnit) : AgoParts
}

/**
 * "3 分钟前 / 2 小时前 / 1 天前" style decomposition. Used by Settings to show
 * when the folder auto-scan last ran, so "is it working?" can be answered at a
 * glance.
 */
fun agoParts(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): AgoParts {
    if (timestampMs <= 0L) return AgoParts.Never
    val elapsed = (nowMs - timestampMs).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    return when {
        minutes < 1L -> AgoParts.JustNow
        minutes < 60L -> AgoParts.Count(minutes, IntervalUnit.MINUTES)
        minutes < 60L * 24L -> AgoParts.Count(minutes / 60L, IntervalUnit.HOURS)
        else -> AgoParts.Count(minutes / (60L * 24L), IntervalUnit.DAYS)
    }
}

/** "10 秒 / 2 小时 30 分 / 1 天 6 小时" for a switch interval. */
@Composable
fun formatInterval(ms: Long): String {
    val parts = intervalParts(ms)
    val primary = stringResource(parts.primaryUnit.labelRes, parts.primaryValue)
    val secondary = parts.secondaryUnit?.let {
        stringResource(it.labelRes, parts.secondaryValue)
    } ?: ""
    return primary + secondary
}

/** "刚刚 / 5 分钟前 / 2 小时前 / 从未" for a past timestamp. */
@Composable
fun formatAgo(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String =
    when (val parts = agoParts(timestampMs, nowMs)) {
        AgoParts.Never -> stringResource(R.string.ago_never)
        AgoParts.JustNow -> stringResource(R.string.ago_just_now)
        is AgoParts.Count -> stringResource(parts.unit.agoLabelRes, parts.value)
    }
