package com.getup.iClouDroid.feature.drive.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity
import com.getup.icloudcore.Icloudcore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where a Drive download ended up, for the "已保存到…" message. */
data class DriveDownloadOutcome(
    /** The `content://` URI of the finished file, or null in tests / legacy Android. */
    val uri: Uri?,
    /** Human-readable location, e.g. `Download/iClouDroid`. */
    val displayLocation: String,
    val bytes: Long
)

/**
 * Downloads one Drive file to the device.
 *
 * ## Why `MediaStore.Downloads` and not `MediaStore.Images`
 *
 * [com.getup.iClouDroid.feature.photos.data.MediaStoreDownloader] uses the image/video
 * collections, which is right for photos. A Drive folder holds arbitrary files (`.pdf`, `.zip`,
 * `.pages`), and the image/video collections reject or misclassify most of them.
 * `MediaStore.Downloads` exists precisely for "a file the user saved", and on API 29+ it needs no
 * storage permission at all because the app owns the rows it inserts.
 *
 * ## Why the bytes go through a temp file
 *
 * [Icloudcore.downloadToFile] streams into a path; it never hands back a byte array. That is the
 * whole point (PITFALLS §4): these files reach 300 MB+, and a `ByteArray` would be allocated twice
 * — once in Go, once in the JVM — for every download.
 *
 * The flow is therefore: Go writes to `cacheDir`, then this class copies that file into the
 * MediaStore stream. One extra disk pass, in exchange for a bounded heap.
 */
