package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable record of "these exact bytes are already in the cloud".
 *
 * ## Why this table exists
 *
 * The upload queue is a *view*: the user can clear completed rows, or clear everything, from the
 * backup queue sheet. Before this table, "I already uploaded this file" lived **only** on those
 * rows, so clearing the queue erased the fact itself. The next scan then saw every local file as
 * brand new, re-enqueued it, and the worker streamed it a second time. Nothing was duplicated in
 * the cloud -- Go's `masterRecordExists()` guard caught it at the very end -- but by then the
 * original bytes had already been uploaded to CVWS and the traffic was real.
 *
 * So the queue stays a disposable UI list and the truth moves here, where no UI path can delete a
 * single row. Only [app.acloudroid.qwq.feature.photos.data.PhotoRepository.wipeAllData] clears it,
 * because [masterId] belongs to a specific Apple ID and is meaningless (or actively harmful) after
 * an account switch -- see AGENTS.md §5.5.
 *
 * ## The key
 *
 * [contentKey] is `"<displayName>|<size>|<dateModified>"`, built by
 * [app.acloudroid.qwq.feature.local.data.LocalAlbumRepository.buildFingerprintKey] and by nothing
 * else. Both the scan path and the upload path must call that one helper: if the two formats ever
 * drift apart, lookups silently never match and the table becomes dead weight. (Same failure mode
 * as the Coil cache keys in AGENTS.md §6.3.)
 *
 * Deliberately *not* a content hash. Hashing every file at scan time would mean reading every
 * photo and video off disk on every scan -- the exact cost this table exists to avoid. Name + size
 * + mtime is already queried by `queryBucketMedia`, so the check is free.
 *
 * Its blind spot is a reinstall or a new device, where this table starts empty. That is covered by
 * Go's `masterRecordExists()` fallback: one round trip and no byte upload for a case that should
 * only ever happen once per file.
 */
@Entity(
    tableName = "uploaded_fingerprints",
    indices = [
        Index(value = ["masterId"]),
        Index(value = ["localSha256"])
    ]
)
data class UploadedFingerprintEntity(
    /** `"<displayName>|<size>|<dateModified>"` -- see [app.acloudroid.qwq.feature.local.data.LocalAlbumRepository.buildFingerprintKey]. */
    @PrimaryKey
    val contentKey: String,

    /**
     * CloudKit `CPLMaster.recordName` (= MMCS `fileChecksum`) of the uploaded asset.
     *
     * Empty while the upload is still in flight: the row is written *before* the upload starts so
     * that a concurrent scan cannot enqueue the same file twice, and backfilled by
     * [UploadedFingerprintDao.markConfirmed] on success. An empty value therefore means
     * "attempted but never confirmed", which is exactly the case that must be retried.
     */
    val masterId: String,

    /**
     * Client-side SHA-256 (base64) of the original file, as computed by the Go core for the
     * `/assets/upload` signature. Null until confirmed.
     *
     * Only ever diagnostic today -- the dedupe key is [contentKey]. Kept because it is the one
     * value that survives a rename, so a future "same bytes, different name" pass can use it
     * without a re-upload.
     */
    val localSha256: String?,

    val sizeBytes: Long,

    val uploadedAt: Long
)
