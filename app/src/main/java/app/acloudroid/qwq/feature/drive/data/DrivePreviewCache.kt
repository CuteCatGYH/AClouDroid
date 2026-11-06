package app.acloudroid.qwq.feature.drive.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the sandbox temporary preview cache directory (`cache/drive_preview/`).
 *
 * Preview files fetched for in-app viewing or FileProvider external Intent opening
 * are stored here temporarily, isolated from user's permanent Downloads folder.
 */
class DrivePreviewCache(private val context: Context) {

    private val previewDir: File
        get() = File(context.cacheDir, PREVIEW_DIR_NAME).also {
            if (!it.exists()) it.mkdirs()
        }

    /**
     * Returns a target file for [drivewsid] and [extension] inside the preview cache directory.
     */
    fun getPreviewFile(drivewsid: String, extension: String): File {
        val safeId = sanitize(drivewsid)
        val safeExt = sanitizeExtension(extension)
        val fileName = if (safeExt.isBlank()) safeId else "$safeId.$safeExt"
        return File(previewDir, fileName)
    }

    /**
     * Checks if a preview file for [drivewsid] already exists and is non-empty.
     */
    fun findPreviewFile(drivewsid: String): File? {
        val safeId = sanitize(drivewsid)
        val files = previewDir.listFiles() ?: return null
        return files.firstOrNull { file ->
            (file.name == safeId || file.name.startsWith("$safeId.")) && file.length() > 0
        }
    }

    /**
     * Evicts preview files older than [maxAgeDays] or when the cache directory size exceeds [maxSizeBytes].
     */
    fun cleanOldPreviews(maxSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_BYTES, maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS) {
        val dir = previewDir
        val files = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        val maxAgeMs = maxAgeDays * 86_400_000L

        // 1. Remove files exceeding max age
        for (file in files) {
            if (now - file.lastModified() > maxAgeMs) {
                file.delete()
            }
        }

        // 2. If total size still exceeds maxSizeBytes, delete LRU files
        val remainingFiles = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var currentSize = remainingFiles.sumOf { it.length() }

        for (file in remainingFiles) {
            if (currentSize <= maxSizeBytes) break
            val len = file.length()
            if (file.delete()) {
                currentSize -= len
                Log.d(TAG, "Evicted preview file ${file.name} to enforce cache size limit")
            }
        }
    }

    /**
     * Clears all preview cache files.
     */
    fun clearAllPreviews() {
        previewDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "DrivePreviewCache"
        private const val PREVIEW_DIR_NAME = "drive_preview"
        private const val DEFAULT_MAX_CACHE_SIZE_BYTES = 200 * 1024 * 1024L // 200 MB
        private const val DEFAULT_MAX_AGE_DAYS = 3

        internal fun sanitize(input: String): String {
            return input.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        }

        internal fun sanitizeExtension(ext: String): String {
            val trimmed = ext.trim().lowercase().removePrefix(".")
            return sanitize(trimmed)
        }
    }
}
