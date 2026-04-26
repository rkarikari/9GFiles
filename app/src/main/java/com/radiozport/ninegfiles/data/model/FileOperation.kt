package com.radiozport.ninegfiles.data.model

sealed class FileOperation {
    data class Copy(val sources: List<FileItem>, val destination: String) : FileOperation()
    data class Move(val sources: List<FileItem>, val destination: String) : FileOperation()
    data class Delete(val targets: List<FileItem>) : FileOperation()
    data class Rename(val target: FileItem, val newName: String) : FileOperation()
    data class Compress(val sources: List<FileItem>, val outputPath: String, val format: ArchiveFormat) : FileOperation()
    data class Extract(val archive: FileItem, val destination: String) : FileOperation()
    data class CreateFolder(val parentPath: String, val folderName: String) : FileOperation()
    data class CreateFile(val parentPath: String, val fileName: String) : FileOperation()
}

enum class ArchiveFormat(val extension: String) {
    ZIP("zip"),
    TAR_GZ("tar.gz"),
    SEVEN_Z("7z")
}

enum class SortOption {
    NAME_ASC,
    NAME_DESC,
    SIZE_ASC,
    SIZE_DESC,
    DATE_ASC,
    DATE_DESC,
    TYPE_ASC,
    TYPE_DESC,
    EXTENSION_ASC,
    EXTENSION_DESC
}

enum class ViewMode {
    LIST,
    GRID,
    COMPACT
}

data class StorageInfo(
    val label: String,
    val path: String,
    val totalSpace: Long,
    val freeSpace: Long,
    val usedSpace: Long = totalSpace - freeSpace,
    val usagePercent: Float = if (totalSpace > 0) usedSpace.toFloat() / totalSpace else 0f,
    val isRemovable: Boolean = false
)

data class SearchFilter(
    val query: String = "",
    val fileTypes: Set<FileType> = emptySet(),
    val minSize: Long = 0L,
    val maxSize: Long = Long.MAX_VALUE,
    val minDate: Long = 0L,
    val maxDate: Long = Long.MAX_VALUE,
    val includeHidden: Boolean = false,
    val searchInSubFolders: Boolean = true,
    val regexSearch: Boolean = false,
    val searchInContent: Boolean = false   // ← NEW: search inside file text
)

sealed class OperationResult {
    data class Success(val message: String, val affectedCount: Int = 1) : OperationResult()
    data class Failure(val error: String, val exception: Throwable? = null) : OperationResult()
    /**
     * Emitted repeatedly during copy / move operations.
     *
     * @param current          1-based index of the file currently being processed.
     * @param total            Total number of top-level items in this batch.
     * @param currentFile      Display name of the file being transferred right now.
     * @param bytesTransferred Cumulative bytes written across all files so far.
     * @param totalBytes       Grand total bytes for the whole batch (0 if unknown).
     * @param speedBytesPerSec Rolling transfer speed in bytes/second (0 if not yet measured).
     */
    data class Progress(
        val current: Int,
        val total: Int,
        val currentFile: String,
        val bytesTransferred: Long = 0L,
        val totalBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L
    ) : OperationResult() {
        /** Estimated seconds remaining; -1 when speed or size is unknown. */
        val estimatedSecondsRemaining: Long
            get() = if (speedBytesPerSec > 0L && totalBytes > bytesTransferred)
                (totalBytes - bytesTransferred) / speedBytesPerSec
            else -1L
    }
    data class Conflict(val existingFile: FileItem, val newFile: FileItem) : OperationResult()
}

enum class ConflictResolution {
    SKIP,
    REPLACE,
    KEEP_BOTH,
    REPLACE_ALL,
    SKIP_ALL
}
