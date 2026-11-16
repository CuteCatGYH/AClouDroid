package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * High-level record of a full-library batch download run.
 */
@Entity(tableName = "batch_download_tasks")
data class BatchDownloadTaskEntity(
    @PrimaryKey
    val batchId: String,
    val status: String, // PREPARING, DOWNLOADING, PAUSED, COMPLETED, CANCELLED, FAILED
    val targetRelativePath: String, // e.g. "DCIM/Camera"
    val totalCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Individual asset within a batch download task.
 */
@Entity(
    tableName = "batch_download_items",
    indices = [
        Index("batchId"),
        Index("assetId"),
        Index("status")
    ]
)
data class BatchDownloadItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val batchId: String,
    val assetId: String,
    val masterId: String,
    val filename: String,
    val mediaType: String,
    val sizeBytes: Long,
    val assetDate: Long,
    val status: String, // PENDING, DOWNLOADING, SUCCESS, FAILED, SKIPPED
    val mediaStoreUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

object BatchDownloadStatus {
    const val PREPARING = "PREPARING"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
    const val FAILED = "FAILED"

    const val ITEM_PENDING = "PENDING"
    const val ITEM_DOWNLOADING = "DOWNLOADING"
    const val ITEM_SUCCESS = "SUCCESS"
    const val ITEM_FAILED = "FAILED"
    const val ITEM_SKIPPED = "SKIPPED"
}
