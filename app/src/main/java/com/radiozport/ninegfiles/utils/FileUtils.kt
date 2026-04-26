package com.radiozport.ninegfiles.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

object FileUtils {

    // ─── Size Formatting ──────────────────────────────────────────────────

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#")
            .format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    fun formatSizeDetailed(bytes: Long): String {
        val formatted = formatSize(bytes)
        val commaFormatted = DecimalFormat("#,###").format(bytes)
        return "$formatted ($commaFormatted bytes)"
    }

    // ─── Date Formatting ──────────────────────────────────────────────────

    fun formatDate(timestamp: Long, pattern: String = "MMM dd, yyyy HH:mm"): String {
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
    }

    fun formatRelativeDate(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000} minutes ago"
            diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000} days ago"
            else -> formatDate(timestamp, "MMM dd, yyyy")
        }
    }

    // ─── File Hashing ─────────────────────────────────────────────────────

    suspend fun computeMD5(file: File): String = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).buffered(8192).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "N/A"
        }
    }

    suspend fun computeSHA256(file: File): String = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).buffered(8192).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "N/A"
        }
    }

    // ─── MIME Type ────────────────────────────────────────────────────────

    fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    fun getMimeTypeFromUri(context: Context, uri: Uri): String? {
        return if (uri.scheme == "content") {
            context.contentResolver.getType(uri)
        } else {
            val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext?.lowercase())
        }
    }

    // ─── URI Helpers ──────────────────────────────────────────────────────

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            }
        } else {
            uri.lastPathSegment
        }
    }

    fun getFileSizeFromUri(context: Context, uri: Uri): Long {
        return if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
            } ?: 0L
        } else {
            uri.path?.let { File(it).length() } ?: 0L
        }
    }

    // ─── Path Helpers ─────────────────────────────────────────────────────

    fun getUniqueFileName(parent: File, desiredName: String): String {
        if (!File(parent, desiredName).exists()) return desiredName

        val nameWithoutExt = desiredName.substringBeforeLast(".")
        val ext = desiredName.substringAfterLast(".", "")
        val extSuffix = if (ext.isNotEmpty()) ".$ext" else ""

        var counter = 1
        while (File(parent, "$nameWithoutExt ($counter)$extSuffix").exists()) {
            counter++
        }
        return "$nameWithoutExt ($counter)$extSuffix"
    }

    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
    }

    fun getRelativePath(file: File, base: File): String {
        return file.absolutePath.removePrefix(base.absolutePath).trimStart('/')
    }

    // ─── Clipboard ────────────────────────────────────────────────────────

    fun copyPathToClipboard(context: Context, path: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("File Path", path))
    }

    // ─── File Detection ───────────────────────────────────────────────────

    fun isTextFile(file: File): Boolean {
        val textExtensions = setOf(
            "txt", "md", "csv", "log", "json", "xml", "yaml", "yml",
            "html", "htm", "css", "js", "ts", "kt", "java", "py",
            "c", "cpp", "h", "cs", "go", "rb", "php", "swift", "rs",
            "sh", "bat", "ini", "cfg", "conf", "toml", "properties"
        )
        return file.extension.lowercase() in textExtensions
    }

    fun isMediaFile(file: File): Boolean {
        val mediaExtensions = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif",
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm",
            "mp3", "flac", "aac", "wav", "ogg", "m4a", "wma"
        )
        return file.extension.lowercase() in mediaExtensions
    }

    fun isArchive(file: File): Boolean {
        val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
        return file.extension.lowercase() in archiveExtensions
    }

    // ─── Disk Helpers ─────────────────────────────────────────────────────

    fun getFolderSizeAsync(folder: File): Long {
        return try {
            folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    fun hasSufficientSpace(destPath: String, requiredBytes: Long): Boolean {
        return try {
            val stat = android.os.StatFs(destPath)
            stat.availableBytes >= requiredBytes
        } catch (_: Exception) {
            false
        }
    }
}
