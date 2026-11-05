package app.acloudroid.qwq.feature.photos.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import app.acloudroid.qwq.feature.photos.data.db.UploadQueueDao
import app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity
import app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Handles incoming shared media URIs (via ACTION_SEND / ACTION_SEND_MULTIPLE),
 * resolves metadata, copies transient content URIs if needed, detects Live/Motion photos,
 * enqueues items into [UploadQueueDao], and triggers background uploads.
 */
object ShareUploadHandler {

    private const val TAG = "ShareUploadHandler"
    private const val CACHE_DIR_NAME = "share_uploads"

    data class ShareItem(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val fileSize: Long,
        val isMotionPhoto: Boolean = false
    )

    /**
     * Extracts URI list from incoming Intent.
     */
    fun extractUrisFromIntent(intent: Intent): List<Uri> {
        val action = intent.action ?: return emptyList()
        val uris = mutableListOf<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                val uri = extractUriExtra(intent)
                if (uri != null) uris.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = extractUriListExtra(intent)
                if (list != null) uris.addAll(list.filterNotNull())
            }
        }
        return uris.distinct()
    }

    @Suppress("DEPRECATION")
    private fun extractUriExtra(intent: Intent): Uri? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractUriListExtra(intent: Intent): List<Uri?>? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
    }

    /**
     * Resolves metadata for a list of URIs.
     */
    suspend fun resolveShareItems(context: Context, uris: List<Uri>): List<ShareItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ShareItem>()
        for (uri in uris) {
            try {
                val item = resolveSingleItem(context, uri)
                if (item != null) {
                    result.add(item)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve metadata for $uri: ${e.message}")
            }
        }
        result
    }

    private fun resolveSingleItem(context: Context, uri: Uri): ShareItem? {
        val resolver = context.contentResolver
        var displayName = ""
        var fileSize = 0L
        var mimeType: String? = resolver.getType(uri)

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)

                    if (nameIdx != -1) displayName = cursor.getString(nameIdx) ?: ""
                    if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                }
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(uri.path ?: "")
            if (file.exists()) {
                displayName = file.name
                fileSize = file.length()
            }
        }

        if (displayName.isBlank()) {
            displayName = uri.lastPathSegment ?: "share_${System.currentTimeMillis()}"
        }

        val finalMimeType = if (mimeType.isNullOrBlank()) {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "heic", "heif" -> "image/heic"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                else -> if (mimeType?.startsWith("video/") == true) "video/mp4" else "image/jpeg"
            }
        } else {
            mimeType
        }

        if (fileSize <= 0) {
            try {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    fileSize = pfd.statSize
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query statSize for $uri: ${e.message}")
            }
        }

        val motionInfo = if (finalMimeType.startsWith("image/")) {
            MotionPhotoDetector.detect(context, uri, fileSize)
        } else {
            MotionPhotoInfo()
        }

        return ShareItem(
            uri = uri,
            displayName = displayName,
            mimeType = finalMimeType,
            fileSize = fileSize,
            isMotionPhoto = motionInfo.isMotionPhoto
        )
    }

    /**
     * Enqueues resolved share items into database and triggers background worker.
     *
     * Transitive/external content URIs are copied into local cache directory to ensure
     * background WorkManager tasks retain read permissions even after the share Activity finishes.
     */
    suspend fun enqueueShareItems(
        context: Context,
        uploadQueueDao: UploadQueueDao,
        items: List<ShareItem>
    ): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()
        val entities = mutableListOf<UploadQueueEntity>()
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

        var newlyAdded = 0
        for (item in items) {
            // Copy all content:// URIs to app private cache directory.
            // This ensures background WorkManager tasks can read shared files without requiring system
            // READ_MEDIA_IMAGES / READ_MEDIA_VIDEO permissions, leveraging the transient read permission
            // granted by the Share Sheet Intent while ShareUploadActivity is active.
            val targetUriString = if (item.uri.scheme == ContentResolver.SCHEME_FILE) {
                item.uri.toString()
            } else {
                val copiedFile = copyStreamToCache(context, item.uri, cacheDir, item.displayName)
                if (copiedFile != null) {
                    Uri.fromFile(copiedFile).toString()
                } else {
                    item.uri.toString()
                }
            }

            val existing = uploadQueueDao.findByUri(targetUriString)
            if (existing == null) {
                val motionInfo = if (item.mimeType.startsWith("image/")) {
                    MotionPhotoDetector.detect(context, Uri.parse(targetUriString), item.fileSize)
                } else {
                    MotionPhotoInfo()
                }

                entities.add(
                    UploadQueueEntity(
                        mediaStoreUri = targetUriString,
                        filename = item.displayName,
                        mimeType = item.mimeType,
                        fileSize = item.fileSize,
                        localSha256 = "",
                        status = "PENDING",
                        isMotionPhoto = motionInfo.isMotionPhoto,
                        videoOffset = motionInfo.videoOffset,
                        videoSize = motionInfo.videoSize,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                newlyAdded++
            }
        }

        if (entities.isNotEmpty()) {
            uploadQueueDao.insertAll(entities)
            Log.i(TAG, "Enqueued $newlyAdded shared items into upload_queue")
            PhotoBackupWorker.scheduleBackup(context)
        }

        newlyAdded
    }

    private fun isMediaStoreOrLocalFileUri(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return true
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val authority = uri.authority ?: ""
            return authority.startsWith("media") || authority == "com.android.providers.media.documents"
        }
        return false
    }

    private fun copyStreamToCache(
        context: Context,
        sourceUri: Uri,
        cacheDir: File,
        displayName: String
    ): File? {
        val safeName = "${UUID.randomUUID()}_${displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
        val targetFile = File(cacheDir, safeName)

        val readUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sourceUri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    MediaStore.setRequireOriginal(sourceUri)
                } else {
                    sourceUri
                }
            } catch (e: Exception) {
                sourceUri
            }
        } else {
            sourceUri
        }

        return try {
            context.contentResolver.openInputStream(readUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied transient share URI $sourceUri to ${targetFile.absolutePath}")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy transient share stream for $sourceUri", e)
            if (targetFile.exists()) targetFile.delete()
            null
        }
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.isBlank()
}
