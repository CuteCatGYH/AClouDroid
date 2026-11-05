package app.acloudroid.qwq.feature.photos.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object MediaStoreDownloader {
    private const val TAG = "MediaStoreDownloader"

    suspend fun downloadAsset(
        context: Context,
        photo: PhotoAssetEntity,
        repository: PhotoRepository,
        onProgress: ((progressPercent: Int, downloadedBytes: Long) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val notificationId = photo.id.hashCode()
        app.acloudroid.qwq.feature.photos.work.DownloadNotificationHelper.showProgress(
            context,
            notificationId,
            photo.filename,
            -1,
            0L
        )

        val progressHandler: (Int, Long) -> Unit = { percent, bytes ->
            onProgress?.invoke(percent, bytes)
            app.acloudroid.qwq.feature.photos.work.DownloadNotificationHelper.showProgress(
                context,
                notificationId,
                photo.filename,
                percent,
                bytes
            )
        }

        try {
            var downloadUrl = photo.originalUrl ?: photo.medUrl ?: photo.thumbUrl
            var videoComplUrl = photo.videoComplUrl
            val isExpired = downloadUrl.isNullOrBlank() ||
                photo.sessionGeneration != repository.currentSessionGeneration() ||
                (System.currentTimeMillis() - photo.urlFetchedAt > 45 * 60 * 1000L)

            val isLivePhoto = photo.mediaType == "LIVE_PHOTO" || !videoComplUrl.isNullOrBlank()

            // Issue 1 Fix: For Live Photos, if videoComplUrl is missing or URLs are stale, ALWAYS force-refresh URLs from server!
            if (isLivePhoto && (videoComplUrl.isNullOrBlank() || isExpired)) {
                Log.i(TAG, "Live Photo videoComplUrl missing or stale for master=${photo.masterId}, force refreshing URLs...")
                val refreshed = repository.refreshUrlsForMaster(photo.masterId, force = true)
                if (refreshed.isSuccess) {
                    val urls = refreshed.getOrNull()
                    downloadUrl = urls?.originalUrl ?: urls?.medUrl ?: urls?.thumbUrl ?: downloadUrl
                    if (urls != null && !urls.videoComplUrl.isNullOrBlank()) {
                        videoComplUrl = urls.videoComplUrl
                    }
                }
            } else if (isExpired) {
                Log.i(TAG, "Download URL stale for master=${photo.masterId}, refreshing...")
                val refreshed = repository.refreshUrlsForMaster(photo.masterId)
                if (refreshed.isSuccess) {
                    val urls = refreshed.getOrNull()
                    downloadUrl = urls?.originalUrl ?: urls?.medUrl ?: urls?.thumbUrl ?: downloadUrl
                    if (urls != null && !urls.videoComplUrl.isNullOrBlank()) {
                        videoComplUrl = urls.videoComplUrl
                    }
                }
            }

            if (downloadUrl.isNullOrBlank()) {
                val err = Exception("无法获取有效的照片/视频下载链接")
                app.acloudroid.qwq.feature.photos.work.DownloadNotificationHelper.showFailed(
                    context,
                    notificationId,
                    photo.filename,
                    err.message
                )
                return@withContext Result.failure(err)
            }

            val isVideo = photo.mediaType == "VIDEO" ||
                photo.filename.endsWith(".mp4", ignoreCase = true) ||
                photo.filename.endsWith(".mov", ignoreCase = true)

            // If it is a Live Photo and we have videoComplUrl, save as a single Motion Photo file!
            if (!isVideo && isLivePhoto && !videoComplUrl.isNullOrBlank()) {
                Log.i(TAG, "Downloading Live Photo as single Motion Photo for ${photo.filename}...")
                val motionResult = downloadLivePhotoMotionPhoto(
                    context = context,
                    photoUrl = downloadUrl,
                    videoUrl = videoComplUrl,
                    photo = photo,
                    repository = repository,
                    onProgress = progressHandler
                )
                if (motionResult.isSuccess) {
                    val uri = motionResult.getOrThrow()
                    app.acloudroid.qwq.feature.photos.work.DownloadNotificationHelper.showFinished(
                        context,
                        notificationId,
                        photo.filename,
                        uri
                    )
                    return@withContext motionResult
                } else {
                    Log.w(TAG, "Motion photo packing failed, falling back to static photo download", motionResult.exceptionOrNull())
                }
            }

            val photoMimeType = when {
                isVideo -> if (photo.filename.endsWith(".mov", ignoreCase = true)) "video/quicktime" else "video/mp4"
                photo.filename.endsWith(".heic", ignoreCase = true) -> "image/heic"
                photo.filename.endsWith(".png", ignoreCase = true) -> "image/png"
                else -> "image/jpeg"
            }

            val relativePhotoPath = if (isVideo) {
                Environment.DIRECTORY_MOVIES + "/iClouDroid"
            } else {
                Environment.DIRECTORY_PICTURES + "/iClouDroid"
            }

            val downloadResult = downloadSingleFile(
                context = context,
                url = downloadUrl,
                filename = photo.filename,
                mimeType = photoMimeType,
                relativePath = relativePhotoPath,
                isVideo = isVideo,
                assetDate = photo.assetDate,
                masterId = photo.masterId,
                repository = repository,
                expectedSize = photo.size,
                onProgress = progressHandler
            )

            if (downloadResult.isSuccess) {
                val uri = downloadResult.getOrThrow()
                app.acloudroid.qwq.feature.photos.work.DownloadNotificationHelper.showFinished(
                    context,
                    notificationId,
                    photo.filename,
                    uri
                )
            } else {
                app.acloudroid.qwq.feature.photos.work.DownloadNotificationHelper.showFailed(
                    context,
                    notificationId,
                    photo.filename,
                    downloadResult.exceptionOrNull()?.message
                )
            }

            downloadResult
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download asset", e)
            app.acloudroid.qwq.feature.photos.work.DownloadNotificationHelper.showFailed(
                context,
                notificationId,
                photo.filename,
                e.message
            )
            Result.failure(e)
        }
    }

    private suspend fun downloadLivePhotoMotionPhoto(
        context: Context,
        photoUrl: String,
        videoUrl: String,
        photo: PhotoAssetEntity,
        repository: PhotoRepository,
        onProgress: ((progressPercent: Int, downloadedBytes: Long) -> Unit)? = null
    ): Result<Uri> {
        val photoBytes = fetchUrlBytes(
            initialUrl = photoUrl,
            masterId = photo.masterId,
            repository = repository,
            isVideo = false,
            fallbackSize = if (photo.size > 0) photo.size / 4 else 0L,
            onProgress = { p, bytes ->
                val calcPercent = if (p >= 0) (p * 0.3).toInt().coerceIn(0, 30) else -1
                onProgress?.invoke(calcPercent, bytes)
            }
        ) ?: return Result.failure(Exception("无法获取静态原图数据"))

        val videoBytes = fetchUrlBytes(
            initialUrl = videoUrl,
            masterId = photo.masterId,
            repository = repository,
            isVideo = true,
            fallbackSize = photo.size,
            onProgress = { p, bytes ->
                val calcPercent = if (p >= 0) (30 + p * 0.7).toInt().coerceIn(30, 100) else -1
                onProgress?.invoke(calcPercent, photoBytes.size.toLong() + bytes)
            }
        ) ?: return Result.failure(Exception("无法获取实况视频数据"))

        // Issue 2 & 3 Fix: Read EXIF metadata & orientation from original photoBytes
        val srcExif = try {
            ExifInterface(ByteArrayInputStream(photoBytes))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse EXIF from photoBytes", e)
            null
        }

        val orientation = srcExif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        // Decode Bitmap and physically rotate pixels so image is upright (Orientation = NORMAL)
        var bitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
            ?: return Result.failure(Exception("静态图解码失败"))

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (degrees != 0f) {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
        bitmap.recycle()
        val jpegBytes = baos.toByteArray()

        val baseName = photo.filename.substringBeforeLast('.')
        val motionFilename = "$baseName.jpg"
        val relativePath = Environment.DIRECTORY_PICTURES + "/iClouDroid"

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, motionFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            if (photo.assetDate > 0) {
                put(MediaStore.MediaColumns.DATE_ADDED, photo.assetDate / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, photo.assetDate / 1000)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, contentValues)
            ?: return Result.failure(Exception("创建 Motion Photo MediaStore 记录失败"))

        try {
            resolver.openOutputStream(uri)?.use { out ->
                packJpegMotionPhoto(jpegBytes, videoBytes, out)
            } ?: return Result.failure(Exception("无法打开 Motion Photo 输出流"))
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return Result.failure(e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        // Copy original EXIF attributes (Camera Make, Model, ExposureTime, FNumber, ISO, GPS) to the output file
        if (srcExif != null) {
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val dstExif = ExifInterface(pfd.fileDescriptor)
                    copyExifAttributes(srcExif, dstExif)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy EXIF attributes to Motion Photo file", e)
            }
        }

        Log.i(TAG, "Successfully saved Live Photo as single Motion Photo $motionFilename to $uri")
        return Result.success(uri)
    }

    private fun copyExifAttributes(srcExif: ExifInterface, dstExif: ExifInterface) {
        val exifTags = arrayOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP
        )

        for (tag in exifTags) {
            val value = srcExif.getAttribute(tag)
            if (!value.isNullOrBlank()) {
                dstExif.setAttribute(tag, value)
            }
        }

        // Reset Orientation to NORMAL (1) because we physically rotated the bitmap pixels upright
        dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        try {
            dstExif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save EXIF attributes", e)
        }
    }

    private fun isJpegBytes(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }

    private suspend fun fetchUrlBytes(
        initialUrl: String,
        masterId: String,
        repository: PhotoRepository,
        isVideo: Boolean,
        fallbackSize: Long = 0L,
        onProgress: ((progressPercent: Int, downloadedBytes: Long) -> Unit)? = null
    ): ByteArray? {
        var currentUrl = initialUrl
        var connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 60000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }

        if (connection.responseCode == HttpURLConnection.HTTP_GONE ||
            connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN
        ) {
            connection.disconnect()
            val refreshed = repository.refreshUrlsForMaster(masterId, force = true).getOrNull()
            val newUrl = if (isVideo) {
                refreshed?.originalUrl ?: refreshed?.videoComplUrl
            } else {
                refreshed?.originalUrl ?: refreshed?.medUrl
            }
            if (!newUrl.isNullOrBlank()) {
                currentUrl = newUrl
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
            }
        }

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            return null
        }

        val totalLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connection.contentLengthLong.takeIf { it > 0 } ?: fallbackSize
        } else {
            connection.contentLength.toLong().takeIf { it > 0 } ?: fallbackSize
        }

        return try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var downloadedBytes = 0L
            var lastReportTime = 0L
            var lastReportedPercent = -1

            connection.inputStream.use { inStream ->
                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (onProgress != null) {
                        val percent = if (totalLength > 0) {
                            ((downloadedBytes * 100) / totalLength).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        val now = System.currentTimeMillis()
                        if (percent != lastReportedPercent && (now - lastReportTime >= 250 || percent == 100 || (percent > 0 && percent - lastReportedPercent >= 2))) {
                            lastReportedPercent = percent
                            lastReportTime = now
                            onProgress(percent, downloadedBytes)
                        } else if (percent == -1 && now - lastReportTime >= 250) {
                            lastReportTime = now
                            onProgress(-1, downloadedBytes)
                        }
                    }
                }
            }
            out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun packJpegMotionPhoto(
        jpegBytes: ByteArray,
        videoBytes: ByteArray,
        outStream: OutputStream
    ) {
        val xmpXml = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    GCamera:MotionPhoto="1"
                    GCamera:MotionPhotoVersion="1"
                    GCamera:MotionPhotoPresentationTimestampUs="0"
                    GCamera:MicroVideo="1"
                    GCamera:MicroVideoVersion="1"
                    GCamera:MicroVideoOffset="${videoBytes.size}"
                    GCamera:MicroVideoPresentationTimestampUs="0">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource"
                          Item:Mime="image/jpeg"
                          Item:Semantic="Primary"
                          Item:Length="0"/>
                      <rdf:li rdf:parseType="Resource"
                          Item:Mime="video/mp4"
                          Item:Semantic="MotionPhoto"
                          Item:Length="${videoBytes.size}"/>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val xmpHeaderBytes = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
        val xmlBytes = xmpXml.toByteArray(Charsets.UTF_8)
        val payloadSize = xmpHeaderBytes.size + xmlBytes.size
        val segmentLength = payloadSize + 2

        if (!isJpegBytes(jpegBytes)) {
            outStream.write(jpegBytes)
            outStream.write(videoBytes)
            return
        }

        var insertPos = 2
        var pos = 2
        while (pos + 4 < jpegBytes.size) {
            if (jpegBytes[pos] != 0xFF.toByte()) break
            val marker = jpegBytes[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val len = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
            if (marker == 0xE0) {
                insertPos = pos + 2 + len
            }
            pos += 2 + len
        }

        outStream.write(jpegBytes, 0, insertPos)
        outStream.write(0xFF)
        outStream.write(0xE1)
        outStream.write((segmentLength shr 8) and 0xFF)
        outStream.write(segmentLength and 0xFF)
        outStream.write(xmpHeaderBytes)
        outStream.write(xmlBytes)
        outStream.write(jpegBytes, insertPos, jpegBytes.size - insertPos)
        outStream.write(videoBytes)
    }

    private suspend fun downloadSingleFile(
        context: Context,
        url: String,
        filename: String,
        mimeType: String,
        relativePath: String,
        isVideo: Boolean,
        assetDate: Long,
        masterId: String,
        repository: PhotoRepository,
        expectedSize: Long = 0L,
        onProgress: ((progressPercent: Int, downloadedBytes: Long) -> Unit)? = null
    ): Result<Uri> {
        var currentUrl = url
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            if (assetDate > 0) {
                put(MediaStore.MediaColumns.DATE_ADDED, assetDate / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, assetDate / 1000)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, contentValues)
            ?: return Result.failure(Exception("创建 MediaStore 记录失败 ($filename)"))

        var connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 60000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }

        if (connection.responseCode == HttpURLConnection.HTTP_GONE ||
            connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN
        ) {
            Log.w(TAG, "URL returned ${connection.responseCode}, refreshing URL for master=$masterId...")
            connection.disconnect()
            val refreshed = repository.refreshUrlsForMaster(masterId, force = true).getOrNull()
            val newUrl = if (isVideo) {
                refreshed?.originalUrl ?: refreshed?.videoComplUrl
            } else {
                refreshed?.originalUrl ?: refreshed?.medUrl ?: refreshed?.thumbUrl
            }
            if (!newUrl.isNullOrBlank()) {
                currentUrl = newUrl
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
            }
        }

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            resolver.delete(uri, null, null)
            return Result.failure(Exception("HTTP ${connection.responseCode} 下载 $filename 失败"))
        }

        var inStream: InputStream? = null
        var outStream: OutputStream? = null
        try {
            inStream = connection.inputStream
            outStream = resolver.openOutputStream(uri)
                ?: throw Exception("无法打开 MediaStore 输出流 ($filename)")

            val totalLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            } else {
                connection.contentLength.toLong().takeIf { it > 0 } ?: expectedSize
            }
            var downloadedBytes = 0L
            var lastReportTime = 0L
            var lastReportedPercent = -1

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (inStream.read(buffer).also { bytesRead = it } != -1) {
                outStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                if (onProgress != null) {
                    val percent = if (totalLength > 0) {
                        ((downloadedBytes * 100) / totalLength).toInt().coerceIn(0, 100)
                    } else {
                        -1
                    }
                    val now = System.currentTimeMillis()
                    if (percent != lastReportedPercent && (now - lastReportTime >= 250 || percent == 100 || (percent > 0 && percent - lastReportedPercent >= 2))) {
                        lastReportedPercent = percent
                        lastReportTime = now
                        onProgress(percent, downloadedBytes)
                    } else if (percent == -1 && now - lastReportTime >= 250) {
                        lastReportTime = now
                        onProgress(-1, downloadedBytes)
                    }
                }
            }
            outStream.flush()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            inStream?.close()
            outStream?.close()
            connection.disconnect()
        }

        // Translate Apple's QuickTime metadata into Android-readable `udta` atoms.
        //
        // An iPhone clip round-trips byte-identically (verified against `resOriginalFileSize`), so
        // nothing was ever lost - it is just in a form Android does not read. Apple keeps capture
        // info in `moov/meta` (`hdlr`+`keys`+`ilst`); Samsung Gallery and MediaStore look at
        // `udta` atoms instead, which is why a perfectly intact clip shows no camera and no
        // location after download.
        //
        // Done while `IS_PENDING` is still 1: the file is on disk but hidden from other apps, so
        // the first reader to see it already sees the patched bytes.
        //
        // Failure is logged and swallowed. A missing `auth` atom shows an empty camera field,
        // whereas aborting the download would lose the user's video.
        if (isVideo) {
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val report = com.getup.icloudcore.Icloudcore
                        .injectAndroidVideoMetadataFD(pfd.detachFd().toLong())
                    Log.i(TAG, "android metadata for $filename: $report")
                }
            } catch (e: Exception) {
                Log.w(TAG, "android metadata injection failed for $filename", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        Log.i(TAG, "Successfully saved $filename to $uri")
        return Result.success(uri)
    }
}
