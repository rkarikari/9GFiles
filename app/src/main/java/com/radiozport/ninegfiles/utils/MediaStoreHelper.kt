package com.radiozport.ninegfiles.utils

import android.content.Context
import android.provider.MediaStore
import com.radiozport.ninegfiles.data.db.RecentFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaCounts(
    val images: Long = 0L,
    val videos: Long = 0L,
    val audio: Long = 0L,
    val documents: Long = 0L,
    val imageSize: Long = 0L,
    val videoSize: Long = 0L,
    val audioSize: Long = 0L,
    val documentSize: Long = 0L
)

object MediaStoreHelper {

    suspend fun getMediaCounts(context: Context): MediaCounts = withContext(Dispatchers.IO) {
        MediaCounts(
            images = queryCount(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
            videos = queryCount(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
            audio = queryCount(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
            imageSize = querySize(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
            videoSize = querySize(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
            audioSize = querySize(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        )
    }

    private fun queryCount(context: Context, uri: android.net.Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID),
                null, null, null)?.use { it.count.toLong() } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun querySize(context: Context, uri: android.net.Uri): Long {
        return try {
            var total = 0L
            context.contentResolver.query(uri,
                arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
            )?.use { cursor ->
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    if (sizeCol >= 0) total += cursor.getLong(sizeCol)
                }
            }
            total
        } catch (_: Exception) { 0L }
    }

    /**
     * Query MediaStore.Files for ALL file types (images, video, audio, documents, APKs,
     * archives, etc.) sorted by DATE_MODIFIED descending. This is the correct data source
     * for a "recently modified" list — it reflects actual device files rather than only
     * files previously opened inside this app.
     *
     * Returns [RecentFileEntity] directly so the existing [RecentAdapter] works unchanged.
     * The [RecentFileEntity.accessedAt] field holds the file's last-modified timestamp (ms).
     */
    suspend fun getRecentlyModifiedFiles(
        context: Context,
        limit: Int = 30
    ): List<RecentFileEntity> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RecentFileEntity>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        // Exclude directories and zero-byte entries; MIME_TYPE is null for directories
        // in the Files table on most Android versions.
        val selection =
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL AND " +
            "${MediaStore.Files.FileColumns.MIME_TYPE} != '' AND " +
            "${MediaStore.Files.FileColumns.SIZE} > 0"
        try {
            context.contentResolver.query(
                uri, projection, selection, null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val path = cursor.getString(dataCol)?.takeIf { it.isNotEmpty() } ?: continue
                    results.add(
                        RecentFileEntity(
                            path     = path,
                            name     = cursor.getString(nameCol) ?: java.io.File(path).name,
                            mimeType = cursor.getString(mimeCol) ?: "*/*",
                            size     = cursor.getLong(sizeCol),
                            // DATE_MODIFIED is in seconds; RecentFileEntity.accessedAt is ms
                            accessedAt = cursor.getLong(dateCol) * 1_000L
                        )
                    )
                    count++
                }
            }
        } catch (_: Exception) {}
        results
    }

    /** Get recently-accessed media files from MediaStore, sorted by date_modified DESC */
    suspend fun getRecentMedia(context: Context, limit: Int = 20): List<RecentMediaItem> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<RecentMediaItem>()

            val uris = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image",
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video",
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "audio"
            )

            for ((uri, type) in uris) {
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.DATE_MODIFIED
                        ),
                        null, null,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT ${limit / 3}"
                    )?.use { cursor ->
                        val idCol   = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                        while (cursor.moveToNext()) {
                            results.add(
                                RecentMediaItem(
                                    id = cursor.getLong(idCol),
                                    name = cursor.getString(nameCol) ?: "",
                                    path = cursor.getString(dataCol) ?: "",
                                    size = cursor.getLong(sizeCol),
                                    dateModified = cursor.getLong(dateCol) * 1000L,
                                    mediaType = type,
                                    contentUri = android.content.ContentUris.withAppendedId(uri, cursor.getLong(idCol))
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            results.sortedByDescending { it.dateModified }.take(limit)
        }

    /** Search all media types in MediaStore by display name */
    suspend fun searchMedia(context: Context, query: String): List<RecentMediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            val results = mutableListOf<RecentMediaItem>()
            val uris = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image",
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video",
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "audio"
            )

            for ((uri, type) in uris) {
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.DATE_MODIFIED
                        ),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$query%"),
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT 100"
                    )?.use { cursor ->
                        val idCol   = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                        while (cursor.moveToNext()) {
                            results.add(
                                RecentMediaItem(
                                    id = cursor.getLong(idCol),
                                    name = cursor.getString(nameCol) ?: "",
                                    path = cursor.getString(dataCol) ?: "",
                                    size = cursor.getLong(sizeCol),
                                    dateModified = cursor.getLong(dateCol) * 1000L,
                                    mediaType = type,
                                    contentUri = android.content.ContentUris.withAppendedId(uri, cursor.getLong(idCol))
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            results.sortedByDescending { it.dateModified }
        }
}

data class RecentMediaItem(
    val id: Long,
    val name: String,
    val path: String,
    val size: Long,
    val dateModified: Long,
    val mediaType: String,       // "image", "video", "audio"
    val contentUri: android.net.Uri
)
