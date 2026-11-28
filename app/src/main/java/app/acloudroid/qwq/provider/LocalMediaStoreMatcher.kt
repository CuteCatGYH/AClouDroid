package app.acloudroid.qwq.provider

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlin.math.abs

/**
 * Matches cloud [PhotoAssetEntity] rows against items already present in the device's local
 * [MediaStore], producing `MEDIA_STORE_URI` values so Android's Photo Picker collapses local and
 * cloud copies of the same backed-up/downloaded photo into a single tile.
 */
internal class LocalMediaStoreIndex(entries: List<Entry>) {

    data class Entry(
        val id: Long,
        val displayName: String,
        val dateTakenMs: Long,
        val dateModifiedSec: Long,
        val size: Long,
        val isVideo: Boolean
    ) {
        val effectiveDateMs: Long
            get() = if (dateTakenMs > 0L) dateTakenMs else dateModifiedSec * 1000L
    }

    private data class BucketKey(val isVideo: Boolean, val name: String)

    private val byExactName: Map<BucketKey, List<Entry>> =
        entries.filter { it.displayName.isNotBlank() }
            .groupBy { BucketKey(it.isVideo, it.displayName.lowercase()) }

    private val byBaseName: Map<BucketKey, List<Entry>> =
        entries.filter { it.displayName.isNotBlank() }
            .groupBy { BucketKey(it.isVideo, baseNameLower(it.displayName)) }

    /**
     * Returns the canonical local `MediaStore` URI string (`content://media/external/.../<id>`)
     * for an asset if a matching local item exists on this device, or `null` for cloud-only rows.
     */
    fun findMatchingUriString(
        isVideo: Boolean,
        filename: String,
        assetDateMs: Long,
        sizeBytes: Long
    ): String? {
        val id = findMatchingId(isVideo, filename, assetDateMs, sizeBytes) ?: return null
        val baseUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(baseUri, id).toString()
    }

    internal fun findMatchingId(
        isVideo: Boolean,
        filename: String,
        assetDateMs: Long,
        sizeBytes: Long
    ): Long? {
        val lower = filename.lowercase()
        if (lower.isBlank()) return null

        val exactCandidates = byExactName[BucketKey(isVideo, lower)].orEmpty()
        val exactMatch = pickBestCandidate(
            candidates = exactCandidates,
            assetDateMs = assetDateMs,
            sizeBytes = sizeBytes,
            allowSizeMatchWithWideDate = true
        )
        if (exactMatch != null) return exactMatch.id

        // Handle transcodes where the same asset keeps its base name with a different extension
        // (e.g. an iCloud Live Photo `IMG_2199.HEIC` downloaded locally as `IMG_2199.jpg`).
        val base = baseNameLower(lower)
        if (base.isBlank() || assetDateMs <= 0L) return null
        val baseCandidates = byBaseName[BucketKey(isVideo, base)].orEmpty()
        return pickBestCandidate(
            candidates = baseCandidates,
            assetDateMs = assetDateMs,
            sizeBytes = sizeBytes,
            allowSizeMatchWithWideDate = false
        )?.id
    }

    private fun pickBestCandidate(
        candidates: List<Entry>,
        assetDateMs: Long,
        sizeBytes: Long,
        allowSizeMatchWithWideDate: Boolean
    ): Entry? {
        if (candidates.isEmpty()) return null
        val matching = candidates.filter { entry ->
            val dateDiff = if (assetDateMs > 0L && entry.effectiveDateMs > 0L) {
                abs(entry.effectiveDateMs - assetDateMs)
            } else {
                Long.MAX_VALUE
            }
            val sizeExact = sizeBytes > 0L && entry.size == sizeBytes
            // CloudKit stores second-precision timestamps while MediaStore keeps milliseconds,
            // and Samsung Motion Photos carry a larger combined container size locally than in
            // iCloud's still-image asset record — so filename + ±2.5s window catches both.
            dateDiff <= CLOSE_DATE_WINDOW_MS ||
                (allowSizeMatchWithWideDate && sizeExact && (assetDateMs <= 0L || dateDiff <= WIDE_DATE_WINDOW_MS))
        }
        if (matching.isEmpty()) return null
        return matching.minWithOrNull(
            compareBy<Entry>(
                { !(sizeBytes > 0L && it.size == sizeBytes) },
                { if (assetDateMs > 0L && it.effectiveDateMs > 0L) abs(it.effectiveDateMs - assetDateMs) else Long.MAX_VALUE },
                { it.id }
            )
        )
    }

    companion object {
        private const val TAG = "LocalMediaStoreIndex"
        private const val CLOSE_DATE_WINDOW_MS = 2_500L
        private const val WIDE_DATE_WINDOW_MS = 60_000L

        internal fun baseNameLower(filename: String): String {
            val lower = filename.lowercase()
            val dot = lower.lastIndexOf('.')
            return if (dot > 0) lower.substring(0, dot) else lower
        }

        fun load(resolver: ContentResolver): LocalMediaStoreIndex {
            val entries = ArrayList<Entry>()
            queryCollection(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, out = entries)
            queryCollection(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, out = entries)
            return LocalMediaStoreIndex(entries)
        }

        private fun queryCollection(
            resolver: ContentResolver,
            uri: Uri,
            isVideo: Boolean,
            out: MutableList<Entry>
        ) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE
            )
            try {
                resolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: continue
                        out.add(
                            Entry(
                                id = cursor.getLong(idCol),
                                displayName = name,
                                dateTakenMs = if (cursor.isNull(takenCol)) 0L else cursor.getLong(takenCol),
                                dateModifiedSec = if (cursor.isNull(modCol)) 0L else cursor.getLong(modCol),
                                size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol),
                                isVideo = isVideo
                            )
                        )
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing media permission querying $uri", e)
            } catch (e: Exception) {
                Log.w(TAG, "Failed querying local MediaStore $uri", e)
            }
        }
    }
}
