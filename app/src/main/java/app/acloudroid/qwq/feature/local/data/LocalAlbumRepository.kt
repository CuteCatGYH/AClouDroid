package app.acloudroid.qwq.feature.local.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.acloudroid.qwq.feature.photos.data.db.UploadQueueDao
import app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity
import app.acloudroid.qwq.feature.photos.data.db.UploadedFingerprintDao
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
     * An item is skipped when either the queue already holds a row for its MediaStore URI, or
     * [UploadedFingerprintDao] remembers it was uploaded before. The second check is what makes
     * clearing the backup queue safe: the queue is a disposable *view* of pending work, while
     * "these bytes are already in the cloud" lives in `uploaded_fingerprints`, which no UI can
     * delete from (see [app.acloudroid.qwq.feature.photos.data.db.UploadedFingerprintEntity]).
     *
     * Checking here rather than inside the worker matters: a skipped item is never enqueued, so it
     * never shows up in the queue sheet as a phantom "pending" row and never costs a session warm
     * up. The check is free -- `queryBucketMedia` already selects every column the key needs.
     *
     * @return Number of newly enqueued items.
     */
    suspend fun scanAndEnqueueBucket(
        bucketId: String,
        uploadQueueDao: UploadQueueDao,
        uploadedFingerprintDao: UploadedFingerprintDao
    ): Int = withContext(Dispatchers.IO) {
        val mediaItems = mutableListOf<LocalMediaItem>()

        queryBucketMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, bucketId, mediaItems)
        queryBucketMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bucketId, mediaItems)

        var newlyEnqueued = 0
        var skippedAlreadyUploaded = 0
        val now = System.currentTimeMillis()
        val entitiesToInsert = mutableListOf<UploadQueueEntity>()

        for (item in mediaItems) {
            val uriStr = item.uri.toString()
            val existing = uploadQueueDao.findByUri(uriStr)
            if (existing == null) {
                // `item.dateTaken` is MediaStore DATE_MODIFIED * 1000 (see queryBucketMedia), which
                // is exactly the value the fingerprint key is built from.
                val fingerprintKey = buildFingerprintKey(
                    displayName = item.displayName,
                    size = item.size,
                    dateModifiedSeconds = item.dateTaken / 1000L
                )
                // Only a *confirmed* fingerprint may skip. A placeholder row (masterId = '') means
                // "an upload was started for these bytes", not "they are in the cloud" -- the
                // upload may still fail, and skipping on it would strand the file forever once it
                // hit the retry ceiling in a bad network (ISS-069).
                //
                // In that case we fall through and enqueue normally. That is safe: the queue's own
                // `findByUri` above already prevents a second row for a file that is still being
                // retried, so falling through here only decides about files whose queue row is
                // gone (cleared by the user) while their upload never succeeded.
                if (uploadedFingerprintDao.isConfirmed(fingerprintKey)) {
                    skippedAlreadyUploaded++
                    continue
                }

                val motionInfo = if (item.mimeType.startsWith("image/")) {
                    app.acloudroid.qwq.feature.photos.data.MotionPhotoDetector.detect(context, item.uri, item.size)
                } else {
                    app.acloudroid.qwq.feature.photos.data.MotionPhotoInfo()
                }

                entitiesToInsert.add(
                    UploadQueueEntity(
                        mediaStoreUri = uriStr,
                        filename = item.displayName,
                        mimeType = item.mimeType,
                        fileSize = item.size,
                        dateModified = item.dateTaken / 1000L,
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

        if (skippedAlreadyUploaded > 0) {
            android.util.Log.d(
                "LocalAlbumRepository",
                "scanAndEnqueueBucket($bucketId): enqueued=$newlyEnqueued, alreadyUploaded=$skippedAlreadyUploaded"
            )
        }

        newlyEnqueued
    }

    companion object {
        /**
         * Builds the [UploadedFingerprintEntity.contentKey] for a local file.
         *
         * **Every** producer and consumer of a fingerprint must call this function -- the scan
         * path here and the upload path in
         * [app.acloudroid.qwq.feature.photos.data.PhotoRepository.uploadPhotoFromQueue]. If the two
         * sides ever format the key differently, lookups never match and the table silently
         * degrades into dead weight that still looks like it is working. This is the same class of
         * bug as binding a Coil cache key to a signed URL (AGENTS.md §6.3), so it gets a single
         * definition rather than two `"$a|$b|$c"` literals.
         *
         * Why these three fields: `displayName` + `size` is already close to unique, and
         * `dateModified` disambiguates the realistic collision (burst shots that MediaStore
         * renames to `IMG_0001 (1).jpg`, `(2)`, ... all share a size). All three are returned by
         * the existing `queryBucketMedia` projection, so the check costs no extra disk or binder
         * IO -- hashing the file instead would mean reading every photo and video on every scan,
         * which is precisely the cost this table exists to avoid.
         *
         * @param dateModifiedSeconds MediaStore `DATE_MODIFIED`, **in seconds**. Milliseconds here
         *   would still be self-consistent, but a future caller reading the raw column would then
         *   mismatch silently, so the unit is fixed by the parameter name.
         */
        fun buildFingerprintKey(
            displayName: String,
            size: Long,
            dateModifiedSeconds: Long
        ): String = "$displayName|$size|$dateModifiedSeconds"
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
