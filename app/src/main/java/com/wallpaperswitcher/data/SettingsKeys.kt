package com.wallpaperswitcher.data

object SettingsKeys {
    /** Set once the built-in subscription source has been seeded. */
    const val RSS_DEFAULT_SEEDED = "rss_default_seeded"
    const val SERVICE_ENABLED = "service_enabled"
    const val DOUBLE_TAP_ENABLED = "double_tap_enabled"
    /**
     * Switch on every unlock (ACTION_USER_PRESENT). Works in both modes: with
     * the live wallpaper engine the engine shows the next media, without it the
     * receiver writes a static home wallpaper. The lock screen is never touched
     * here - it only has its own timer.
     */
    const val UNLOCK_SWITCH_ENABLED = "unlock_switch_enabled"
    // Floating double-tap button fallback for launchers that do not forward
    // touches to the live wallpaper window (Android 16/17 devices).
    const val FLOATING_BUTTON_ENABLED = "floating_button_enabled"
    /**
     * 悬浮按钮「长按预览下一张」：按住看下一张、松手应用、拖开取消。
     *
     * 默认关：这是新增手势，长按以前什么都不做，不该突然改变行为；而且它需要
     * 悬浮窗权限，首次开启要提示用户。
     */
    const val FLOATING_BUTTON_PREVIEW_ON_HOLD = "floating_button_preview_on_hold"
    const val FLOATING_BUTTON_PREVIEW_ON_HOLD_DEFAULT = false
    // Floating button appearance: base color as "#RRGGBB" and opacity as a
    // percentage (5..100, default 10 = the original 90%-transparent look).
    const val FLOATING_BUTTON_COLOR = "floating_button_color"
    const val FLOATING_BUTTON_COLOR_DEFAULT = "#1E88E5"
    const val FLOATING_BUTTON_ALPHA = "floating_button_alpha"
    const val FLOATING_BUTTON_ALPHA_DEFAULT = 10
    const val FLOATING_BUTTON_ALPHA_MIN = 5
    // Floating button content: a short custom label, or a custom picture that
    // replaces the label entirely (see FloatingButtonContentPolicy).
    const val FLOATING_BUTTON_TEXT = "floating_button_text"
    const val FLOATING_BUTTON_TEXT_DEFAULT = "切"
    /** Content URI of the user's picture; empty = draw the text label. */
    const val FLOATING_BUTTON_IMAGE_URI = "floating_button_image_uri"
    const val LAST_IMAGE_ID = "last_image_id"
    // Cursor of the LOCK-slot switching. The home slot keeps using
    // LAST_IMAGE_ID (shared with the live wallpaper engine); the lock screen is
    // switched independently (see WallpaperTarget), so it needs its own cursor
    // and its own shuffle deck.
    const val LAST_IMAGE_ID_LOCK = "last_image_id_lock"
    /**
     * While the user is explicitly setting a HOME wallpaper (tapping an image,
     * confirming in the system live-wallpaper screen), the home timer must not
     * override that pick. Wall-clock ms until which the home timer is postponed.
     *
     * The LOCK timer deliberately ignores this hold: a lock pick only re-anchors
     * the lock schedule, otherwise setting a few lock images in a row would keep
     * renewing the hold and make the lock timer look broken.
     */
    const val MANUAL_PICK_HOLD_UNTIL = "manual_pick_hold_until"
    // The media the user just tapped and confirmed in the system live-wallpaper
    // dialog, plus the wall-clock ms of the tap. The slot enforcement runs a few
    // seconds later (when the real engine is created) and must write THIS media
    // to the lock screen - reading the HOME cursor instead was wrong, because a
    // home tick between the tap and the confirmation moved it (the lock screen
    // then kept showing an older image instead of the one the user picked).
    const val MANUAL_PICK_MEDIA_ID = "manual_pick_media_id"
    const val MANUAL_PICK_AT = "manual_pick_at"
    // Last media written to the LOCK slot + when (wall clock ms). Used to skip
    // redundant lock writes: without it every live-wallpaper apply (and every
    // engine recreation) rewrote the same lock image again and again.
    const val LAST_LOCK_WRITE_ID = "last_lock_write_id"
    const val LAST_LOCK_WRITE_AT = "last_lock_write_at"
    // Same memo for the HOME slot: the static home timer used to re-decode and
    // re-apply the identical image every interval when a group (or a shuffle
    // deck) only offered one item.
    const val LAST_HOME_WRITE_ID = "last_home_write_id"
    const val LAST_HOME_WRITE_AT = "last_home_write_at"
    // --- Lock screen (independent from the home screen triggers) ---
    // The lock screen has its own timed switch (own toggle, interval + anchor),
    // so it can rotate on a completely different rhythm than the home screen.
    const val LOCK_TIMER_ENABLED = "lock_timer_enabled"
    const val LOCK_INTERVAL_MS = "lock_interval_ms"
    // Wall-clock anchor of the lock timer schedule (same idea as the home one).
    const val LOCK_TIMER_LAST_SWITCH_WALL_MS = "lock_timer_last_switch_wall_ms"
    // Global wallpaper settings
    const val GLOBAL_INTERVAL_MS = "global_interval_ms"
    /**
     * 一键暂停 ("稍后切换"): wall-clock ms until which BOTH timed loops hold
     * their ticks. 0 (or a time in the past) = running normally. The tick is
     * never consumed while paused, so resuming switches immediately once the
     * pause expires - even if the app was closed the whole time.
     */
    const val PAUSE_UNTIL = "pause_until"
    /** Wall-clock ms of the pause start, for the "paused for X" line in the UI. */
    const val PAUSE_STARTED_AT = "pause_started_at"
    /**
     * 首启自检向导: set once the user finished / skipped the wizard. A missing
     * row (fresh install, or an install that predates the wizard) reads as
     * "not done" and shows it once.
     */
    const val SETUP_WIZARD_DONE = "setup_wizard_done"
    /** 静态图微动效 (Ken Burns): slow zoom while a still image is displayed. */
    const val KEN_BURNS_ENABLED = "ken_burns_enabled"
    /**
     * Removed 2026-10-05 (4.9.151): the standalone 画质增强 switch moved into
     * [CLARITY_MODE] ("super"). Kept only so the one-time migration can read and
     * delete the old row.
     */
    const val LEGACY_QUALITY_ENHANCE_ENABLED = "quality_enhance_enabled"
    /**
     * 超分算法二选一（清晰度增强打开时生效）："fsr1"（EASU/RCAS）或 "anime4k"
     * （Original 线稿算法）。见 [com.wallpaperswitcher.engine.EnhanceMode]。
     */
    const val ENHANCE_ALGO = "enhance_algo"
    /**
     * 4.9.154 的两个互斥开关，已被 [ENHANCE_ALGO] 取代；只留给一次性迁移读取并
     * 删除（迁移见 WallpaperViewModel.migrateLegacyQualityEnhance）。
     */
    const val LEGACY_FSR1_ENHANCE_ENABLED = "fsr1_enhance_enabled"
    const val LEGACY_ANIME4K_ENHANCE_ENABLED = "anime4k_enhance_enabled"
    // --- 订阅下载策略 (see engine.RssDownloadPolicy) ---
    /** 仅 Wi-Fi 下载: block subscription image downloads on metered networks. */
    const val RSS_WIFI_ONLY = "rss_wifi_only"
    /**
     * 订阅源列表的显示方式：false = 卡片列表（默认），true = 缩略图网格
     * （站点图标，见 engine.RssIcons）。只影响界面，不触发任何抓取。
     */
    const val RSS_GRID_VIEW = "rss_grid_view"
    /** Daily download cap in MB; 0 = unlimited. */
    const val RSS_DAILY_LIMIT_MB = "rss_daily_limit_mb"
    /** Auto-delete unreferenced downloads older than N days; 0 = off. */
    const val RSS_ORPHAN_TTL_DAYS = "rss_orphan_ttl_days"
    /** Local `yyyy-MM-dd` the [RSS_DOWNLOAD_BYTES] counter belongs to. */
    const val RSS_DOWNLOAD_DATE = "rss_download_date"
    /** Bytes downloaded today (reset when [RSS_DOWNLOAD_DATE] changes). */
    const val RSS_DOWNLOAD_BYTES = "rss_download_bytes"
    // Wall-clock (ms) the current switch interval counts from: the last timed
    // switch. Kept across a lock/unlock pause (and across service restarts) so
    // an overdue tick catches up as soon as the screen comes back instead of
    // restarting a whole interval.
    const val TIMER_LAST_SWITCH_WALL_MS = "timer_last_switch_wall_ms"
    const val GLOBAL_SWITCH_MODE = "global_switch_mode"
    const val GLOBAL_SCALE_MODE = "global_scale_mode"
    // 清晰度增强（开/关）: "on"（默认；历史值 auto/super/strong 都按开处理）| "off"。
    const val CLARITY_MODE = "clarity_mode"
    // FILL/STRETCH: rotate media whose orientation mismatches the screen 90° so
    // more of it shows (instead of a thin perpendicular strip). On by default.
    const val ROTATE_MISMATCH_ENABLED = "rotate_mismatch_enabled"
    // Direction used by the auto-rotate feature: "cw" (default) | "ccw".
    const val ROTATE_MISMATCH_CW = "rotate_mismatch_cw"
    // Fade-in-from-black transition after each switch (default on).
    const val SWITCH_FADE_ENABLED = "switch_fade_enabled"
    /**
     * 过渡动画: which transition the renderer plays on a switch.
     * "fade" (default, the historical cross-fade) / "slide" / "zoom" / "none".
     */
    const val SWITCH_TRANSITION = "switch_transition"
    const val SWITCH_TRANSITION_FADE = "fade"
    const val SWITCH_TRANSITION_SLIDE = "slide"
    const val SWITCH_TRANSITION_ZOOM = "zoom"
    const val SWITCH_TRANSITION_NONE = "none"
    /**
     * 默认过渡动画：无。用户要求"取消过渡动画"后，只有主动在设置里选回来
     * 才会有过渡；旧版本升级上来的设备如果已经存了 fade/slide/zoom，仍然按
     * 存的值播放（这是用户自己的选择）。
     */
    const val SWITCH_TRANSITION_DEFAULT = SWITCH_TRANSITION_NONE
    // Play the video wallpaper's own audio track while the wallpaper is
    // visible (default OFF: a silent wallpaper is what users expect, and the
    // first switch to a video must not suddenly make noise).
    const val VIDEO_SOUND_ENABLED = "video_sound_enabled"
    /**
     * 视频播完再切: a timed switch that arrives while a video is playing waits
     * for the current pass to finish instead of cutting the video off. Manual
     * switches ignore it (a tap must act immediately).
     */
    const val VIDEO_PLAY_TO_END = "video_play_to_end"
    /**
     * 收藏优先: favourites (★) are drawn [FAVORITE_WEIGHT]× as often in
     * RANDOM / SHUFFLE. On by default - favouriting is an explicit user action -
     * and can be switched off for a flat distribution.
     */
    const val FAVORITE_BOOST = "favorite_boost"
    /** Extra weight a favourite gets while [FAVORITE_BOOST] is on. */
    const val FAVORITE_WEIGHT = 3
    /**
     * 最近 N 张不重复: how many recently shown media RANDOM avoids per screen
     * (0 = off, the default). SHUFFLE already plays a full pass without
     * repeats, SEQUENTIAL is ordered by definition.
     */
    const val RECENT_NO_REPEAT = "recent_no_repeat"
    // Periodic folder auto-scan
    const val AUTO_SCAN_ENABLED = "auto_scan_enabled"
    const val AUTO_SCAN_INTERVAL_MS = "auto_scan_interval_ms"
    // Wall-clock ms of the last auto-scan run (by the periodic worker or by the
    // catch-up run when the app is opened). Settings shows it, and it is what
    // the catch-up compares against the configured interval.
    const val AUTO_SCAN_LAST_RUN_AT = "auto_scan_last_run_at"
    /**
     * MediaStore generation of the last completed auto-scan run. When the store
     * has not changed since then, re-querying every imported folder cannot find
     * anything new, so the whole run is skipped (see FolderAutoScanWorker).
     */
    const val AUTO_SCAN_LAST_GENERATION = "auto_scan_last_generation"
    /**
     * Size of the pass the SHUFFLE deck is playing through, as the live engine
     * last saw it (its "Shuffle pick" log line reports `deck/saved`). The shown
     * ids themselves live in the `shuffle_shown` table since schema v6 - the old
     * `shuffle_shown_ids` / `shuffle_shown_ids_lock` keys (a comma-separated id
     * list re-written on every switch) are deleted by MIGRATION_5_6.
     */
    const val SHUFFLE_ALL_COUNT = "shuffle_all_count"
    /**
     * Monotonic counter of APPLIED switches, used as the extra seed of the
     * RANDOM / SHUFFLE pick.
     *
     * 下一张预览 must name the media the next switch will really show, which
     * means both must derive the pick from the same state ([SwitchPicking]).
     * Seeding on the cursor alone would make the sequence a fixed function of
     * the cursor - on a small group that walks into a short cycle and repeats
     * the same few pictures forever (a random mapping on N nodes cycles after
     * ~0.6*sqrt(N) steps). This counter only moves when a switch has really
     * been applied, so a preview and the switch that follows it still agree,
     * while every switch draws a fresh, never-repeating seed.
     */
    const val PICK_SEQ = "pick_seq"
    // Theme
    /** Custom accent colour as "#RRGGBB"; empty = 跟随系统 (Monet on Android 12+). */
    const val THEME_COLOR = "theme_color"
    /**
     * Light/dark mode: "system" (follow the phone) / "light" / "dark".
     * Missing or unknown values behave like "system".
     */
    const val THEME_MODE = "theme_mode"
    const val THEME_MODE_SYSTEM = "system"
    const val THEME_MODE_LIGHT = "light"
    const val THEME_MODE_DARK = "dark"
    /**
     * UI language: a BCP-47 language tag ("en", "ja", "zh-Hant", ...) or
     * [LOCALE_SYSTEM] to follow the phone. Only locales that actually ship a
     * translation are offered (see [TRANSLATED_LOCALES]).
     */
    const val LOCALE = "app_locale"
    const val LOCALE_SYSTEM = "system"

    /**
     * Locales this build actually ships translations for, in picker order.
     *
     * Offering a language without a matching `values-<tag>/strings.xml` would
     * silently fall back to the default resources (Chinese), which reads as a
     * broken switch - so the picker is driven by THIS list. Add a tag here only
     * together with its `values-*` folder.
     */
    val TRANSLATED_LOCALES: List<String> = listOf("zh", "zh-TW", "en", "ja", "ko", "es", "ru")
}
