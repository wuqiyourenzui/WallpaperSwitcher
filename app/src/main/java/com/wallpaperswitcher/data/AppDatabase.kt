package com.wallpaperswitcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [WallpaperGroup::class, WallpaperImage::class, AppSettings::class, ShuffleShown::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallpaperGroupDao(): WallpaperGroupDao
    abstract fun wallpaperImageDao(): WallpaperImageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun shuffleDao(): ShuffleDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wallpaper_switcher.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
