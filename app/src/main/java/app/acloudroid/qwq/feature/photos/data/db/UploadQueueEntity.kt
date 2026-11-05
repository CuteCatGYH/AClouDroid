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
