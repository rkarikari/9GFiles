package com.radiozport.ninegfiles.data.model

import android.os.Parcelable
import com.radiozport.ninegfiles.R
import kotlinx.parcelize.Parcelize
import java.io.File

@Parcelize
data class FileItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val isHidden: Boolean,
    val extension: String,
    val mimeType: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val childCount: Int = 0,         // for folders
    val isBookmarked: Boolean = false,
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList()
) : Parcelable {

    val file: File get() = File(path)

    val formattedSize: String
        get() = if (isDirectory) "$childCount items" else formatBytes(size)

    val fileType: FileType
        get() = when {
            isDirectory -> FileType.FOLDER
            else -> FileType.fromExtension(extension)
        }

    companion object {
        fun fromFile(file: File, childCount: Int = 0): FileItem {
            val ext = file.extension.lowercase()
            return FileItem(
                path = file.absolutePath,
                name = file.name,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified(),
                isDirectory = file.isDirectory,
                isHidden = file.name.startsWith("."),
                extension = ext,
                mimeType = MimeTypeHelper.getMimeType(ext),
                canRead = file.canRead(),
                canWrite = file.canWrite(),
                childCount = childCount
            )
        }

        private fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024L -> "$bytes B"
                bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
                bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024))
                else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            }
        }
    }
}

enum class FileType(val iconRes: Int, val colorRes: Int) {
    FOLDER(R.drawable.ic_folder,              R.color.category_archive),
    IMAGE(R.drawable.ic_file_image,           R.color.category_image),
    VIDEO(R.drawable.ic_file_video,           R.color.category_video),
    AUDIO(R.drawable.ic_file_audio,           R.color.category_audio),
    DOCUMENT(R.drawable.ic_file_document,     R.color.category_document),
    PDF(R.drawable.ic_file_pdf,               R.color.category_document),
    ARCHIVE(R.drawable.ic_file_archive,       R.color.category_archive),
    APK(R.drawable.ic_file_apk,               R.color.category_apk),
    CODE(R.drawable.ic_file_code,             R.color.md_theme_tertiary),
    SPREADSHEET(R.drawable.ic_file_spreadsheet, R.color.category_document),
    PRESENTATION(R.drawable.ic_file_presentation, R.color.category_archive),
    UNKNOWN(R.drawable.ic_file_generic,       R.color.md_theme_onSurfaceVariant);

    companion object {
        fun fromExtension(ext: String): FileType = when (ext.lowercase()) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg", "raw", "tiff" -> IMAGE
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "m4v", "ts" -> VIDEO
            "mp3", "flac", "aac", "wav", "ogg", "m4a", "wma", "opus", "aiff" -> AUDIO
            "pdf" -> PDF
            "doc", "docx", "odt", "rtf", "txt", "md", "html", "htm", "xml", "json", "yaml", "yml", "epub" -> DOCUMENT
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tar.gz", "tar.bz2", "tar.xz" -> ARCHIVE
            "apk", "xapk", "apks" -> APK
            "kt", "java", "py", "js", "ts", "cpp", "c", "h", "cs", "go", "rb", "php", "swift", "rs" -> CODE
            "xls", "xlsx", "ods", "csv" -> SPREADSHEET
            "ppt", "pptx", "odp" -> PRESENTATION
            else -> UNKNOWN
        }
    }
}

object MimeTypeHelper {
    fun getMimeType(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "7z" -> "application/x-7z-compressed"
        "tar" -> "application/x-tar"
        "gz" -> "application/gzip"
        "epub" -> "application/epub+zip"
        "apk" -> "application/vnd.android.package-archive"
        "txt" -> "text/plain"
        "html", "htm" -> "text/html"
        "xml" -> "text/xml"
        "json" -> "application/json"
        "kt", "java", "py", "js" -> "text/plain"
        else -> "*/*"
    }
}
