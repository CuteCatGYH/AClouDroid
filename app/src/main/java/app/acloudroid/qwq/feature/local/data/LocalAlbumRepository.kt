package app.acloudroid.qwq.feature.local.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository querying local MediaStore for photos and videos grouped by folder (bucket).
 */
class LocalAlbumRepository(private val context: Context) {

    suspend fun queryLocalAlbums(enabledBucketIds: Set<String> = emptySet()): List<LocalAlbum> = withContext(Dispatchers.IO) {
        val albumsMap = linkedMapOf<String, AlbumAccumulator>()

        // 1. Query Images
        queryMediaBuckets(
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isImage = true,
            accumulator = albumsMap
        )

        // 2. Query Videos
        queryMediaBuckets(
            contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isImage = false,
            accumulator = albumsMap
        )

        albumsMap.values.map { acc ->
            LocalAlbum(
                bucketId = acc.bucketId,
                name = acc.name,
                coverUri = acc.coverUri,
                itemCount = acc.count,
                relativePath = acc.relativePath,
                isBackupEnabled = acc.bucketId in enabledBucketIds
            )
        }.sortedByDescending { it.itemCount }
    }

    /**
     * Scans local media for a given bucketId and enqueues missing items into [UploadQueueDao].
     *
     * @return Number of newly enqueued items.
     */
    suspend fun scanAndEnqueueBucket(
        bucketId: String,
        uploadQueueDao: app.acloudroid.qwq.feature.photos.data.db.UploadQueueDao
    ): Int = withContext(Dispatchers.IO) {
        val mediaItems = mutableListOf<LocalMediaItem>()

        queryBucketMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, bucketId, mediaItems)
        queryBucketMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bucketId, mediaItems)

        var newlyEnqueued = 0
        val now = System.currentTimeMillis()
        val entitiesToInsert = mutableListOf<app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity>()

        for (item in mediaItems) {
            val uriStr = item.uri.toString()
            val existing = uploadQueueDao.findByUri(uriStr)
            if (existing == null) {
                val motionInfo = if (item.mimeType.startsWith("image/")) {
                    app.acloudroid.qwq.feature.photos.data.MotionPhotoDetector.detect(context, item.uri, item.size)
                } else {
                    app.acloudroid.qwq.feature.photos.data.MotionPhotoInfo()
                }

                entitiesToInsert.add(
                    app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity(
                        mediaStoreUri = uriStr,
                        filename = item.displayName,
                        mimeType = item.mimeType,
                        fileSize = item.size,
                        localSha256 = "", // Calculated during streaming upload
                        status = "PENDING",
                        isMotionPhoto = motionInfo.isMotionPhoto,
                        videoOffset = motionInfo.videoOffset,
                        videoSize = motionInfo.videoSize,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                newlyEnqueued++
            } else if (!existing.isMotionPhoto && item.mimeType.startsWith("image/")) {
                val motionInfo = app.acloudroid.qwq.feature.photos.data.MotionPhotoDetector.detect(context, item.uri, item.size)
                if (motionInfo.isMotionPhoto) {
                    uploadQueueDao.update(
                        existing.copy(
                            isMotionPhoto = true,
                            videoOffset = motionInfo.videoOffset,
                            videoSize = motionInfo.videoSize,
                            updatedAt = now
                        )
                    )
                }
            }
        }

        if (entitiesToInsert.isNotEmpty()) {
            uploadQueueDao.insertAll(entitiesToInsert)
        }

        newlyEnqueued
    }

    private fun queryBucketMedia(
        contentUri: Uri,
        bucketId: String,
        output: MutableList<LocalMediaItem>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.BUCKET_ID
        )

        val selection = "${MediaStore.MediaColumns.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "unnamed"
                    val mimeType = cursor.getString(mimeCol) ?: if (contentUri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) "video/mp4" else "image/jpeg"
                    val dateModified = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)

                    val itemUri = ContentUris.withAppendedId(contentUri, id)

                    output.add(
                        LocalMediaItem(
                            id = id,
                            uri = itemUri,
                            displayName = displayName,
                            mimeType = mimeType,
                            dateTaken = dateModified * 1000L,
                            size = size,
                            bucketId = bucketId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Permission or query failure
        }
    }

    private fun queryMediaBuckets(
        contentUri: Uri,
        isImage: Boolean,
        accumulator: MutableMap<String, AlbumAccumulator>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                contentUri,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val bucketId = cursor.getString(bucketIdCol) ?: "unknown"
                    val bucketName = cursor.getString(bucketNameCol) ?: "未命名相册"

                    val itemUri = ContentUris.withAppendedId(contentUri, id)

                    val existing = accumulator[bucketId]
                    if (existing == null) {
                        accumulator[bucketId] = AlbumAccumulator(
                            bucketId = bucketId,
                            name = bucketName,
                            coverUri = itemUri,
                            count = 1
                        )
                    } else {
                        existing.count += 1
                        if (existing.coverUri == null) {
                            existing.coverUri = itemUri
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Permission denied or table unavailable
        }
    }

    private data class AlbumAccumulator(
        val bucketId: String,
        val name: String,
        var coverUri: Uri?,
        var count: Int,
        var relativePath: String = ""
    )
}
