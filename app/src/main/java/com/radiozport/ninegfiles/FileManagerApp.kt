package com.radiozport.ninegfiles

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.room.Room
import com.radiozport.ninegfiles.data.db.FileManagerDatabase
import com.radiozport.ninegfiles.data.preferences.AppPreferences
import com.radiozport.ninegfiles.data.repository.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FileManagerApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: FileManagerDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            FileManagerDatabase::class.java,
            "file_manager_db"
        ).build()
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
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        // Clean old recent files (older than 7 days)
        applicationScope.launch(Dispatchers.IO) {
            val threshold = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            database.recentFileDao().deleteOldRecentFiles(threshold)
        }
    }
}
