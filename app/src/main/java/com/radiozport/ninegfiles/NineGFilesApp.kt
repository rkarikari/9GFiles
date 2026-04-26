package com.radiozport.ninegfiles

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.radiozport.ninegfiles.data.db.FileManagerDatabase
import com.radiozport.ninegfiles.data.preferences.AppPreferences
import com.radiozport.ninegfiles.data.repository.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import com.radiozport.ninegfiles.utils.AppThemeHelper
import kotlinx.coroutines.launch

class NineGFilesApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: FileManagerDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            FileManagerDatabase::class.java,
            "file_manager_db"
        )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()
    }

    val preferences: AppPreferences by lazy { AppPreferences(applicationContext) }

    val fileRepository: FileRepository by lazy {
        FileRepository(applicationContext, database, preferences)
    }

    override fun onCreate() {
        super.onCreate()

        // Apply saved theme
        applicationScope.launch {
            val theme = preferences.themeMode.first()
            val mode = when (theme) {
                "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
            // Apply saved accent color
            val accent = preferences.accentColor.first()
            AppThemeHelper.applyAccentColor(applicationContext, accent)
        }

        // Clean old recent files (older than 7 days)
        applicationScope.launch(Dispatchers.IO) {
            val threshold = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            database.recentFileDao().deleteOldRecentFiles(threshold)
            // Auto-expire trash older than 30 days
            val expireThreshold = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L
            val expired = database.trashDao().getExpiredItems(expireThreshold)
            expired.forEach { item ->
                java.io.File(item.trashPath).deleteRecursively()
                database.trashDao().removeByOriginalPath(item.originalPath)
            }
        }
    }

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trash (
                        originalPath TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        trashPath TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        isDirectory INTEGER NOT NULL,
                        deletedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS search_history (
                        query TEXT NOT NULL PRIMARY KEY,
                        usedAt INTEGER NOT NULL,
                        useCount INTEGER NOT NULL
                    )
                """)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recent_folders (
                        path TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        visitedAt INTEGER NOT NULL
                    )
                """)
            }
        }
    }
}
