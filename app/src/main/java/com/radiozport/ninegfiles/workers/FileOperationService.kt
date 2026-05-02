package com.radiozport.ninegfiles.workers

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.*
import com.radiozport.ninegfiles.ui.main.MainActivity
import kotlinx.coroutines.*

class FileOperationService : Service() {

    companion object {
        /** Use the app-level channel ID so NineGFilesApp.createAllNotificationChannels()
         *  is the single source of truth for channel creation. */
        val CHANNEL_ID get() = NineGFilesApp.CHANNEL_FILE_OPS
        const val NOTIFICATION_ID = 1001

        const val ACTION_COPY   = "com.radiozport.ninegfiles.action.COPY"
        const val ACTION_MOVE   = "com.radiozport.ninegfiles.action.MOVE"
        const val ACTION_DELETE = "com.radiozport.ninegfiles.action.DELETE"
        const val ACTION_CANCEL = "com.radiozport.ninegfiles.action.CANCEL"
        const val ACTION_EXTRACT = "com.radiozport.ninegfiles.action.EXTRACT"
        const val ACTION_COMPRESS = "com.radiozport.ninegfiles.action.COMPRESS"

        const val EXTRA_FILES       = "extra_files"
        const val EXTRA_DESTINATION = "extra_destination"
        const val EXTRA_ARCHIVE_OUT = "extra_archive_out"

        fun buildCopyIntent(context: Context, files: ArrayList<FileItem>, dest: String) =
            Intent(context, FileOperationService::class.java).apply {
                action = ACTION_COPY
                putParcelableArrayListExtra(EXTRA_FILES, files)
                putExtra(EXTRA_DESTINATION, dest)
            }

        fun buildMoveIntent(context: Context, files: ArrayList<FileItem>, dest: String) =
            Intent(context, FileOperationService::class.java).apply {
                action = ACTION_MOVE
                putParcelableArrayListExtra(EXTRA_FILES, files)
                putExtra(EXTRA_DESTINATION, dest)
            }

        fun buildDeleteIntent(context: Context, files: ArrayList<FileItem>) =
            Intent(context, FileOperationService::class.java).apply {
                action = ACTION_DELETE
                putParcelableArrayListExtra(EXTRA_FILES, files)
            }

        fun buildExtractIntent(context: Context, archive: FileItem, dest: String) =
            Intent(context, FileOperationService::class.java).apply {
                action = ACTION_EXTRACT
                putParcelableArrayListExtra(EXTRA_FILES, arrayListOf(archive))
                putExtra(EXTRA_DESTINATION, dest)
            }

        fun buildCompressIntent(context: Context, files: ArrayList<FileItem>, outputPath: String) =
            Intent(context, FileOperationService::class.java).apply {
                action = ACTION_COMPRESS
                putParcelableArrayListExtra(EXTRA_FILES, files)
                putExtra(EXTRA_ARCHIVE_OUT, outputPath)
            }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Notification channel is pre-created by NineGFilesApp.createAllNotificationChannels();
        // no per-service creation needed here.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                currentJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val files = intent?.getParcelableArrayListExtra<FileItem>(EXTRA_FILES) ?: return START_NOT_STICKY
        val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: ""
        val archiveOut = intent.getStringExtra(EXTRA_ARCHIVE_OUT) ?: ""

        val repo = (applicationContext as NineGFilesApp).fileRepository

        startForeground(NOTIFICATION_ID, buildNotification("Preparing…", 0))

        currentJob = serviceScope.launch {
            val result: OperationResult = when (intent.action) {
                ACTION_COPY -> repo.copyFiles(files, destination) { progress ->
                    if (progress is OperationResult.Progress) {
                        val pct = (progress.current * 100 / progress.total.coerceAtLeast(1))
                        updateNotification(buildProgressText("Copying", progress), pct)
                    }
                }
                ACTION_MOVE -> repo.moveFiles(files, destination) { progress ->
                    if (progress is OperationResult.Progress) {
                        val pct = (progress.current * 100 / progress.total.coerceAtLeast(1))
                        updateNotification(buildProgressText("Moving", progress), pct)
                    }
                }
                ACTION_DELETE -> repo.deleteFiles(files) { progress ->
                    if (progress is OperationResult.Progress) {
                        val pct = (progress.current * 100 / progress.total.coerceAtLeast(1))
                        updateNotification("Deleting: ${progress.currentFile}", pct)
                    }
                }
                ACTION_EXTRACT -> repo.extractZip(files[0].path, destination) { progress ->
                    if (progress is OperationResult.Progress) {
                        val pct = (progress.current * 100 / progress.total.coerceAtLeast(1))
                        updateNotification("Extracting: ${progress.currentFile}", pct)
                    }
                }
                ACTION_COMPRESS -> repo.compressFiles(files, archiveOut) { progress ->
                    if (progress is OperationResult.Progress) {
                        val pct = (progress.current * 100 / progress.total.coerceAtLeast(1))
                        updateNotification("Compressing: ${progress.currentFile}", pct)
                    }
                }
                else -> OperationResult.Failure("Unknown action")
            }

            val msg = when (result) {
                is OperationResult.Success -> result.message
                is OperationResult.Failure -> "Failed: ${result.error}"
                else -> "Done"
            }

            showCompletionNotification(msg)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Transfer Progress Helpers ────────────────────────────────────────

    /**
     * Builds a two-line notification text for copy / move operations:
     *   Line 1 — "Copying: filename.ext  (3 of 7)"
     *   Line 2 — "1.4 GB / 3.2 GB · 24.6 MB/s · ETA 1:18"
     * When byte totals are not yet available the second line is omitted.
     */
    private fun buildProgressText(verb: String, p: OperationResult.Progress): String {
        val headline = "$verb: ${p.currentFile}  (${p.current} of ${p.total})"
        if (p.totalBytes <= 0L) return headline
        val transferred = formatBytes(p.bytesTransferred)
        val total       = formatBytes(p.totalBytes)
        val speed       = if (p.speedBytesPerSec > 0L) " · ${formatSpeed(p.speedBytesPerSec)}" else ""
        val eta         = if (p.estimatedSecondsRemaining >= 0L) " · ETA ${formatEta(p.estimatedSecondsRemaining)}" else ""
        return "$headline\n$transferred / $total$speed$eta"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_024L             -> "$bytes B"
        bytes < 1_024L * 1_024     -> "%.1f KB".format(bytes / 1_024.0)
        bytes < 1_024L * 1_024 * 1_024 -> "%.1f MB".format(bytes / (1_024.0 * 1_024))
        else                       -> "%.2f GB".format(bytes / (1_024.0 * 1_024 * 1_024))
    }

    private fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

    private fun formatEta(secs: Long): String = when {
        secs < 60   -> "${secs}s"
        secs < 3600 -> "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
        else        -> "${secs / 3600}h ${(secs % 3600) / 60}m"
    }

    // ─── Notifications ────────────────────────────────────────────────────

    private fun buildNotification(text: String, progress: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FileOperationService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("File Manager Pro")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_folder)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_close, "Cancel", cancelIntent)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun showCompletionNotification(message: String) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Operation Complete")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_folder)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID + 1, notification)
    }
}
