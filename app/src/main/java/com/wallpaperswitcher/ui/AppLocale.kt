package com.wallpaperswitcher.ui

import android.content.Context
import android.content.res.Configuration
import com.wallpaperswitcher.data.SettingsKeys
import java.util.Locale

/**
 * UI language handling for the whole app.
 *
 * The chosen language is applied the way Android intends - in
 * [android.content.Context.attachBaseContext] - instead of by wrapping the
 * Compose content in a `CompositionLocalProvider`:
 *
 * A provider that overrides `LocalContext`/`LocalConfiguration` around the app
 * content **breaks `rememberLauncherForActivityResult`**: without the original
 * Activity context in `LocalContext`, androidx.activity cannot resolve the
 * `ActivityResultRegistryOwner` and the Settings screen died with
 * `IllegalStateException: No ActivityResultRegistryOwner was provided via
 * LocalActivityResultRegistryOwner` (measured on the Redmi tablet, 10 crashes).
 * Wrapping the Activity's base context has none of those side effects and also
 * covers code that runs outside composition (notification text, hints, ...).
 *
 * The value is mirrored into SharedPreferences because `attachBaseContext` runs
 * before Room is usable; the database stays the source of truth and the mirror is
 * written on every change (see `WallpaperViewModel.setLocale`).
 */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    private val cacheLock = Any()
    private var cachedBase: Context? = null
    private var cachedTag: String? = null
    private var cached: Context? = null

    /**
     * The context the framework handed to `Application.attachBaseContext`, i.e.
     * BEFORE [wrap] re-pointed the process at the stored tag. It carries the
     * system's own locale (including Android 13's per-app language), so it is
     * what "跟随系统" has to resolve against.
     *
     * Without it, switching back to "system" was stuck on the language the
     * process happened to start with: the Application's resources stay wrapped
     * for the whole process, and the hint read from the ViewModel came out in the
     * PREVIOUS language (measured on the device: the settings screen was already
     * Russian while the hint bubble logged
     * `Hint shown for 5000ms: “홈 화면 및 잠금 화면”을 선택하세요` - Korean, the
     * language the app had been started in).
     */
    @Volatile
    private var systemBase: Context? = null

    /** Called from `Application.attachBaseContext` with the UNWRAPPED base. */
    fun rememberSystemBase(base: Context) {
        systemBase = base
    }

    /** The stored tag ("system" when unset/unknown). */
    fun storedTag(context: Context): String =
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAG, SettingsKeys.LOCALE_SYSTEM)
                ?: SettingsKeys.LOCALE_SYSTEM
        } catch (_: Exception) {
            SettingsKeys.LOCALE_SYSTEM
        }

    /** Mirrors the Room setting so [wrap] can read it before the DB exists. */
    @Suppress("ApplySharedPref") // commit() is required - see the comment below.
    fun store(context: Context, tag: String) {
        try {
            // commit(), not apply(): the caller recreates the Activity right
            // after this, and attachBaseContext would otherwise still read the
            // previous tag (measured: the language looked "unchanged" until the
            // next launch). One tiny write, so blocking here is fine.
            // (Lint's ApplySharedPref suggestion is deliberate noise here: apply()
            // would write asynchronously and reintroduce that bug.)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TAG, tag).commit()
        } catch (_: Exception) {
        }
    }

    /**
     * Wraps [base] with the chosen locale, or returns it unchanged for
     * "system" - in that case the system (including Android 13's per-app
     * language, which the platform already applies to app resources) decides.
     */
    fun wrap(base: Context): Context {
        val tag = storedTag(base)
        if (tag.isEmpty() || tag == SettingsKeys.LOCALE_SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isEmpty()) return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return try {
            base.createConfigurationContext(configuration)
        } catch (_: Exception) {
            base
        }
    }

    /**
     * A context whose resources use the chosen language, for strings read
     * OUTSIDE composition - ViewModel toasts, the hint bubble above the system
     * live-wallpaper dialog, the foreground service's notification.
     *
     * Those used to read `getApplication().getString(...)`, and the Application
     * context never sees the in-app choice: [wrap] is applied to the Activity's
     * base context, so every toast/hint stayed in the default language (Chinese)
     * even after switching to English - the reported "系统壁纸界面的提示词未更换语言"
     * and "未启用分组设置图片的提示未更换语言".
     *
     * The tag is re-read on every call (a language change must be visible at
     * once, without restarting the process) and the wrapper is cached per tag so
     * a toast does not allocate one.
     */
    fun localized(context: Context): Context {
        val base = systemBase ?: context.applicationContext ?: context
        val tag = storedTag(base)
        if (tag.isEmpty() || tag == SettingsKeys.LOCALE_SYSTEM) return base
        synchronized(cacheLock) {
            val hit = cached
            if (hit != null && tag == cachedTag && cachedBase === base) return hit
            val wrapped = wrap(base)
            cachedBase = base
            cachedTag = tag
            cached = wrapped
            return wrapped
        }
    }
}

/**
 * 当前生效的语言标签（"zh-CN" / "en" / …），用于设置里的语言选择器显示当前值。
 *
 * 读的是**同步镜像**（[storedTag] 用的那份存储），而不是数据库：语言是在
 * attachBaseContext 里应用的，必须是同步可见的值，否则重建 Activity 时会读到旧值。
 */
fun currentLocaleTag(context: android.content.Context): String =
    AppLocale.storedTag(context.applicationContext ?: context)