class DriveDownloader(
    private val context: Context
) {

    /**
     * Downloads [item] and publishes it into the Downloads collection.
     *
     * @param onProgress called with 0..100. Percent is derived from the total the server reports;
     *   when the size is unknown it stays at -1 rather than pretending to advance.
     */
    suspend fun download(
        item: DriveItemEntity,
        onProgress: ((Int) -> Unit)? = null
    ): Result<DriveDownloadOutcome> = withContext(Dispatchers.IO) {
        if (item.type != DriveItemEntity.TYPE_FILE) {
            return@withContext Result.failure(Exception("只有文件可以下载"))
        }

        val notificationId = item.drivewsid.hashCode()
        val fileName = sanitizeFileName(item.name, item.extension)
        val mimeType = mimeTypeFor(item.extension)

        val tempDir = File(context.cacheDir, "drive_downloads").apply { mkdirs() }
        val tempFile = File(tempDir, "${item.docwsid}.part")

        // 1. Calculate resumption offset if a partial file exists from an interrupted download
        var resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
        if (item.size > 0 && resumeFrom >= item.size) {
            tempFile.delete()
            resumeFrom = 0L
        }

        val initialPercent = if (item.size > 0 && resumeFrom > 0) {
            ((resumeFrom * 100) / item.size).toInt().coerceIn(0, 99)
        } else 0

        DriveDownloadNotificationHelper.showProgress(
            context = context,
            notificationId = notificationId,
            filename = fileName,
            progressPercent = initialPercent,
            downloadedBytes = resumeFrom,
            drivewsid = item.drivewsid
        )

        var publishSuccess = false

        try {
            // 2. Mint a signed URL.
            val tokenJson = Icloudcore.retrieveDownloadURL(item.drivewsid)
            val token = parseToken(tokenJson)
                ?: throw Exception(formatDownloadError(tokenErrorMessage(tokenJson)))

            // 3. Stream into temp file with live progress ticker
            // Cancellation MUST reach Go synchronously.
            //
            // `invokeOnCompletion` fires only once the job is *completed*, i.e. after the blocking
            // JNI call has returned — which is precisely when cancellation is no longer needed.
            // A previous version relied on it and the cancel button was pure theatre: the byte
            // stream kept running and the full file landed in Downloads. The JNI call below is a
            // native blocking `io.Copy`, so the only thing that can stop it is an explicit call
            // into Go, made from the cancel path itself (see DriveDownloadQueue.cancel and
            // DriveDownloadReceiver), or from the periodic ticker below as a safety net.
            val tickerJob = launch {
                while (isActive) {
                    delay(PROGRESS_POLL_MS)
                    val currentBytes = tempFile.length()
                    val percent = if (item.size > 0) {
                        ((currentBytes * 100) / item.size).toInt().coerceIn(0, 99)
                    } else -1
                    onProgress?.invoke(percent)
                    DriveDownloadNotificationHelper.showProgress(
                        context = context,
                        notificationId = notificationId,
                        filename = fileName,
                        progressPercent = percent,
                        downloadedBytes = currentBytes,
                        drivewsid = item.drivewsid
                    )
                }
            }

            // Safety net: if this coroutine is cancelled by any path that did not already signal
            // Go (e.g. the ViewModel scope being torn down), the ticker notices and pushes the
            // cancellation through before it dies. `finally` runs on cancellation, so this cannot
            // be skipped.
            var resultJson: String
            try {
                resultJson = Icloudcore.downloadToFileWithID(
                    token.url,
                    tempFile.absolutePath,
                    resumeFrom,
                    item.docwsid
                )
            } finally {
                // Only poke Go when we really are being cancelled; on the normal path this is a
                // no-op lookup in Go's map.
                coroutineContext[Job]?.let { if (!it.isActive) signalGoCancel(item) }
                tickerJob.cancel()
            }

            var download = parseDownloadResult(resultJson)

            // If the server ignored Range on a resumed request, clear tempFile and retry from 0.
            if (!download.success && resumeFrom > 0 &&
                download.errorMessage?.contains("server ignored Range") == true
            ) {
                Log.w(TAG, "Server ignored Range for ${item.name}, restarting download from 0")
                tempFile.delete()
                resumeFrom = 0L
                resultJson = Icloudcore.downloadToFileWithID(token.url, tempFile.absolutePath, 0L, item.docwsid)
                download = parseDownloadResult(resultJson)
            }

            if (!download.success) {
                val formattedMsg = formatDownloadError(download.errorMessage)
                throw Exception(formattedMsg)
            }

            onProgress?.invoke(100)

            // 4. Publish to MediaStore Downloads
            val outcome = publish(tempFile, fileName, item.extension, download.written)
            publishSuccess = true

            val resolvedMime = outcome.uri?.let { context.contentResolver.getType(it) } ?: mimeType
            DriveDownloadNotificationHelper.showFinished(
                context = context,
                notificationId = notificationId,
                filename = fileName,
                contentUri = outcome.uri,
                mimeType = resolvedMime
            )

            Result.success(outcome)
        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.i(TAG, "Download cancelled for ${item.name}; keeping .part file (${tempFile.length()} B)")
                DriveDownloadNotificationHelper.cancel(context, notificationId)
                throw e
            }
            Log.e(TAG, "Download failed for ${item.name}", e)
            val displayErr = e.message ?: "下载失败"
            DriveDownloadNotificationHelper.showFailed(
                context = context,
                notificationId = notificationId,
                filename = fileName,
                errorMsg = displayErr
            )
            Result.failure(e)
        } finally {
            if (publishSuccess) {
                tempFile.delete()
            }
        }
    }

    /**
     * Writes [source] into the shared Downloads collection.
     *
     * `IS_PENDING` keeps the row invisible to other apps until the copy finishes, so a gallery or
     * file manager never lists a half-written file. It **must** be cleared on the failure path too,
     * otherwise the row lingers as a permanently hidden zero-byte entry that the user cannot see
     * and therefore cannot delete.
     */
    private fun publish(
        source: File,
        displayName: String,
        extension: String?,
        expectedBytes: Long
    ): DriveDownloadOutcome {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/iClouDroid"
        val mime = mimeTypeFor(extension)

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            // Pre-Q has no Downloads collection; fall back to a plain public directory.
            return publishLegacy(source, relativePath, displayName)
        }

        val resolver = context.contentResolver

        // A download is one-way and must never destroy something already on the device.
        //
        // An earlier version deleted any same-named file *before* inserting, which meant a failed
        // transfer destroyed the user's existing copy and delivered nothing in its place. It also
        // silently overwrote local edits. Both problems disappear once a collision produces a new
        // file instead of replacing an old one.
        //
        // The suffix is assigned here rather than left to MediaStore: the platform's own
        // de-duplication is not part of the API contract, and different providers (and OEM
        // builds) number collisions differently. Doing it explicitly means `report.pdf`,
        // `report (1).pdf`, `report (2).pdf` on every device.
        val finalName = uniqueDisplayName(resolver, collection, relativePath, displayName)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw Exception("无法在下载目录创建文件")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: throw Exception("无法打开输出流")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Remove the half-written row rather than leaving an invisible zero-byte entry.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        val written = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: expectedBytes

        return DriveDownloadOutcome(
            uri = uri,
            displayLocation = "下载/iClouDroid",
            bytes = if (written > 0) written else expectedBytes
        )
    }

    /**
     * Returns `displayName`, or `name (1).ext` / `name (2).ext` if that name is taken.
     *
     * The suffix goes **before** the extension (`report (1).pdf`, not `report.pdf (1)`) so the
     * file stays openable by the app registered for that type.
     *
     * If the existence check itself fails the original name is returned unchanged — losing the
     * suffix is cosmetic, whereas refusing the download over it would not be.
     */
    internal fun uniqueDisplayName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        displayName: String
    ): String {
        if (!existsInFolder(resolver, collection, relativePath, displayName)) return displayName

        val (stem, suffix) = splitExtension(displayName)
        // Bounded: after this many collisions the user almost certainly wants a new name rather
        // than a longer scan, and an unbounded loop here would be a trivial way to hang.
        for (n in 1..MAX_DUPLICATE_SUFFIX) {
            val candidate = if (suffix.isEmpty()) "$stem ($n)" else "$stem ($n).$suffix"
            if (!existsInFolder(resolver, collection, relativePath, candidate)) return candidate
        }
        return if (suffix.isEmpty()) {
            "$stem (${System.currentTimeMillis()})"
        } else {
            "$stem (${System.currentTimeMillis()}).$suffix"
        }
    }

    private fun existsInFolder(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        displayName: String
    ): Boolean = try {
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf(displayName, "$relativePath/"),
            null
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        // An unreadable collection means "assume free": a duplicate suffix is cosmetic, whereas
        // failing the download because the check failed is not.
        Log.d(TAG, "existsInFolder check failed: ${e.message}")
        false
    }

    /** Pre-API-29 path: no MediaStore.Downloads, so write straight into public storage. */
    private fun publishLegacy(source: File, relativePath: String, displayName: String): DriveDownloadOutcome {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "iClouDroid")
        if (!dir.exists() && !dir.mkdirs()) throw Exception("无法创建下载目录")

        // Same never-overwrite rule as the MediaStore path: a second download of the same name
        // must not clobber a file the user may have edited.
        var dest = File(dir, displayName)
        if (dest.exists()) {
            val (stem, suffix) = splitExtension(displayName)
            for (n in 1..MAX_DUPLICATE_SUFFIX) {
                val candidate = if (suffix.isEmpty()) "$stem ($n)" else "$stem ($n).$suffix"
                val file = File(dir, candidate)
                if (!file.exists()) {
                    dest = file
                    break
                }
            }
        }

        source.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }

        return DriveDownloadOutcome(
            uri = Uri.fromFile(dest),
            displayLocation = "下载/iClouDroid",
            bytes = dest.length()
        )
    }

    // ----------------------------------------------------------------------------------------
    // JSON parsing
    // ----------------------------------------------------------------------------------------

    private data class Token(val url: String)

    private fun parseToken(json: String): Token? = runCatching {
        val obj = JSONObject(json)
        if (!obj.optBoolean("success", false)) return null
        val url = obj.optJSONObject("token")?.optString("url").orEmpty()
        if (url.isBlank()) null else Token(url)
    }.getOrNull()

    private fun tokenErrorMessage(json: String): String? = runCatching {
        val obj = JSONObject(json)
        if (obj.optBoolean("success", false)) null
        else obj.optString("errorMessage").takeIf { it.isNotBlank() }
    }.getOrNull()

    private data class DownloadResult(
        val success: Boolean,
        val written: Long,
        val errorMessage: String?
    )

    private fun parseDownloadResult(json: String): DownloadResult = runCatching {
        val obj = JSONObject(json)
        DownloadResult(
            success = obj.optBoolean("success", false),
            written = obj.optLong("written", 0L),
            errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() }
        )
    }.getOrElse { DownloadResult(false, 0L, "下载响应无法解析") }

    // `internal` rather than `private`: the naming rules below are the one part of this class
    // that is pure and unit-testable, and they guard a path-traversal boundary. Leaving them
    // unreachable from a test would mean the only way to check them is on a device.
    internal companion object {
        const val TAG = "DriveDownloader"

        /** How often the progress ticker samples the partial file. */
        private const val PROGRESS_POLL_MS = 250L

        /**
         * Pushes a cancellation into the Go core for every id the download might be registered
         * under.
         *
         * The JNI call `downloadToFileWithID` blocks in native `io.Copy` and cannot observe a
         * Kotlin coroutine cancellation, so this is the only way to actually stop the byte
         * stream. Calling it for a download that already finished is harmless: Go reports false
         * and the ids are not reused.
         */
        internal fun signalGoCancel(item: DriveItemEntity) {
            runCatching { Icloudcore.cancelDownload(item.docwsid) }
            runCatching { Icloudcore.cancelDownload(item.drivewsid) }
        }

        /** Maps technical error strings from Go / HTTP response to user-friendly messages. */
        fun formatDownloadError(rawMsg: String?): String {
            if (rawMsg.isNullOrBlank()) return "下载失败"
            return when {
                rawMsg.contains("no data_token.url") -> "该文件类型（如 iWork 包）暂不支持直接下载"
                rawMsg.contains("No active session") || rawMsg.contains("AUTH_EXPIRED") || rawMsg.contains("401") -> "iCloud 登录凭证已过期，请重新登录"
                rawMsg.contains("NOT_FOUND") || rawMsg.contains("404") -> "文件在云端不存在或已被删除"
                rawMsg.contains("503") || rawMsg.contains("Bad Gateway") -> "云端服务暂不可用，请稍后重试"
                else -> rawMsg
            }
        }

        /** How many `(n)` suffixes to try before falling back to a timestamp. */
        const val MAX_DUPLICATE_SUFFIX = 99

        /** `report.tar.gz` -> (`report.tar`, `gz`); `README` -> (`README`, ``). */
        internal fun splitExtension(name: String): Pair<String, String> {
            val dot = name.lastIndexOf('.')
            // A leading dot is a hidden file (`.localized`), not an extension separator.
            // A trailing dot is a malformed name, not a file with an empty extension — keeping
            // it would yield `report. (1)`, which is not something any OS produces.
            if (dot <= 0 || dot == name.lastIndex) return name.trimEnd('.') to ""
            return name.substring(0, dot) to name.substring(dot + 1)
        }

        /**
         * Replaces characters that are illegal in a filename or that would let a crafted name
         * escape the target directory. Drive names come from another device, so this is not
         * paranoia — a name containing `/` would otherwise create a nested path.
         */
        fun sanitizeFileName(name: String, extension: String?): String {
            val base = name.ifBlank { "未命名" }
            val cleaned = base
                .replace(Regex("""[/\\:*?"<>|\u0000-\u001F]"""), "_")
                .trim()
                .trimEnd('.')
                .take(180)
                .ifBlank { "未命名" }

            // The listing's `name` usually already includes the extension, but not always.
            // Appending blindly would produce `report.pdf.pdf`.
            val ext = extension?.takeIf { it.isNotBlank() }
            return if (ext != null && !cleaned.endsWith(".$ext", ignoreCase = true)) {
                "$cleaned.$ext"
            } else {
                cleaned
            }
        }

        /**
         * Best-effort MIME type from the extension.
         *
         * `application/octet-stream` is the correct fallback: MediaStore uses the type to decide
         * how a file is opened, and guessing something specific would make the system offer a
         * worthless "open with" list.
         */
        fun mimeTypeFor(extension: String?): String = when (extension?.lowercase()) {
            "txt", "log", "md" -> "text/plain"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "gif" -> "image/gif"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            else -> "application/octet-stream"
        }
    }
}
