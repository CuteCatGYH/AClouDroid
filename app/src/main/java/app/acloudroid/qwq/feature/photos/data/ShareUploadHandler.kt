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
                else -> {
                    val mapType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    mapType ?: "application/octet-stream"
                }
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
     * Result of [enqueueShareItems].
     *
     * [unreadable] is not decoration: a share URI whose bytes could not be copied must **not**
     * become a queue row. Doing so produced a row that the worker could never read (the transient
     * grant dies with the share Activity), so it cycled `PENDING -> fail -> PENDING` forever and the
     * backup sheet showed it as permanently "uploading" while nothing was uploading.
     */
    data class EnqueueResult(
        val enqueued: Int,
        val skippedAlreadyQueued: Int,
        val unreadable: List<String>
    )

    /**
     * Enqueues resolved share items into database and triggers background worker.
     *
     * Transitive/external content URIs are copied into local cache directory to ensure
     * background WorkManager tasks retain read permissions even after the share Activity finishes.
     * An item that cannot be copied is reported in [EnqueueResult.unreadable] and **dropped**.
     */
    suspend fun enqueueShareItems(
        context: Context,
        uploadQueueDao: UploadQueueDao,
        items: List<ShareItem>
    ): EnqueueResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext EnqueueResult(0, 0, emptyList())

        val now = System.currentTimeMillis()
        val entities = mutableListOf<UploadQueueEntity>()
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

        var newlyAdded = 0
        var skipped = 0
        val unreadable = mutableListOf<String>()

        for (item in items) {
            // Copy every content:// URI into the app's private cache. The share intent's read
            // grant is tied to this Activity, so a URI queued as-is would be unreadable by the
            // worker a few milliseconds later.
            val targetUriString = if (item.uri.scheme == ContentResolver.SCHEME_FILE) {
                item.uri.toString()
            } else {
                val copiedFile = copyStreamToCache(context, item.uri, cacheDir, item.displayName)
                if (copiedFile == null) {
                    // Deliberately NOT falling back to `item.uri.toString()`. Queuing an URI we
                    // already know is unreadable only moves the failure into the worker, where it
                    // surfaces as an item stuck on "uploading" instead of an honest error.
                    Log.w(
                        TAG,
                        "Dropping shared item ${item.displayName}: bytes could not be copied"
                    )
                    unreadable.add(item.displayName)
                    continue
                }
                Uri.fromFile(copiedFile).toString()
            }

            val existing = uploadQueueDao.findByUri(targetUriString)
            if (existing != null) {
                skipped++
                continue
            }

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

        if (entities.isNotEmpty()) {
            uploadQueueDao.insertAll(entities)
            Log.i(TAG, "Enqueued $newlyAdded shared items into upload_queue (skipped=$skipped)")
            PhotoBackupWorker.scheduleBackup(context)
        }

        EnqueueResult(newlyAdded, skipped, unreadable)
    }

    data class DriveEnqueueResult(
        val enqueuedCount: Int,
        val dedupedCount: Int,
        val revivedCount: Int,
        val renamedCount: Int,
        val unreadable: List<String>
    )

    /**
     * Enqueues resolved share items into iCloud Drive repository.
     */
    suspend fun enqueueDriveShareItems(
        driveRepository: app.acloudroid.qwq.feature.drive.data.DriveRepository,
        items: List<ShareItem>,
        targetFolderDrivewsId: String = app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity.ROOT_DRIVEWSID,
        targetFolderPath: String = "/"
    ): DriveEnqueueResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext DriveEnqueueResult(0, 0, 0, 0, emptyList())

        var enqueued = 0
        var deduped = 0
        var revived = 0
        var renamed = 0
        val unreadable = mutableListOf<String>()

        for (item in items) {
            val res = driveRepository.enqueueUpload(
                uri = item.uri,
                targetFolderDrivewsId = targetFolderDrivewsId,
                targetFolderPath = targetFolderPath
            ) { outcome ->
                when (outcome) {
                    is app.acloudroid.qwq.feature.drive.data.DriveRepository.EnqueueOutcome.Deduped -> deduped++
                    is app.acloudroid.qwq.feature.drive.data.DriveRepository.EnqueueOutcome.Revived -> revived++
                    is app.acloudroid.qwq.feature.drive.data.DriveRepository.EnqueueOutcome.Renamed -> renamed++
                    is app.acloudroid.qwq.feature.drive.data.DriveRepository.EnqueueOutcome.Queued -> enqueued++
                }
            }
            if (res.isFailure) {
                Log.w(TAG, "Failed to enqueue drive item ${item.displayName}: ${res.exceptionOrNull()?.message}")
                unreadable.add(item.displayName)
            }
        }

        DriveEnqueueResult(
            enqueuedCount = enqueued,
            dedupedCount = deduped,
            revivedCount = revived,
            renamedCount = renamed,
            unreadable = unreadable
        )
    }

    private fun isMediaStoreOrLocalFileUri(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return true
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val authority = uri.authority ?: ""
            return authority.startsWith("media") || authority == "com.android.providers.media.documents"
        }
        return false
    }

    /**
     * Copies a (possibly transient) share URI into the app's private cache so the background
     * worker can still read it after the share Activity is gone.
     *
     * ## Why `setRequireOriginal` is gated on the authority
     *
     * `requireOriginal` un-redacts GPS, so it looks like it should always be added. It must not
     * be: `setRequireOriginal` rewrites the URI to `...?requireOriginal=1`, and a third-party
     * provider treats that as a *different, un-granted* URI. Samsung's Gallery provider rejects it
     * outright:
     *
     * ```
     * Permission Denial: reading com.samsung.android.gallery.app.provider.GalleryFileProvider
     *   uri content://com.sec.android.gallery3d.fileprovider/...?requireOriginal=1
     *   requires the provider be exported, or grantUriPermission()
     * ```
     *
     * The share intent grants read access to the URI it handed us, not to a rewritten variant of
     * it. Only MediaStore's own `content://media/...` URIs understand the parameter, so only those
     * get it. The second attempt below retries the untouched URI, which keeps a provider that
     * rejects even the plain form from silently losing the file.
     */
    private fun copyStreamToCache(
        context: Context,
        sourceUri: Uri,
        cacheDir: File,
        displayName: String
    ): File? {
        val safeName = "${UUID.randomUUID()}_${displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
        val targetFile = File(cacheDir, safeName)

        val canUnredact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            looksLikeMediaStoreUri(sourceUri) &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        // First attempt keeps GPS; second attempt drops it so the file still gets uploaded.
        // Losing the location of a shared photo is a far smaller loss than failing the upload.
        val candidates = if (canUnredact) {
            listOf(
                runCatching { MediaStore.setRequireOriginal(sourceUri) }.getOrDefault(sourceUri),
                sourceUri
            )
        } else {
            listOf(sourceUri)
        }

        for (uri in candidates.distinct()) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(
                    TAG,
                    "Copied transient share URI $sourceUri to ${targetFile.absolutePath} " +
                        "(${targetFile.length()}B, unredacted=${uri !== sourceUri})"
                )
                return targetFile
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Copy attempt failed for $sourceUri " +
                        "(unredacted=${uri !== sourceUri}): ${e.javaClass.simpleName}: ${e.message}"
                )
                if (targetFile.exists()) targetFile.delete()
            }
        }

        Log.e(TAG, "Failed to copy transient share stream for $sourceUri (all attempts exhausted)")
        return null
    }

    /**
     * Whether [uri] is served by MediaProvider itself.
     *
     * Deliberately a whitelist of the two authorities MediaStore uses (`media` and the SAF
     * document provider) rather than a blacklist: any other content provider is a third-party
     * `FileProvider` and must be read through the exact URI we were granted.
     */
    internal fun looksLikeMediaStoreUri(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        return isMediaStoreAuthority(uri.authority)
    }

    /**
     * Authority half of [looksLikeMediaStoreUri], split out so it is testable without an Android
     * `Uri` (the project's unit tests are plain JVM - no Robolectric).
     *
     * `endsWith(".media")` covers vendor packages that embed MediaProvider
     * (`com.google.android.providers.media.module.media`, `com.samsung.android.providers.media.media`),
     * which do honour `requireOriginal`. A *substring* match would be wrong: a hypothetical
     * `com.some.mediabrowser.provider` is a different provider.
     */
    internal fun isMediaStoreAuthority(authority: String?): Boolean {
        if (authority.isNullOrBlank()) return false
        return authority == "media" ||
            authority == "com.android.providers.media.documents" ||
            authority.endsWith(".media")
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.isBlank()
}
