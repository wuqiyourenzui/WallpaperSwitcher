package com.wallpaperswitcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [WallpaperGroup::class, WallpaperImage::class, AppSettings::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallpaperGroupDao(): WallpaperGroupDao
    abstract fun wallpaperImageDao(): WallpaperImageDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from version 1 to 2.
         * Adds mediaType, isFromFolder, folderPath columns to wallpaper_images.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'")
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN isFromFolder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wallpaper_images ADD COLUMN folderPath TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wallpaper_switcher.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
