package com.radiozport.ninegfiles

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.Configuration
import com.radiozport.ninegfiles.data.db.FileManagerDatabase
import com.radiozport.ninegfiles.data.preferences.AppPreferences
import com.radiozport.ninegfiles.data.repository.FileRepository
import com.radiozport.ninegfiles.utils.AppLockState
import com.radiozport.ninegfiles.utils.AppThemeHelper
import com.radiozport.ninegfiles.utils.CastMediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class NineGFilesApp : Application(), Configuration.Provider {

    // ─── Application-wide coroutine scope ─────────────────────────────────────
    // SupervisorJob: a child failure does not cancel siblings or the parent.
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ─── Lazy singletons ──────────────────────────────────────────────────────

    val database: FileManagerDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            FileManagerDatabase::class.java,
            "file_manager_db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
    }

    val preferences: AppPreferences by lazy { AppPreferences(applicationContext) }

    val fileRepository: FileRepository by lazy {
        FileRepository(applicationContext, database, preferences)
    }

    // ─── WorkManager Configuration.Provider ───────────────────────────────────
    // Implementing Configuration.Provider disables the default auto-initializer
    // (declared in the manifest via androidx-startup) and gives us control over:
    //   • worker thread pool size
    //   • minimum log level in different build variants
    // The manifest's WorkManagerInitializer meta-data remains as the startup hook;
    // returning a custom Configuration here is what actually overrides behaviour.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR
            )
            .setMaxSchedulerLimit(10)          // cap concurrent background workers
            .build()

    // ─── Process lifecycle — global app-lock gate ──────────────────────────────
    // Using ProcessLifecycleOwner means *any* activity re-entering the foreground
    // (including ones started via ACTION_VIEW intents or launcher shortcuts) will
    // trigger the lock check — not just MainActivity.
    private var appWentToBackground = false

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // All activities have stopped — app is in background.
            appWentToBackground = true
        }

        override fun onStart(owner: LifecycleOwner) {
            // At least one activity has come to the foreground.
            // MainActivity.onResume() reads AppLockState and shows the prompt.
            if (appWentToBackground) {
                appWentToBackground = false
                AppLockState.markPendingIfEnabled(applicationContext)
            }
        }
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        installStrictModeInDebug()
        installGlobalExceptionHandler()
        createAllNotificationChannels()
        bootstrapPrivateDirectories()

        // Apply the user's saved theme and accent colour on the main thread
        // so the very first frame is already correct (avoids a white flash).
        applicationScope.launch(Dispatchers.Main) {
            applyPersistedTheme()
        }

        // Background hygiene — never blocks the main thread.
        applicationScope.launch(Dispatchers.IO) {
            pruneRecentFiles()
            purgeExpiredTrash()
            pruneDeadRecentFolders()
            pruneOldSearchHistory()
        }

        // ProcessLifecycleOwner must be observed from the main thread.
        applicationScope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        }
    }

    // ─── Theme & Accent ───────────────────────────────────────────────────────

    private suspend fun applyPersistedTheme() {
        val theme = preferences.themeMode.first()
        val mode = when (theme) {
            "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        val accent = preferences.accentColor.first()
        AppThemeHelper.applyAccentColor(applicationContext, accent)
    }

    // ─── Notification Channels ────────────────────────────────────────────────
    // All channels must be registered before the first notification is posted.
    // Creating them here (instead of lazily inside services) ensures they exist
    // even if a completion notification fires before the service has started.

    private fun createAllNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // File copy / move / delete / compress operations (foreground service)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FILE_OPS,
                "File Operations",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of file copy, move, delete, and compress operations"
                setShowBadge(false)
            }
        )

        // One-shot completion and error alerts posted after the service ends
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Operation Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies you when a file operation completes or fails"
                setShowBadge(true)
            }
        )

        // FTP server — low-priority persistent "server is running" indicator
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FTP_SERVER,
                "FTP Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the built-in FTP server is active"
                setShowBadge(false)
            }
        )

        // Wi-Fi Direct file transfers
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WIFI_DIRECT,
                "Wi-Fi Direct",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Progress and completion of Wi-Fi Direct file transfers"
                setShowBadge(true)
            }
        )
    }

    // ─── Private directory bootstrap ──────────────────────────────────────────
    // Ensures the vault and trash directories exist from day one, and hides
    // them from the system media scanner by placing a .nomedia sentinel file.

    private fun bootstrapPrivateDirectories() {
        applicationScope.launch(Dispatchers.IO) {
            // Secure Vault — app-private filesDir; inaccessible to other apps.
            val vaultDir = File(filesDir, VAULT_DIR_NAME)
            if (vaultDir.mkdirs() || vaultDir.exists()) {
                File(vaultDir, NOMEDIA_FILE).apply { if (!exists()) createNewFile() }
            }

            // Recycle Bin — external storage so files are restored to the same
            // partition they were deleted from (avoids cross-device copies).
            val extRoot = Environment.getExternalStorageDirectory()
            if (extRoot.canWrite()) {
                val trashDir = File(extRoot, TRASH_DIR_NAME)
                if (trashDir.mkdirs() || trashDir.exists()) {
                    File(trashDir, NOMEDIA_FILE).apply { if (!exists()) createNewFile() }
                }
            }
        }
    }

    // ─── Periodic database hygiene ────────────────────────────────────────────

    /** Delete recent-file entries older than 7 days. */
    private suspend fun pruneRecentFiles() {
        val threshold = System.currentTimeMillis() - RECENT_FILES_MAX_AGE_MS
        database.recentFileDao().deleteOldRecentFiles(threshold)
    }

    /**
     * Permanently delete trash entries whose 30-day retention window has expired.
     * The on-disk copy is wiped first so the record is always consistent.
     */
    private suspend fun purgeExpiredTrash() {
        val autoCleanDays = preferences.trashAutoCleanDays.first()
        // 0 means "Never" — user has disabled auto-clean
        if (autoCleanDays == 0) return
        val retentionMs = autoCleanDays.toLong() * 24 * 60 * 60 * 1000
        val expireThreshold = System.currentTimeMillis() - retentionMs
        val expired = database.trashDao().getExpiredItems(expireThreshold)
        expired.forEach { item ->
            File(item.trashPath).deleteRecursively()
            database.trashDao().removeByOriginalPath(item.originalPath)
        }
    }

    /**
     * Remove recent-folder entries whose paths no longer exist on disk.
     * Stale entries accumulate when the user renames or deletes a folder
     * that was previously visited — without this the Home screen "Recent
     * Folders" row would display phantom entries that crash on tap.
     */
    private suspend fun pruneDeadRecentFolders() {
        val allFolders = database.recentFolderDao().getRecentFolders().first()
        allFolders
            .filter { !File(it.path).exists() }
            .forEach { database.recentFolderDao().deleteByPath(it.path) }
    }

    /**
     * Trim search-history entries older than 60 days.
     * The DAO caps the *visible* list at 20 entries, but this query
     * ensures the underlying table never grows unboundedly on devices
     * that are never rebooted or reinstalled.
     */
    private suspend fun pruneOldSearchHistory() {
        val threshold = System.currentTimeMillis() - SEARCH_HISTORY_MAX_AGE_MS
        database.searchHistoryDao().deleteOlderThan(threshold)
    }

    // ─── StrictMode (debug builds only) ───────────────────────────────────────
    // Catches accidental disk or network I/O on the main thread during
    // development. Completely disabled in release builds.

    private fun installStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }

    // ─── Global uncaught exception handler ────────────────────────────────────
    // Logs the crash and attempts a clean shutdown of the Cast HTTP server
    // before delegating to the system's default handler.

    private fun installGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread '${thread.name}'", throwable)
                // Release the HTTP port so the next process launch can bind
                // immediately without a TIME_WAIT delay.
                CastMediaServer.stop()
            } catch (_: Exception) {
                // Never let the handler itself crash — always delegate.
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    // ─── Database migrations ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "NineGFilesApp"

        // ── Notification channel IDs ──────────────────────────────────────────
        // Public so services and fragments can reference them without
        // re-declaring magic strings in multiple places.
        const val CHANNEL_FILE_OPS    = "file_operations"
        const val CHANNEL_ALERTS      = "file_alerts"
        const val CHANNEL_FTP_SERVER  = "ftp_server"
        const val CHANNEL_WIFI_DIRECT = "wifi_direct"

        // ── Directory names ───────────────────────────────────────────────────
        const val VAULT_DIR_NAME  = "secure_vault"
        const val TRASH_DIR_NAME  = ".NineGFilesTrash"
        private const val NOMEDIA_FILE = ".nomedia"

        // ── Data-retention windows ────────────────────────────────────────────
        private const val RECENT_FILES_MAX_AGE_MS  = 7L  * 24 * 60 * 60 * 1000  //  7 days
        private const val TRASH_RETENTION_MS        = 30L * 24 * 60 * 60 * 1000  // 30 days
        private const val SEARCH_HISTORY_MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000  // 60 days

        // ── Room migrations ───────────────────────────────────────────────────

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS file_notes (
                        filePath TEXT NOT NULL PRIMARY KEY,
                        note TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
            }
        }
    }
}
