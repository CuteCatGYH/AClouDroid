package app.acloudroid.qwq.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.R
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Storage Access Framework (SAF) [DocumentsProvider] implementation for AClouDroid.
 *
 * Integrates AClouDroid into the Android System File Picker (DocumentsUI).
 * Renders two top-level virtual folders:
 *  - "iCloud Photo": Grouped by year-month subfolders (e.g. "2026年11月")
 *  - "iCloud Drive": Lists iCloud Drive files and sub-folders
 */
class ICloudDocumentsProvider : DocumentsProvider() {

    private val container by lazy {
        (context?.applicationContext as? IClouDroidApp)?.container
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DOC_ID_ROOT)
        row.add(
            DocumentsContract.Root.COLUMN_TITLE,
            context?.getString(R.string.documents_provider_label) ?: "iCloud"
        )

        val hasSession = container?.sessionManager?.isLoggedIn() == true
        val summary = if (hasSession) {
            val dsid = container?.sessionManager?.getDsid()
            if (!dsid.isNullOrBlank()) "iCloud ($dsid)" else "iCloud Photos & Drive"
        } else {
            "未登录 (未关联 iCloud 账号)"
        }
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, summary)
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
        )
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, null)
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        when {
            documentId == DOC_ID_ROOT -> {
                includeRootDocument(result)
            }
            documentId == DOC_ID_PHOTOS -> {
                includePhotosFolderDocument(result)
            }
            documentId == DOC_ID_DRIVE -> {
                includeDriveFolderDocument(result)
            }
            documentId.startsWith(PREFIX_PHOTO_YM) -> {
                val ymStr = documentId.removePrefix(PREFIX_PHOTO_YM)
                val ym = ymStr.toIntOrNull() ?: 0
                val title = if (ym == 0) "未归档照片" else "%d年%02d月".format(ym / 100, ym % 100)
                includeYearMonthFolderDocument(result, ym, title)
            }
            documentId.startsWith(PREFIX_PHOTO) -> {
                val photoId = documentId.removePrefix(PREFIX_PHOTO)
                val asset = runBlocking(Dispatchers.IO) {
                    try {
                        container?.photoDatabase?.photoDao()?.getById(photoId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to query photo $photoId", e)
                        null
                    }
                }
                if (asset != null) {
                    includePhotoDocument(result, asset)
                }
            }
            documentId.startsWith(PREFIX_DRIVE) -> {
                val drivewsid = documentId.removePrefix(PREFIX_DRIVE)
                val item = runBlocking(Dispatchers.IO) {
                    try {
                        container?.driveDatabase?.driveItemDao()?.getById(drivewsid)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to query drive item $drivewsid", e)
                        null
                    }
                }
                if (item != null) {
                    includeDriveDocument(result, item)
                }
            }
        }
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        when {
            parentDocumentId == DOC_ID_ROOT -> {
                includePhotosFolderDocument(result)
                includeDriveFolderDocument(result)
            }
            parentDocumentId == DOC_ID_PHOTOS -> {
                runBlocking(Dispatchers.IO) {
                    try {
                        val photoDao = container?.photoDatabase?.photoDao()
                        val months = photoDao?.distinctYearMonths() ?: emptyList()
                        val unbucketed = photoDao?.getUnbucketedPhotos() ?: emptyList()

                        if (months.isNotEmpty()) {
                            for (ym in months) {
                                val year = ym / 100
                                val month = ym % 100
                                val title = "%d年%02d月".format(year, month)
                                includeYearMonthFolderDocument(result, ym, title)
                            }
                            if (unbucketed.isNotEmpty()) {
                                includeYearMonthFolderDocument(result, 0, "未归档照片")
                            }
                        } else {
                            val allPhotos = photoDao?.getAllPhotosList() ?: emptyList()
                            for (photo in allPhotos) {
                                includePhotoDocument(result, photo)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to query photo folders for SAF", e)
                    }
                }
            }
            parentDocumentId.startsWith(PREFIX_PHOTO_YM) -> {
                val ymStr = parentDocumentId.removePrefix(PREFIX_PHOTO_YM)
                val ym = ymStr.toIntOrNull() ?: 0
                runBlocking(Dispatchers.IO) {
                    try {
                        val photoDao = container?.photoDatabase?.photoDao()
                        val photos = if (ym == 0) {
                            photoDao?.getUnbucketedPhotos() ?: emptyList()
                        } else {
                            photoDao?.getPhotosInMonth(ym) ?: emptyList()
                        }
                        for (photo in photos) {
                            includePhotoDocument(result, photo)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to list photos in month $ym for SAF", e)
                    }
                }
            }
            parentDocumentId == DOC_ID_DRIVE -> {
                queryAndIncludeDriveChildren(result, DriveItemEntity.ROOT_DRIVEWSID)
            }
            parentDocumentId.startsWith(PREFIX_DRIVE) -> {
                val drivewsid = parentDocumentId.removePrefix(PREFIX_DRIVE)
                queryAndIncludeDriveChildren(result, drivewsid)
            }
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.i(TAG, "openDocument: id=$documentId, mode=$mode")
        return when {
            documentId.startsWith(PREFIX_PHOTO) -> {
                val photoId = documentId.removePrefix(PREFIX_PHOTO)
                openPhotoDocument(photoId)
            }
            documentId.startsWith(PREFIX_DRIVE) -> {
                val drivewsid = documentId.removePrefix(PREFIX_DRIVE)
                openDriveDocument(drivewsid)
            }
            else -> {
                throw FileNotFoundException("Unsupported document ID: $documentId")
            }
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor? {
        return runBlocking(Dispatchers.IO) {
            try {
                if (documentId.startsWith(PREFIX_PHOTO)) {
                    val photoId = documentId.removePrefix(PREFIX_PHOTO)
                    val asset = container?.photoDatabase?.photoDao()?.getById(photoId) ?: return@runBlocking null
                    val thumbFile = getPhotoThumbnailFile(asset)
                    if (thumbFile != null && isPlausiblePreview(thumbFile)) {
                        val pfd = ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        return@runBlocking AssetFileDescriptor(pfd, 0, thumbFile.length())
                    }
                } else if (documentId.startsWith(PREFIX_DRIVE)) {
                    val drivewsid = documentId.removePrefix(PREFIX_DRIVE)
                    val item = container?.driveDatabase?.driveItemDao()?.getById(drivewsid) ?: return@runBlocking null
                    val thumbFile = getDriveThumbnailFile(item)
                    if (thumbFile != null && isPlausiblePreview(thumbFile)) {
                        val pfd = ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        return@runBlocking AssetFileDescriptor(pfd, 0, thumbFile.length())
                    }
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "openDocumentThumbnail failed for $documentId: ${e.message}")
                null
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == DOC_ID_ROOT) {
            return documentId == DOC_ID_PHOTOS || documentId == DOC_ID_DRIVE ||
                documentId.startsWith(PREFIX_PHOTO_YM) || documentId.startsWith(PREFIX_PHOTO) ||
                documentId.startsWith(PREFIX_DRIVE)
        }
        if (parentDocumentId == DOC_ID_PHOTOS) {
            return documentId.startsWith(PREFIX_PHOTO_YM) || documentId.startsWith(PREFIX_PHOTO)
        }
        if (parentDocumentId.startsWith(PREFIX_PHOTO_YM)) {
            return documentId.startsWith(PREFIX_PHOTO)
        }
        if (parentDocumentId == DOC_ID_DRIVE) {
            return documentId.startsWith(PREFIX_DRIVE)
        }
        if (parentDocumentId.startsWith(PREFIX_DRIVE) && documentId.startsWith(PREFIX_DRIVE)) {
            val parentDrivewsid = parentDocumentId.removePrefix(PREFIX_DRIVE)
            val childDrivewsid = documentId.removePrefix(PREFIX_DRIVE)
            val childItem = runBlocking(Dispatchers.IO) {
                container?.driveDatabase?.driveItemDao()?.getById(childDrivewsid)
            }
            return childItem?.parentDrivewsid == parentDrivewsid
        }
        return false
    }

    private fun queryAndIncludeDriveChildren(result: MatrixCursor, parentDrivewsid: String) {
        runBlocking(Dispatchers.IO) {
            try {
                val repo = container?.driveRepository
                val dao = container?.driveDatabase?.driveItemDao()
                if (repo != null && dao != null) {
                    repo.ensureRoot()
                    var children = dao.getChildren(parentDrivewsid)
                    if (children.isEmpty()) {
                        repo.refreshFolder(parentDrivewsid)
                        children = dao.getChildren(parentDrivewsid)
                    }
                    for (child in children) {
                        includeDriveDocument(result, child)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query drive children for $parentDrivewsid", e)
            }
        }
    }

    private fun includeRootDocument(result: MatrixCursor) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DOC_ID_ROOT)
        row.add(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            context?.getString(R.string.documents_provider_label) ?: "iCloud"
        )
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID)
    }

    private fun includePhotosFolderDocument(result: MatrixCursor) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DOC_ID_PHOTOS)
        row.add(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            context?.getString(R.string.icloud_photo_folder_name) ?: "iCloud Photo"
        )
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID)
    }

    private fun includeYearMonthFolderDocument(result: MatrixCursor, yearMonth: Int, displayName: String) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "$PREFIX_PHOTO_YM$yearMonth")
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID)
        if (yearMonth > 0) {
            val year = yearMonth / 100
            val month = yearMonth % 100
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(year, month - 1, 1, 0, 0, 0)
            }
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, cal.timeInMillis)
        }
    }

    private fun includeDriveFolderDocument(result: MatrixCursor) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DOC_ID_DRIVE)
        row.add(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            context?.getString(R.string.icloud_drive_folder_name) ?: "iCloud Drive"
        )
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID)
    }

    private fun includePhotoDocument(result: MatrixCursor, asset: PhotoAssetEntity) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "$PREFIX_PHOTO${asset.id}")
        val name = when {
            asset.filename.isNotBlank() -> asset.filename
            asset.mediaType == "VIDEO" -> "VID_${asset.id}.mp4"
            else -> "IMG_${asset.id}.jpg"
        }
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
        val mime = resolvePhotoMimeType(asset)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
        row.add(DocumentsContract.Document.COLUMN_SIZE, asset.size)
        val lastModified = if (asset.assetDate < 10_000_000_000L) {
            asset.assetDate * 1000L
        } else {
            asset.assetDate
        }
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModified)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
    }

    private fun includeDriveDocument(result: MatrixCursor, item: DriveItemEntity) {
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "$PREFIX_DRIVE${item.drivewsid}")
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, item.displayName)
        if (item.isContainer) {
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID)
        } else {
            val mime = resolveDriveMimeType(item)
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
            row.add(DocumentsContract.Document.COLUMN_SIZE, item.size)
            val modifiedTime = parseIsoToEpochMillis(item.dateModified)
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, modifiedTime)
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
        }
    }

    private fun openPhotoDocument(photoId: String): ParcelFileDescriptor {
        return runBlocking(Dispatchers.IO) {
            val repo = container?.photoRepository ?: throw FileNotFoundException("Photo repository unavailable")
            val dao = container?.photoDatabase?.photoDao() ?: throw FileNotFoundException("Photo database unavailable")
            val asset = dao.getById(photoId) ?: throw FileNotFoundException("Photo asset $photoId not found")

            val cacheDir = File(context?.cacheDir, "documents_photo_cache").apply { mkdirs() }
            val ext = if (asset.mediaType == "VIDEO") "mp4" else "jpg"
            val targetFile = File(cacheDir, "${asset.id}.$ext")

            if (targetFile.exists() && targetFile.length() > 0) {
                return@runBlocking ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            repo.refreshUrlsForMaster(asset.masterId)
            val freshAsset = dao.getById(photoId) ?: asset
            val urlStr = freshAsset.originalUrl ?: freshAsset.medUrl ?: freshAsset.thumbUrl
                ?: throw FileNotFoundException("No download URL for photo ${asset.id}")

            if (!downloadToFile(urlStr, targetFile)) {
                throw FileNotFoundException("Failed to download photo ${asset.id}")
            }

            ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private fun openDriveDocument(drivewsid: String): ParcelFileDescriptor {
        return runBlocking(Dispatchers.IO) {
            val repo = container?.driveRepository ?: throw FileNotFoundException("Drive repository unavailable")
            val dao = container?.driveDatabase?.driveItemDao() ?: throw FileNotFoundException("Drive database unavailable")
            val item = dao.getById(drivewsid) ?: throw FileNotFoundException("Drive item $drivewsid not found")

            if (item.isContainer) {
                throw FileNotFoundException("Cannot open directory $drivewsid")
            }

            val fileResult = repo.fetchFileToPreviewCache(item)
            if (fileResult.isFailure) {
                throw FileNotFoundException("Failed to download drive file: ${fileResult.exceptionOrNull()?.message}")
            }

            val file = fileResult.getOrThrow()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private suspend fun getPhotoThumbnailFile(asset: PhotoAssetEntity): File? {
        val appContext = context?.applicationContext ?: return null
        val cacheDir = appContext.cacheDir ?: return null

        // 1. Check CloudMediaProvider thumbnail cache
        val cmpThumb = File(cacheDir, "cmp_previews/${asset.id}.thumb")
        if (isPlausiblePreview(cmpThumb)) {
            return cmpThumb
        }

        // 2. Check documents_photo_thumbs cache
        val thumbDir = File(cacheDir, "documents_photo_thumbs").apply { if (!exists()) mkdirs() }
        val docThumb = File(thumbDir, "${asset.id}.jpg")
        if (isPlausiblePreview(docThumb)) {
            return docThumb
        }

        // 3. Check Coil disk cache (image_cache)
        val container = (appContext as? IClouDroidApp)?.container
        if (container != null) {
            try {
                val loader = ICloudImageLoaderFactory.get(appContext, container)
                val snapshot = loader.diskCache?.openSnapshot("icloud_${asset.id}_thumb")
                    ?: loader.diskCache?.openSnapshot("icloud_${asset.masterId}_thumb")
                    ?: loader.diskCache?.openSnapshot("icloud_${asset.id}_medium")
                    ?: loader.diskCache?.openSnapshot("icloud_${asset.id}_original")
                if (snapshot != null) {
                    val file = snapshot.data.toNioPath().toFile()
                    if (isPlausiblePreview(file)) {
                        return file
                    }
                }
            } catch (e: Exception) {
                // Ignore Coil snapshot read failure
            }
        }

        // 4. Download thumbnail if missing or if cached URL expired
        val repo = container?.photoRepository ?: return null
        val dao = container.photoDatabase.photoDao()

        fun pickUrl(a: PhotoAssetEntity): String? {
            return if (a.mediaType == "VIDEO" || a.mediaType == "LIVE_PHOTO") {
                a.thumbUrl
            } else {
                a.thumbUrl ?: a.medUrl ?: a.originalUrl
            }
        }

        var urlStr = pickUrl(asset)
        var success = false

        // Try downloading with existing cached URL first (fast path)
        if (!urlStr.isNullOrBlank()) {
            success = downloadToFile(urlStr, docThumb) && isPlausiblePreview(docThumb)
        }

        // If cached URL failed (e.g. 403/410 expired signature) or was missing, refresh URL from server and retry
        if (!success) {
            try {
                repo.refreshUrlsForMaster(asset.masterId)
                val fresh = dao.getById(asset.id) ?: asset
                urlStr = pickUrl(fresh)
                if (!urlStr.isNullOrBlank()) {
                    if (downloadToFile(urlStr, docThumb) && isPlausiblePreview(docThumb)) {
                        return docThumb
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh thumbnail URL for asset ${asset.id}: ${e.message}")
            }
        } else {
            return docThumb
        }

        return null
    }

    private suspend fun getDriveThumbnailFile(item: DriveItemEntity): File? {
        if (!item.isDownloadable) return null
        val appContext = context?.applicationContext ?: return null
        val cacheDir = appContext.cacheDir ?: return null

        // Fast path: check cached file before taking network / semaphore locks
        val thumbDir = File(cacheDir, "drive_thumbs")
        val targetFile = File(thumbDir, "${item.drivewsid.hashCode()}.jpg")
        if (isPlausiblePreview(targetFile)) {
            return targetFile
        }

        // Slow path: fetch from DriveRepository if missing
        val repo = container?.driveRepository ?: return null
        val fetched = repo.getOrFetchThumbnailFile(item)
        if (fetched != null && isPlausiblePreview(fetched)) {
            return fetched
        }
        return null
    }

    private fun isPlausiblePreview(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        if (file.length() < 512) return false
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(12)
                if (input.read(buf) < 12) return false
                val b = buf.map { it.toInt() and 0xFF }
                val isJpeg = b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF
                val isPng = b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                val isGif = b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46
                val isRiff = b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                val isFtyp = b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70
                val isMoov = b[4] == 0x6D && b[5] == 0x6F && b[6] == 0x6F && b[7] == 0x76
                isJpeg || isPng || isGif || isRiff || isFtyp || isMoov
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadToFile(urlStr: String, destination: File): Boolean {
        val tempFile = File(destination.parentFile, "${destination.name}.tmp")
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode !in 200..299) {
                return false
            }
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.renameTo(destination) || (destination.exists() && destination.length() > 0)
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadToFile failed: ${e.message}")
            tempFile.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "ICloudDocProvider"

        const val ROOT_ID = "icloud_root"
        const val DOC_ID_ROOT = "doc_root"
        const val DOC_ID_PHOTOS = "doc_photos"
        const val DOC_ID_DRIVE = "doc_drive"

        const val PREFIX_PHOTO_YM = "photo_ym_"
        const val PREFIX_PHOTO = "photo_"
        const val PREFIX_DRIVE = "drive_"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        fun getMimeTypeForExtension(ext: String): String? {
            return try {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            } catch (e: Throwable) {
                null
            } ?: when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "json" -> "application/json"
                "zip" -> "application/zip"
                else -> null
            }
        }

        fun resolvePhotoMimeType(asset: PhotoAssetEntity): String {
            if (asset.mediaType == "VIDEO") return "video/mp4"
            val ext = asset.filename.substringAfterLast('.', "").lowercase()
            if (ext.isNotBlank()) {
                val mapped = getMimeTypeForExtension(ext)
                if (mapped != null) return mapped
            }
            return "image/jpeg"
        }

        fun resolveDriveMimeType(item: DriveItemEntity): String {
            val ext = item.extension?.lowercase()?.trimStart('.')
            if (!ext.isNullOrBlank()) {
                val mapped = getMimeTypeForExtension(ext)
                if (mapped != null) return mapped
            }
            return "application/octet-stream"
        }

        fun parseIsoToEpochMillis(isoDate: String?): Long {
            if (isoDate.isNullOrBlank()) return System.currentTimeMillis()
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(isoDate)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}
