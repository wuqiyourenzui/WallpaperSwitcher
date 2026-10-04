package com.wallpaperswitcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        WallpaperGroup::class, WallpaperImage::class, AppSettings::class, ShuffleShown::class,
        GroupSchedule::class, RecentShown::class, OnlineSource::class, OnlineItem::class,
        RssSource::class, RssArticle::class
    ],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallpaperGroupDao(): WallpaperGroupDao
    abstract fun wallpaperImageDao(): WallpaperImageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun shuffleDao(): ShuffleDao
    abstract fun groupScheduleDao(): GroupScheduleDao
    /** Group-scoped pick queries (per-group pacing path). */
    abstract fun groupPickDao(): GroupPickDao
    /** "最近 N 张不重复" history (see [RecentShown]). */
    abstract fun recentDao(): RecentDao
    /** 在线壁纸源 (see [OnlineSource]). */
    abstract fun onlineSourceDao(): OnlineSourceDao
    /** Downloaded items of the online sources (see [OnlineItem]). */
    abstract fun onlineItemDao(): OnlineItemDao
    /** 阅读订阅源 (see [RssSource]). */
    abstract fun rssSourceDao(): RssSourceDao
    /** Articles of the subscriptions (see [RssArticle]). */
    abstract fun rssArticleDao(): RssArticleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'")
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN isFromFolder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN folderPath TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpaper_groups ADD COLUMN type TEXT NOT NULL DEFAULT 'IMAGE'")
            }
        }

        // Dual-screen support (Paperize-style): every group says whether its
        // media belongs on the home screen, the lock screen, or both. Existing
        // groups keep the old behaviour (shown everywhere).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpaper_groups ADD COLUMN target TEXT NOT NULL DEFAULT 'BOTH'")
            }
        }

        // Decode metadata (see WallpaperImage): lets a re-decode read the media
        // library once instead of three times. Existing rows start at 0 and are
        // filled by the folder scan or by the first decode (lazy self-heal).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN rotationDegrees INTEGER NOT NULL DEFAULT 0")
            }
        }

        // SHUFFLE deck state moves out of the `app_settings` blob into one row
        // per shown media (see ShuffleShown): the old comma-separated id list was
        // re-read, rebuilt and re-written in full on every switch. The old keys
        // are dropped here - both switch paths (live engine + static applier) use
        // the table from this version on, so leaving them would only keep dead
        // data in the settings table that the settings screen observes.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shuffle_shown` (" +
                        "`slot` TEXT NOT NULL, " +
                        "`mediaId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`slot`, `mediaId`))"
                )
                db.execSQL(
                    "DELETE FROM app_settings " +
                        "WHERE `key` IN ('shuffle_shown_ids', 'shuffle_shown_ids_lock')"
                )
            }
        }

        /**
         * Per-group pacing: a group may carry its own interval and switch mode,
         * and a new table remembers when each group last provided a switch for
         * each screen (only read for groups that opt out of the global
         * interval).
         *
         * Existing rows default to `intervalMs = 0` / `switchMode = ''`, i.e.
         * "follow the global settings" - so an upgraded install keeps behaving
         * exactly as before until a group is given its own rhythm.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE wallpaper_groups ADD COLUMN intervalMs INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE wallpaper_groups ADD COLUMN switchMode TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE wallpaper_groups ADD COLUMN activeFromMinute INTEGER NOT NULL DEFAULT -1"
                )
                db.execSQL(
                    "ALTER TABLE wallpaper_groups ADD COLUMN activeToMinute INTEGER NOT NULL DEFAULT -1"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_schedule` (" +
                        "`groupId` INTEGER NOT NULL, " +
                        "`slot` TEXT NOT NULL, " +
                        "`lastSwitchAt` INTEGER NOT NULL, " +
                        "`lastMediaId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `slot`), " +
                        "FOREIGN KEY(`groupId`) REFERENCES `wallpaper_groups`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                // The shuffle deck becomes per (slot, group): a group with its
                // own switch mode plays its own pass. The deck is throwaway
                // state (which media were already dealt), so rebuilding the
                // table instead of migrating rows is safe - the next switch
                // simply starts a fresh pass.
                db.execSQL("DROP TABLE IF EXISTS `shuffle_shown`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shuffle_shown` (" +
                        "`slot` TEXT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, " +
                        "`mediaId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`slot`, `groupId`, `mediaId`))"
                )
            }
        }

        /**
         * 分组级的切换模式被取消（模式统一由全局设置决定）:
         * the column stays (dropping it would need a table rebuild that
         * cascade-deletes every media row through the FK), but the stale values
         * an older build stored are cleared so they can never resurface, and
         * [WallpaperGroup.switchMode] is inert from v8 on.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE wallpaper_groups SET switchMode = ''")
            }
        }

        /**
         * 分组规则扩展 + 收藏 + "最近 N 张不重复":
         *
         *  - 时间规则可以按星期（[WallpaperGroup.activeDays]，0 = 每天）;
         *  - 分组可以只在浅色/深色模式下参与（[WallpaperGroup.activeThemeMode]）;
         *  - 分组可以筛选素材（[WallpaperGroup.filterMode]）并选择顺序
         *    （[WallpaperGroup.sortOrder]）;
         *  - 媒体可以收藏（[WallpaperImage.isFavorite]）;
         *  - RANDOM 的"最近 N 张不重复"历史（[RecentShown]）。
         *
         * 所有新列都有与旧行为一致的默认值（0 / ''），所以升级后的轮换不变。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpaper_groups ADD COLUMN activeDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wallpaper_groups ADD COLUMN activeThemeMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE wallpaper_groups ADD COLUMN filterMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE wallpaper_groups ADD COLUMN sortOrder TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_shown` (" +
                        "`slot` TEXT NOT NULL, " +
                        "`mediaId` INTEGER NOT NULL, " +
                        "`shownAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`slot`, `mediaId`))"
                )
            }
        }

        /**
         * 在线壁纸源: Bing 每日图 / 指定 URL / WebDAV 目录. Two new tables only;
         * no existing column changes, so an upgraded install keeps every group,
         * media row and setting untouched.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `online_sources` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`webdavUrl` TEXT NOT NULL, " +
                        "`webdavPath` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, " +
                        "`passwordCipher` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`intervalMinutes` INTEGER NOT NULL, " +
                        "`wifiOnly` INTEGER NOT NULL, " +
                        "`chargingOnly` INTEGER NOT NULL, " +
                        "`keepCount` INTEGER NOT NULL, " +
                        "`lastFetchAt` INTEGER NOT NULL, " +
                        "`lastResult` TEXT NOT NULL, " +
                        "`lastErrorAt` INTEGER NOT NULL, " +
                        "`etag` TEXT NOT NULL, " +
                        "`lastModified` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `online_items` (" +
                        "`sourceId` INTEGER NOT NULL, " +
                        "`remoteKey` TEXT NOT NULL, " +
                        "`contentHash` TEXT NOT NULL, " +
                        "`imageId` INTEGER NOT NULL, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceId`, `remoteKey`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `online_sources`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
            }
        }

        /**
         * 在线壁纸源自定义: per-source pages-per-album / max-per-run, and the
         * 美人图 manual selection (newline-separated image URLs). Three added
         * columns with defaults, so every existing source keeps its current
         * behaviour (2 pages, 8 per run, auto mode).
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE online_sources ADD COLUMN pagesPerAlbum INTEGER NOT NULL DEFAULT 2"
                )
                db.execSQL(
                    "ALTER TABLE online_sources ADD COLUMN maxPerRun INTEGER NOT NULL DEFAULT 8"
                )
                db.execSQL(
                    "ALTER TABLE online_sources ADD COLUMN selectedImages TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * 阅读订阅源 (Legado-compatible): two new tables only. Existing groups,
         * media, online sources and settings are untouched.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rss_sources` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`type` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`rawJson` TEXT NOT NULL, " +
                        "`lastFetchAt` INTEGER NOT NULL, " +
                        "`lastResult` TEXT NOT NULL, " +
                        "`lastErrorAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rss_articles` (" +
                        "`sourceId` INTEGER NOT NULL, " +
                        "`guid` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`link` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`imageUrl` TEXT NOT NULL, " +
                        "`publishedAt` INTEGER NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "`isRead` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceId`, `guid`), " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `rss_sources`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rss_articles_sourceId` " +
                        "ON `rss_articles` (`sourceId`)"
                )
            }
        }

        /**
         * Articles carry the 阅读 category (`sortUrl` entry) they were fetched
         * from, so a source can keep several category lists side by side.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `rss_articles` ADD COLUMN `sort` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * 阅读链接可带 `,{"headers":…}` 请求选项；文章页在 WebView 打开、
         * 封面给 Coil 抓取时要用这些头，所以随文章一起存下来。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `rss_articles` ADD COLUMN `requestHeaders` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wallpaper_switcher.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
