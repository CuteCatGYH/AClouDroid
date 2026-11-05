package com.getup.iClouDroid.feature.drive.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveUploadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(upload: DriveUploadEntity): Long

    @Query("SELECT * FROM drive_upload_queue WHERE id = :id")
    suspend fun getById(id: Long): DriveUploadEntity?

    @Query("SELECT * FROM drive_upload_queue WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: String): List<DriveUploadEntity>

    @Query("SELECT * FROM drive_upload_queue WHERE status IN ('QUEUED', 'UPLOADING') ORDER BY createdAt ASC")
    suspend fun getPendingUploads(): List<DriveUploadEntity>

    @Query("SELECT * FROM drive_upload_queue ORDER BY createdAt DESC")
    fun observeAllUploads(): Flow<List<DriveUploadEntity>>

    @Query("SELECT * FROM drive_upload_queue WHERE status IN ('QUEUED', 'UPLOADING') ORDER BY createdAt ASC")
    fun observeActiveUploads(): Flow<List<DriveUploadEntity>>

    /**
     * Active tasks PLUS recently failed ones.
     *
     * The banner used to observe [observeActiveUploads] alone, so the instant a task moved to
     * FAILED it vanished from the UI with no message — a 1.2 GB upload rejected by the CDN in 1.7 s
     * looked exactly like "nothing happened", and the user reasonably concluded the app never
     * uploaded anything. Failures have to stay visible until dismissed.
     *
     * [failedSince] bounds the failure window so old failures do not accumulate forever.
     */
    @Query(
        "SELECT * FROM drive_upload_queue " +
            "WHERE status IN ('QUEUED', 'UPLOADING') " +
            "OR (status = 'FAILED' AND updatedAt >= :failedSince) " +
            "ORDER BY createdAt ASC"
    )
    fun observeVisibleUploads(failedSince: Long): Flow<List<DriveUploadEntity>>

    @Query("UPDATE drive_upload_queue SET status = 'QUEUED' WHERE status = 'UPLOADING'")
    suspend fun resetUploadingToQueued()

    @Query("UPDATE drive_upload_queue SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, errorMessage: String? = null, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE drive_upload_queue SET bytesUploaded = :bytesUploaded, speedText = :speedText, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgressAndSpeed(id: Long, bytesUploaded: Long, speedText: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE drive_upload_queue SET status = 'SUCCESS', documentId = :documentId, bytesUploaded = filesize, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markSuccess(id: Long, documentId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE drive_upload_queue SET status = 'FAILED', errorMessage = :errorMessage, retryCount = retryCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markFailed(id: Long, errorMessage: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE drive_upload_queue SET status = 'QUEUED', errorMessage = NULL, speedText = NULL, bytesUploaded = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun resetForRetry(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM drive_upload_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM drive_upload_queue WHERE status = 'SUCCESS'")
    suspend fun clearCompleted()

    @Query("DELETE FROM drive_upload_queue")
    suspend fun deleteAll()
}
