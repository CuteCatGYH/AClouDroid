package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_queue",
    indices = [
        Index(value = ["mediaStoreUri"]),
        Index(value = ["localSha256"]),
        Index(value = ["status"])
    ]
)
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mediaStoreUri: String,
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    /**
     * MediaStore `DATE_MODIFIED` (seconds), carried purely so the upload path can rebuild the same
     * [UploadedFingerprintEntity.contentKey] the scan path computed.
     *
     * Re-querying MediaStore at upload time would work too, but a row inserted by an older version
     * has no guarantee the file still exists by the time it uploads -- and the fingerprint would
     * then be keyed on something the scan never saw.
     */
    val dateModified: Long = 0L,
    val localSha256: String,
    val status: String, // "PENDING", "UPLOADING", "SUCCESS", "FAILED"
    val retryCount: Int = 0,
    val lastError: String? = null,
    val isMotionPhoto: Boolean = false,
    val videoOffset: Long = 0L,
    val videoSize: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long
)
