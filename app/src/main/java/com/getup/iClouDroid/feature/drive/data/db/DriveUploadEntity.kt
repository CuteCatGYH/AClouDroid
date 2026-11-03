package com.getup.iClouDroid.feature.drive.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Upload task status constants.
 */
object DriveUploadStatus {
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "drive_upload_queue",
    indices = [
        Index("status"),
        Index("createdAt")
    ]
)
data class DriveUploadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val localPath: String,
    val targetFolderDrivewsId: String,
    val targetFolderPath: String,
    val filename: String,
    val filesize: Long,
    val mimeType: String,
    val status: String = DriveUploadStatus.QUEUED,
    val bytesUploaded: Long = 0,
    val speedText: String? = null,
    val errorMessage: String? = null,
    val documentId: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
