package com.radiozport.ninegfiles.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─── Entities ────────────────────────────────────────────────────────────────

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
    val emoji: String = "📁"
)

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val path: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val accessedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "file_tags")
data class FileTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val tag: String,
    val color: Int
)

@Entity(tableName = "trash")
data class TrashEntity(
    @PrimaryKey val originalPath: String,
    val name: String,
    val trashPath: String,        // Where it lives inside .Trash/
    val size: Long,
    val isDirectory: Boolean,
    val deletedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val usedAt: Long = System.currentTimeMillis(),
    val useCount: Int = 1
)

@Entity(tableName = "recent_folders")
data class RecentFolderEntity(
    @PrimaryKey val path: String,
    val name: String,
    val visitedAt: Long = System.currentTimeMillis()
)

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY addedAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT path FROM bookmarks")
    suspend fun getAllBookmarkedPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE path = :path")
    suspend fun deleteBookmarkByPath(path: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE path = :path)")
    suspend fun isBookmarked(path: String): Boolean
}

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY accessedAt DESC LIMIT 50")
    fun getRecentFiles(): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentFile(file: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE path = :path")
    suspend fun deleteRecentFile(path: String)

    @Query("DELETE FROM recent_files WHERE accessedAt < :threshold")
    suspend fun deleteOldRecentFiles(threshold: Long)

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()
}

@Dao
interface FileTagDao {
    @Query("SELECT * FROM file_tags WHERE filePath = :path")
    fun getTagsForFile(path: String): Flow<List<FileTagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTag(tag: FileTagEntity)

    @Delete
    suspend fun removeTag(tag: FileTagEntity)

    @Query("SELECT DISTINCT tag FROM file_tags ORDER BY tag ASC")
    fun getAllTags(): Flow<List<String>>

    @Query("SELECT * FROM file_tags WHERE tag = :tag")
    fun getFilesByTag(tag: String): Flow<List<FileTagEntity>>
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun getAllTrashed(): Flow<List<TrashEntity>>

    @Query("SELECT COUNT(*) FROM trash")
    fun getTrashCount(): Flow<Int>

    @Query("SELECT SUM(size) FROM trash")
    fun getTrashSize(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashed(item: TrashEntity)

    @Delete
    suspend fun removeFromTrash(item: TrashEntity)

    @Query("DELETE FROM trash WHERE originalPath = :path")
    suspend fun removeByOriginalPath(path: String)

    @Query("DELETE FROM trash")
    suspend fun clearAll()

    @Query("SELECT * FROM trash WHERE deletedAt < :threshold")
    suspend fun getExpiredItems(threshold: Long): List<TrashEntity>
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY usedAt DESC LIMIT 20")
    fun getRecentSearches(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE query LIKE :prefix || '%' ORDER BY useCount DESC LIMIT 5")
    suspend fun getSuggestions(prefix: String): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: SearchHistoryEntity)

    @Query("""
        UPDATE search_history
        SET usedAt = :now, useCount = useCount + 1
        WHERE query = :query
    """)
    suspend fun bump(query: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}

@Dao
interface RecentFolderDao {
    @Query("SELECT * FROM recent_folders ORDER BY visitedAt DESC LIMIT 10")
    fun getRecentFolders(): Flow<List<RecentFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: RecentFolderEntity)

    @Query("DELETE FROM recent_folders WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM recent_folders")
    suspend fun clearAll()
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [
        BookmarkEntity::class,
        RecentFileEntity::class,
        FileTagEntity::class,
        TrashEntity::class,
        SearchHistoryEntity::class,
        RecentFolderEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FileManagerDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun recentFileDao(): RecentFileDao
    abstract fun fileTagDao(): FileTagDao
    abstract fun trashDao(): TrashDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun recentFolderDao(): RecentFolderDao
}
