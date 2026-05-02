package com.radiozport.ninegfiles.data.repository

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.radiozport.ninegfiles.data.db.*
import com.radiozport.ninegfiles.data.model.*
import com.radiozport.ninegfiles.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.*
import java.security.MessageDigest

class FileRepository(
    private val context: Context,
    private val db: FileManagerDatabase,
    private val prefs: AppPreferences
) {

    // ─── Storage Info ─────────────────────────────────────────────────────────

    fun getStorageList(): List<StorageInfo> {
        val storages = mutableListOf<StorageInfo>()
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        val stat = StatFs(internalPath)
        storages.add(StorageInfo(
            label = "Internal Storage", path = internalPath,
            totalSpace = stat.totalBytes, freeSpace = stat.availableBytes, isRemovable = false
        ))
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        storageManager.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }.forEach { vol ->
            vol.directory?.let { dir ->
                val extStat = StatFs(dir.absolutePath)
                storages.add(StorageInfo(
                    label = vol.getDescription(context), path = dir.absolutePath,
                    totalSpace = extStat.totalBytes, freeSpace = extStat.availableBytes, isRemovable = true
                ))
            }
        }
        return storages
    }

    // ─── File Listing ─────────────────────────────────────────────────────────

    suspend fun listFiles(path: String, sortOption: SortOption, showHidden: Boolean, foldersFirst: Boolean): List<FileItem> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return@withContext emptyList()
        val bookmarkedPaths = db.bookmarkDao().getAllBookmarkedPaths().toSet()
        val notedPaths = db.fileNoteDao().getAllNotes()
            .first()
            .map { it.filePath }.toSet()
        val files = dir.listFiles()
            ?.filter { showHidden || !it.name.startsWith(".") }
            ?.map { file ->
                val childCount = if (file.isDirectory) file.listFiles()?.size ?: 0 else 0
                FileItem.fromFile(file, childCount).copy(
                    isBookmarked = file.absolutePath in bookmarkedPaths,
                    hasNote = file.absolutePath in notedPaths
                )
            } ?: emptyList()
        sortFiles(files, sortOption, foldersFirst)
    }

    private fun sortFiles(files: List<FileItem>, sort: SortOption, foldersFirst: Boolean): List<FileItem> {
        val comparator = when (sort) {
            SortOption.NAME_ASC -> compareBy<FileItem> { it.name.lowercase() }
            SortOption.NAME_DESC -> compareByDescending { it.name.lowercase() }
            SortOption.SIZE_ASC -> compareBy { it.size }
            SortOption.SIZE_DESC -> compareByDescending { it.size }
            SortOption.DATE_ASC -> compareBy { it.lastModified }
            SortOption.DATE_DESC -> compareByDescending { it.lastModified }
            SortOption.TYPE_ASC -> compareBy { it.fileType.ordinal }
            SortOption.TYPE_DESC -> compareByDescending { it.fileType.ordinal }
            SortOption.EXTENSION_ASC -> compareBy { it.extension }
            SortOption.EXTENSION_DESC -> compareByDescending { it.extension }
        }
        return if (foldersFirst) {
            val folders = files.filter { it.isDirectory }.sortedWith(comparator)
            val filesList = files.filter { !it.isDirectory }.sortedWith(comparator)
            folders + filesList
        } else files.sortedWith(comparator)
    }

    // ─── File Search ──────────────────────────────────────────────────────────

    suspend fun searchFiles(rootPath: String, filter: SearchFilter, onProgress: (Int) -> Unit = {},
                            sortOption: SortOption = SortOption.NAME_ASC, foldersFirst: Boolean = true): List<FileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FileItem>()
        searchRecursive(File(rootPath), filter, results, onProgress)
        sortFiles(results.take(1000), sortOption, foldersFirst)
    }

    private fun searchRecursive(dir: File, filter: SearchFilter, results: MutableList<FileItem>, onProgress: (Int) -> Unit) {
        if (!dir.canRead()) return
        dir.listFiles()?.forEach { file ->
            val name = file.name
            if (!filter.includeHidden && name.startsWith(".")) return@forEach
            if (matchesFilter(file, filter)) {
                val childCount = if (file.isDirectory) file.listFiles()?.size ?: 0 else 0
                results.add(FileItem.fromFile(file, childCount))
                onProgress(results.size)
            }
            if (file.isDirectory && filter.searchInSubFolders) searchRecursive(file, filter, results, onProgress)
        }
    }

    private fun matchesFilter(file: File, filter: SearchFilter): Boolean {
        val nameMatch = if (filter.query.isNotEmpty()) {
            if (filter.regexSearch) {
                try { Regex(filter.query, RegexOption.IGNORE_CASE).containsMatchIn(file.name) }
                catch (_: Exception) { file.name.contains(filter.query, ignoreCase = true) }
            } else file.name.contains(filter.query, ignoreCase = true)
        } else true

        // If name matched OR content search is on, check content for searchable text files
        if (!nameMatch) {
            if (!filter.searchInContent || file.isDirectory) return false
            val contentSearchable = file.extension.lowercase() in
                setOf("txt", "kt", "java", "py", "js", "ts", "json", "xml", "md",
                      "html", "htm", "csv", "yaml", "yml", "toml", "ini", "cfg",
                      "log", "sh", "bat", "cs", "cpp", "c", "h", "rb", "go",
                      "rs", "swift", "php", "r", "sql")
            if (!contentSearchable) return false
            val queryInContent = try {
                file.bufferedReader().use { reader ->
                    reader.lineSequence().any { line ->
                        if (filter.regexSearch) {
                            try { Regex(filter.query, RegexOption.IGNORE_CASE).containsMatchIn(line) }
                            catch (_: Exception) { line.contains(filter.query, ignoreCase = true) }
                        } else line.contains(filter.query, ignoreCase = true)
                    }
                }
            } catch (_: Exception) { false }
            if (!queryInContent) return false
        }

        if (file.isDirectory) return filter.fileTypes.isEmpty()
        val size = file.length()
        if (size < filter.minSize || size > filter.maxSize) return false
        val modified = file.lastModified()
        if (modified < filter.minDate || modified > filter.maxDate) return false
        if (filter.fileTypes.isNotEmpty()) {
            val fileType = FileType.fromExtension(file.extension)
            if (fileType !in filter.fileTypes) return false
        }
        return true
    }

    // ─── File Operations ──────────────────────────────────────────────────────

    suspend fun copyFiles(sources: List<FileItem>, destination: String, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        // Pre-calculate grand total bytes so we can show meaningful overall progress.
        val totalBytes = sources.sumOf { if (it.isDirectory) getFolderSize(it.path) else it.size }
        var bytesTransferred = 0L
        val startTime = System.currentTimeMillis()
        var successCount = 0

        sources.forEachIndexed { index, source ->
            onProgress(OperationResult.Progress(
                current = index + 1, total = sources.size,
                currentFile = source.name,
                bytesTransferred = bytesTransferred, totalBytes = totalBytes,
                speedBytesPerSec = calcSpeed(bytesTransferred, startTime)
            ))
            try {
                val destFile = File(destination, source.name)
                if (source.isDirectory) {
                    copyDirectory(source.file, destFile) { delta ->
                        bytesTransferred += delta
                        onProgress(OperationResult.Progress(
                            current = index + 1, total = sources.size,
                            currentFile = source.name,
                            bytesTransferred = bytesTransferred, totalBytes = totalBytes,
                            speedBytesPerSec = calcSpeed(bytesTransferred, startTime)
                        ))
                    }
                } else {
                    copyFile(source.file, destFile) { delta ->
                        bytesTransferred += delta
                        onProgress(OperationResult.Progress(
                            current = index + 1, total = sources.size,
                            currentFile = source.name,
                            bytesTransferred = bytesTransferred, totalBytes = totalBytes,
                            speedBytesPerSec = calcSpeed(bytesTransferred, startTime)
                        ))
                    }
                }
                successCount++
            } catch (e: Exception) {
                return@withContext OperationResult.Failure("Failed to copy ${source.name}: ${e.message}", e)
            }
        }
        OperationResult.Success("Copied $successCount item(s) successfully", successCount)
    }

    suspend fun moveFiles(sources: List<FileItem>, destination: String, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        // Pre-calculate total bytes for cross-device moves (USB / SD card).
        // For same-device renames this stays at 0 — progress is per-file.
        val totalBytes = sources.sumOf { if (it.isDirectory) getFolderSize(it.path) else it.size }
        var bytesTransferred = 0L
        val startTime = System.currentTimeMillis()
        var successCount = 0

        sources.forEachIndexed { index, source ->
            onProgress(OperationResult.Progress(
                current = index + 1, total = sources.size,
                currentFile = source.name,
                bytesTransferred = bytesTransferred, totalBytes = totalBytes,
                speedBytesPerSec = calcSpeed(bytesTransferred, startTime)
            ))
            try {
                val destFile = File(destination, source.name)
                if (!source.file.renameTo(destFile)) {
                    // renameTo failed — cross-device move (e.g. internal → USB / SD card).
                    // Fall back to copy-then-delete with full byte-level progress.
                    if (source.isDirectory) {
                        copyDirectory(source.file, destFile) { delta ->
                            bytesTransferred += delta
                            onProgress(OperationResult.Progress(
                                current = index + 1, total = sources.size,
                                currentFile = source.name,
                                bytesTransferred = bytesTransferred, totalBytes = totalBytes,
                                speedBytesPerSec = calcSpeed(bytesTransferred, startTime)
                            ))
                        }
                    } else {
                        copyFile(source.file, destFile) { delta ->
                            bytesTransferred += delta
                            onProgress(OperationResult.Progress(
                                current = index + 1, total = sources.size,
                                currentFile = source.name,
                                bytesTransferred = bytesTransferred, totalBytes = totalBytes,
                                speedBytesPerSec = calcSpeed(bytesTransferred, startTime)
                            ))
                        }
                    }
                    source.file.deleteRecursively()
                } else {
                    // Same-device atomic rename — no bytes physically transferred,
                    // advance counter by the file's logical size for consistent display.
                    bytesTransferred += if (source.isDirectory) getFolderSize(source.path) else source.size
                }
                successCount++
            } catch (e: Exception) {
                return@withContext OperationResult.Failure("Failed to move ${source.name}: ${e.message}", e)
            }
        }
        OperationResult.Success("Moved $successCount item(s) successfully", successCount)
    }

    suspend fun deleteFiles(targets: List<FileItem>, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        var successCount = 0
        targets.forEachIndexed { index, target ->
            onProgress(OperationResult.Progress(index + 1, targets.size, target.name))
            try {
                if (target.file.deleteRecursively()) {
                    successCount++
                    db.recentFileDao().deleteRecentFile(target.path)
                    db.bookmarkDao().deleteBookmarkByPath(target.path)
                } else return@withContext OperationResult.Failure("Could not delete ${target.name}")
            } catch (e: Exception) {
                return@withContext OperationResult.Failure("Failed to delete ${target.name}: ${e.message}", e)
            }
        }
        OperationResult.Success("Deleted $successCount item(s) successfully", successCount)
    }

    /** Move to .Trash instead of deleting permanently */
    suspend fun trashFiles(targets: List<FileItem>, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        val trashDir = File(Environment.getExternalStorageDirectory(), ".NineGFilesTrash").also { it.mkdirs() }
        var successCount = 0
        targets.forEachIndexed { index, target ->
            onProgress(OperationResult.Progress(index + 1, targets.size, target.name))
            try {
                val timestamp = System.currentTimeMillis()
                val trashName = "${target.name}_$timestamp"
                val trashFile = File(trashDir, trashName)
                if (target.file.renameTo(trashFile)) {
                    db.trashDao().insertTrashed(TrashEntity(
                        originalPath = target.path,
                        name = target.name,
                        trashPath = trashFile.absolutePath,
                        size = target.size,
                        isDirectory = target.isDirectory,
                        deletedAt = timestamp
                    ))
                    db.recentFileDao().deleteRecentFile(target.path)
                    db.bookmarkDao().deleteBookmarkByPath(target.path)
                    successCount++
                } else return@withContext OperationResult.Failure("Could not move ${target.name} to Trash")
            } catch (e: Exception) {
                return@withContext OperationResult.Failure("Trash failed for ${target.name}: ${e.message}", e)
            }
        }
        OperationResult.Success("Moved $successCount item(s) to Trash", successCount)
    }

    suspend fun restoreFromTrash(item: TrashEntity): OperationResult = withContext(Dispatchers.IO) {
        try {
            val trashFile = File(item.trashPath)
            val originalFile = File(item.originalPath)
            originalFile.parentFile?.mkdirs()
            if (trashFile.renameTo(originalFile)) {
                db.trashDao().removeByOriginalPath(item.originalPath)
                OperationResult.Success("Restored ${item.name}")
            } else OperationResult.Failure("Could not restore ${item.name}")
        } catch (e: Exception) {
            OperationResult.Failure("Restore failed: ${e.message}", e)
        }
    }

    suspend fun deleteFromTrash(item: TrashEntity): OperationResult = withContext(Dispatchers.IO) {
        try {
            File(item.trashPath).deleteRecursively()
            db.trashDao().removeByOriginalPath(item.originalPath)
            OperationResult.Success("Permanently deleted ${item.name}")
        } catch (e: Exception) {
            OperationResult.Failure("Delete failed: ${e.message}", e)
        }
    }

    suspend fun emptyTrash(): OperationResult = withContext(Dispatchers.IO) {
        try {
            File(Environment.getExternalStorageDirectory(), ".NineGFilesTrash").deleteRecursively()
            db.trashDao().clearAll()
            OperationResult.Success("Trash emptied")
        } catch (e: Exception) {
            OperationResult.Failure("Could not empty trash: ${e.message}", e)
        }
    }

    fun getTrashItems() = db.trashDao().getAllTrashed()

    /** Secure multi-pass overwrite then delete */
    suspend fun shredFiles(targets: List<FileItem>, passes: Int = 3, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        var successCount = 0
        targets.forEachIndexed { index, target ->
            onProgress(OperationResult.Progress(index + 1, targets.size, target.name))
            try {
                shredRecursive(target.file, passes)
                successCount++
                db.recentFileDao().deleteRecentFile(target.path)
                db.bookmarkDao().deleteBookmarkByPath(target.path)
            } catch (e: Exception) {
                return@withContext OperationResult.Failure("Shred failed for ${target.name}: ${e.message}", e)
            }
        }
        OperationResult.Success("Securely deleted $successCount item(s)", successCount)
    }

    private fun shredRecursive(file: File, passes: Int) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { shredRecursive(it, passes) }
            file.delete()
            return
        }
        val length = file.length()
        if (length > 0) {
            val buf = ByteArray(minOf(length, 64 * 1024).toInt())
            repeat(passes) { pass ->
                val value = when (pass % 3) {
                    0 -> 0x00.toByte(); 1 -> 0xFF.toByte(); else -> 0xAA.toByte()
                }
                buf.fill(value)
                RandomAccessFile(file, "rw").use { raf ->
                    var written = 0L
                    while (written < length) {
                        val chunk = minOf(buf.size.toLong(), length - written).toInt()
                        raf.write(buf, 0, chunk)
                        written += chunk
                    }
                    raf.fd.sync()
                }
            }
        }
        file.delete()
    }

    suspend fun renameFile(target: FileItem, newName: String): OperationResult = withContext(Dispatchers.IO) {
        try {
            if (newName.isBlank()) return@withContext OperationResult.Failure("Name cannot be empty")
            if (newName.contains('/') || newName.contains('\\'))
                return@withContext OperationResult.Failure("Name cannot contain slashes")
            val newFile = File(target.file.parent, newName)
            if (newFile.exists()) return@withContext OperationResult.Failure("A file with this name already exists")
            if (target.file.renameTo(newFile)) {
                db.recentFileDao().deleteRecentFile(target.path)
                db.bookmarkDao().deleteBookmarkByPath(target.path)
                OperationResult.Success("Renamed to $newName")
            } else OperationResult.Failure("Could not rename file")
        } catch (e: Exception) {
            OperationResult.Failure(e.message ?: "Rename failed", e)
        }
    }

    /** Batch rename with prefix/suffix/counter/regex template */
    suspend fun batchRename(targets: List<FileItem>, template: BatchRenameTemplate, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        var successCount = 0
        targets.forEachIndexed { index, item ->
            onProgress(OperationResult.Progress(index + 1, targets.size, item.name))
            try {
                val newName = template.apply(item.name, index + 1)
                if (newName == item.name) { successCount++; return@forEachIndexed }
                val newFile = File(item.file.parent, newName)
                if (!newFile.exists() && item.file.renameTo(newFile)) successCount++
                else return@withContext OperationResult.Failure("Could not rename ${item.name} → $newName")
            } catch (e: Exception) {
                return@withContext OperationResult.Failure("Batch rename error: ${e.message}", e)
            }
        }
        OperationResult.Success("Renamed $successCount item(s)", successCount)
    }

    suspend fun createFolder(parentPath: String, name: String): OperationResult = withContext(Dispatchers.IO) {
        try {
            if (name.isBlank()) return@withContext OperationResult.Failure("Name cannot be empty")
            val newDir = File(parentPath, name)
            if (newDir.exists()) return@withContext OperationResult.Failure("Folder already exists")
            if (newDir.mkdirs()) OperationResult.Success("Folder '$name' created")
            else OperationResult.Failure("Could not create folder")
        } catch (e: Exception) {
            OperationResult.Failure(e.message ?: "Create folder failed", e)
        }
    }

    suspend fun createFile(parentPath: String, name: String): OperationResult = withContext(Dispatchers.IO) {
        try {
            if (name.isBlank()) return@withContext OperationResult.Failure("Name cannot be empty")
            val newFile = File(parentPath, name)
            if (newFile.exists()) return@withContext OperationResult.Failure("File already exists")
            if (newFile.createNewFile()) OperationResult.Success("File '$name' created")
            else OperationResult.Failure("Could not create file")
        } catch (e: Exception) {
            OperationResult.Failure(e.message ?: "Create file failed", e)
        }
    }

    /** Split a large file into N-MB parts */
    suspend fun splitFile(source: FileItem, partSizeMb: Int, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        val partSize = partSizeMb * 1024L * 1024L
        try {
            val sourceFile = source.file
            val totalParts = ((sourceFile.length() + partSize - 1) / partSize).toInt()
            val buf = ByteArray(64 * 1024)
            FileInputStream(sourceFile).buffered().use { input ->
                for (part in 1..totalParts) {
                    onProgress(OperationResult.Progress(part, totalParts, "${source.name} part $part"))
                    val partFile = File(sourceFile.parent, "${source.name}.part${part.toString().padStart(3, '0')}")
                    var remaining = partSize
                    FileOutputStream(partFile).buffered().use { out ->
                        while (remaining > 0) {
                            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (read == -1) break
                            out.write(buf, 0, read)
                            remaining -= read
                        }
                    }
                }
            }
            OperationResult.Success("Split into $totalParts parts", totalParts)
        } catch (e: Exception) {
            OperationResult.Failure("Split failed: ${e.message}", e)
        }
    }

    /** Combine .partXXX files back into original */
    suspend fun combineFileParts(parts: List<FileItem>, outputPath: String, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        val sorted = parts.sortedBy { it.name }
        try {
            val out = File(outputPath)
            val buf = ByteArray(64 * 1024)
            FileOutputStream(out).buffered().use { output ->
                sorted.forEachIndexed { idx, part ->
                    onProgress(OperationResult.Progress(idx + 1, sorted.size, part.name))
                    FileInputStream(part.file).buffered().use { it.copyTo(output, 64 * 1024) }
                }
            }
            OperationResult.Success("Combined into ${out.name}")
        } catch (e: Exception) {
            OperationResult.Failure("Combine failed: ${e.message}", e)
        }
    }

    /** Change last-modified timestamp on a file */
    suspend fun changeTimestamp(item: FileItem, newTimestampMs: Long): OperationResult = withContext(Dispatchers.IO) {
        return@withContext if (item.file.setLastModified(newTimestampMs))
            OperationResult.Success("Timestamp updated")
        else
            OperationResult.Failure("Could not change timestamp — check permissions")
    }

    // ─── Archive Operations ───────────────────────────────────────────────────

    suspend fun compressFiles(sources: List<FileItem>, outputPath: String, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(outputPath)
            ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
                sources.forEachIndexed { idx, source ->
                    onProgress(OperationResult.Progress(idx + 1, sources.size, source.name))
                    addToZip(zos, source.file, source.file.parent ?: "")
                }
            }
            OperationResult.Success("Archive created: ${outputFile.name}")
        } catch (e: Exception) {
            OperationResult.Failure("Compression failed: ${e.message}", e)
        }
    }

    private fun addToZip(zos: ZipArchiveOutputStream, file: File, basePath: String) {
        val entryName = file.absolutePath.removePrefix(basePath).trimStart('/')
        if (file.isDirectory) {
            file.listFiles()?.forEach { addToZip(zos, it, basePath) }
        } else {
            val entry = ZipArchiveEntry(file, entryName)
            zos.putArchiveEntry(entry)
            FileInputStream(file).buffered().use { it.copyTo(zos) }
            zos.closeArchiveEntry()
        }
    }

    suspend fun extractZip(archivePath: String, destinationPath: String, onProgress: (OperationResult) -> Unit): OperationResult = withContext(Dispatchers.IO) {
        try {
            val destDir = File(destinationPath).also { it.mkdirs() }
            ZipFile(File(archivePath)).use { zip ->
                val entries = zip.entries.toList()
                entries.forEachIndexed { idx, entry ->
                    onProgress(OperationResult.Progress(idx + 1, entries.size, entry.name))
                    val destFile = File(destDir, entry.name)
                    if (entry.isDirectory) destFile.mkdirs()
                    else {
                        destFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            destFile.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
            OperationResult.Success("Extracted to ${destDir.name}")
        } catch (e: Exception) {
            OperationResult.Failure("Extraction failed: ${e.message}", e)
        }
    }

    // ─── Bookmarks ────────────────────────────────────────────────────────────

    fun getBookmarks()              = db.bookmarkDao().getAllBookmarks()
    suspend fun addBookmark(item: FileItem)  = db.bookmarkDao().insertBookmark(BookmarkEntity(path = item.path, name = item.name, isDirectory = item.isDirectory))
    /** Re-inserts a previously removed [BookmarkEntity] verbatim, preserving emoji and addedAt. */
    suspend fun restoreBookmark(entity: BookmarkEntity) = db.bookmarkDao().insertBookmark(entity)
    suspend fun removeBookmark(path: String) = db.bookmarkDao().deleteBookmarkByPath(path)
    suspend fun isBookmarked(path: String)   = db.bookmarkDao().isBookmarked(path)

    // ─── Recent Files ─────────────────────────────────────────────────────────

    fun getRecentFiles() = db.recentFileDao().getRecentFiles()
    suspend fun addToRecent(item: FileItem) = db.recentFileDao().insertRecentFile(RecentFileEntity(path = item.path, name = item.name, mimeType = item.mimeType, size = item.size))
    suspend fun clearRecentFiles() = db.recentFileDao().clearAll()

    // ─── Recent Folders ───────────────────────────────────────────────────────

    fun getRecentFolders() = db.recentFolderDao().getRecentFolders()
    suspend fun addRecentFolder(path: String, name: String) = db.recentFolderDao().insertFolder(RecentFolderEntity(path = path, name = name))
    suspend fun clearRecentFolders() = db.recentFolderDao().clearAll()

    // ─── Search History ───────────────────────────────────────────────────────

    fun getSearchHistory() = db.searchHistoryDao().getRecentSearches()

    suspend fun recordSearch(query: String) {
        if (query.length < 2) return
        val existing = db.searchHistoryDao().getSuggestions(query).firstOrNull { it.query == query }
        if (existing != null) db.searchHistoryDao().bump(query)
        else db.searchHistoryDao().insert(SearchHistoryEntity(query = query))
    }

    suspend fun getSuggestions(prefix: String) = db.searchHistoryDao().getSuggestions(prefix)
    suspend fun deleteSearchHistory(query: String) = db.searchHistoryDao().delete(query)
    suspend fun clearSearchHistory() = db.searchHistoryDao().clearAll()

    // ─── Tags ────────────────────────────────────────────────────────────────

    fun getTagsForFile(path: String) = db.fileTagDao().getTagsForFile(path)
    fun getAllTags() = db.fileTagDao().getAllTags()
    suspend fun addTag(filePath: String, tag: String, color: Int) = db.fileTagDao().addTag(FileTagEntity(filePath = filePath, tag = tag, color = color))

    // ─── File Notes ───────────────────────────────────────────────────────────

    fun getNoteForFile(path: String) = db.fileNoteDao().getNoteForFile(path)
    fun getAllNotes() = db.fileNoteDao().getAllNotes()
    suspend fun upsertNote(filePath: String, note: String) =
        db.fileNoteDao().upsertNote(com.radiozport.ninegfiles.data.db.FileNoteEntity(filePath = filePath, note = note))
    suspend fun deleteNote(filePath: String) = db.fileNoteDao().deleteNoteByPath(filePath)
    suspend fun hasNote(path: String) = db.fileNoteDao().hasNote(path)

    // ─── File Details ─────────────────────────────────────────────────────────

    suspend fun getFileDetails(item: FileItem): Map<String, String> = withContext(Dispatchers.IO) {
        buildMap {
            put("Name", item.name)
            put("Path", item.path)
            put("Type", if (item.isDirectory) "Folder" else item.extension.uppercase().ifEmpty { "File" })
            put("Size", if (item.isDirectory) {
                val s = getFolderSize(item.path)
                "${formatBytes(s)} ($s bytes)"
            } else "${formatBytes(item.size)} (${item.size} bytes)")
            put("Modified", java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.lastModified)))
            if (item.isDirectory) put("Contents", "${item.childCount} items")
            put("Readable", if (item.canRead) "Yes" else "No")
            put("Writable", if (item.canWrite) "Yes" else "No")
            if (!item.isDirectory) put("MIME Type", item.mimeType)
            put("Hidden", if (item.isHidden) "Yes" else "No")
            // Unix permissions
            put("Permissions", unixPermissions(item.file))
        }
    }

    /** Return rwxrwxrwx-style permissions string using PosixFilePermissions (API 26+) */
    fun unixPermissions(file: File): String {
        return try {
            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            val order = listOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
            )
            val chars = charArrayOf('r','w','x','r','w','x','r','w','x')
            val prefix = if (file.isDirectory) 'd' else '-'
            prefix + String(CharArray(9) { i -> if (order[i] in perms) chars[i] else '-' })
        } catch (_: Exception) {
            // Fallback when POSIX attributes are unavailable (e.g. FAT32 SD card)
            val d = if (file.isDirectory) "d" else "-"
            val r = if (file.canRead())    "r" else "-"
            val w = if (file.canWrite())   "w" else "-"
            val x = if (file.canExecute()) "x" else "-"
            "$d${r}${w}${x}------"
        }
    }

    /** Compute MD5 / SHA-1 / SHA-256 hash of a file */
    suspend fun computeHash(file: File, algorithm: String = "SHA-256"): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Verify file against a .md5 or .sha256 sidecar file */
    suspend fun verifyChecksum(file: File): ChecksumResult = withContext(Dispatchers.IO) {
        val extensions = listOf("sha256", "sha1", "md5")
        val sidecar = extensions.mapNotNull { ext ->
            val f = File(file.parent, file.name + ".$ext")
            if (f.exists()) Pair(ext.uppercase().replace("SHA", "SHA-"), f) else null
        }.firstOrNull()
            ?: return@withContext ChecksumResult.NoSidecar

        val (algo, sidecarFile) = sidecar
        val expected = sidecarFile.readText().trim().split("\\s+".toRegex()).first().lowercase()
        val actual = computeHash(file, algo).lowercase()
        if (actual == expected) ChecksumResult.Match(algo, actual)
        else ChecksumResult.Mismatch(algo, expected, actual)
    }

    // ─── Storage Treemap ──────────────────────────────────────────────────────

    suspend fun buildStorageTree(path: String, depth: Int = 2): StorageNode = withContext(Dispatchers.IO) {
        buildNode(File(path), depth)
    }

    private fun buildNode(dir: File, remainingDepth: Int): StorageNode {
        if (!dir.isDirectory || remainingDepth == 0) {
            return StorageNode(dir.name, dir.length(), emptyList(), dir.isDirectory)
        }
        val children = dir.listFiles()
            ?.map { buildNode(it, remainingDepth - 1) }
            ?.sortedByDescending { it.size }
            ?: emptyList()
        val total = if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else dir.length()
        return StorageNode(dir.name, total, children, true)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Copy a single file, invoking [onDelta] with the number of bytes written
     * in each chunk (64 KB) so callers can accumulate transfer progress.
     * Throttled to at most one callback per [PROGRESS_THROTTLE_MS] ms to
     * avoid flooding the notification system on very fast local copies.
     */
    private fun copyFile(source: File, dest: File, onDelta: ((Long) -> Unit)? = null) {
        dest.parentFile?.mkdirs()
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var lastNotifyMs = System.currentTimeMillis()
        var pendingDelta = 0L
        source.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
            dest.outputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    if (onDelta != null) {
                        pendingDelta += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyMs >= PROGRESS_THROTTLE_MS) {
                            onDelta(pendingDelta)
                            pendingDelta = 0L
                            lastNotifyMs = now
                        }
                    }
                }
                // Flush remaining accumulated delta
                if (onDelta != null && pendingDelta > 0L) onDelta(pendingDelta)
            }
        }
    }

    /**
     * Recursively copy a directory tree, forwarding byte deltas via [onDelta]
     * so the caller can maintain a running total for overall progress display.
     */
    private fun copyDirectory(source: File, dest: File, onDelta: ((Long) -> Unit)? = null) {
        dest.mkdirs()
        source.listFiles()?.forEach { file ->
            val destFile = File(dest, file.name)
            if (file.isDirectory) copyDirectory(file, destFile, onDelta)
            else copyFile(file, destFile, onDelta)
        }
    }

    /** Rolling bytes-per-second since [startMs]. Returns 0 while less than 1 s has elapsed. */
    private fun calcSpeed(bytesTransferred: Long, startMs: Long): Long {
        val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
        return if (elapsedSec >= 1.0) (bytesTransferred / elapsedSec).toLong() else 0L
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024          // 64 KB I/O buffer
        private const val PROGRESS_THROTTLE_MS = 250L           // max 4 progress callbacks/sec
    }

    fun getFolderSize(path: String): Long {
        var size = 0L
        File(path).walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

// ─── Supporting Data Classes ──────────────────────────────────────────────────

data class StorageNode(
    val name: String,
    val size: Long,
    val children: List<StorageNode>,
    val isDirectory: Boolean
)

sealed class ChecksumResult {
    object NoSidecar : ChecksumResult()
    data class Match(val algorithm: String, val hash: String) : ChecksumResult()
    data class Mismatch(val algorithm: String, val expected: String, val actual: String) : ChecksumResult()
}

data class BatchRenameTemplate(
    val prefix: String = "",
    val suffix: String = "",
    val keepExtension: Boolean = true,
    val useCounter: Boolean = false,
    val counterStart: Int = 1,
    val counterPad: Int = 2,
    val regexFrom: String = "",
    val regexTo: String = ""
) {
    fun apply(originalName: String, index: Int): String {
        val nameNoExt = if (keepExtension) originalName.substringBeforeLast(".", originalName) else originalName
        val ext = if (keepExtension && originalName.contains('.')) ".${originalName.substringAfterLast('.')}" else ""

        var base = nameNoExt
        // Apply regex replace first if set
        if (regexFrom.isNotEmpty()) {
            base = try {
                base.replace(Regex(regexFrom), regexTo)
            } catch (_: Exception) { base }
        }

        val counter = if (useCounter) "${(index + counterStart - 1).toString().padStart(counterPad, '0')}" else ""
        return "$prefix$base$counter$suffix$ext"
    }
}
