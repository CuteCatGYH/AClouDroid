package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface UploadQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<UploadQueueEntity>)

    @Update
    suspend fun update(entity: UploadQueueEntity)

    @Query("SELECT * FROM upload_queue WHERE status = :status ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingTasks(status: String = "PENDING", limit: Int = 10): List<UploadQueueEntity>

    @Query("UPDATE upload_queue SET status = 'UPLOADING', updatedAt = :updatedAt WHERE id IN (:ids) AND status = 'PENDING'")
    suspend fun markTasksUploading(ids: List<Long>, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM upload_queue WHERE mediaStoreUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): UploadQueueEntity?

    @Query("SELECT * FROM upload_queue WHERE localSha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): UploadQueueEntity?

    @Query("DELETE FROM upload_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_queue WHERE status = 'SUCCESS'")
    suspend fun clearCompleted()

    @Query("DELETE FROM upload_queue")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status IN ('PENDING', 'UPLOADING')")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status IN ('PENDING', 'UPLOADING')")
    fun observePendingCount(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT * FROM upload_queue ORDER BY id DESC")
    fun observeAllTasks(): kotlinx.coroutines.flow.Flow<List<UploadQueueEntity>>

    /**
     * Puts every `FAILED` row back in the queue for a fresh set of automatic retries.
     *
     * [lastError] is cleared alongside the status: it is the *reason this attempt failed*, and once
     * the row is queued again it is stale. Leaving it would keep a red error string on a row that is
     * actively uploading (and `BackupQueueSheet` renders `lastError` verbatim on the subtitle line),
     * so the user would see "正在上传 · NETWORK: timeout" and reasonably conclude the retry did not
     * work. A failure that happens *after* the click will populate it again.
     *
     * [UploadQueueEntity.updatedAt] is bumped for the same reason: the retry is a real state change
     * and the queue sheet sorts/reads by it.
     */
    @Query(
        "UPDATE upload_queue SET status = 'PENDING', retryCount = 0, lastError = NULL, " +
            "updatedAt = :updatedAt WHERE status = 'FAILED'"
    )
    suspend fun retryFailedTasks(updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE upload_queue SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetUploadingToPending()

    /**
     * Rows that already burned every automatic retry.
     *
     * Used by the fingerprint reconciliation below: a `FAILED` row is the one state where the
     * upload definitely did *not* land, so its fingerprint must not keep claiming otherwise.
     */
    @Query("SELECT * FROM upload_queue WHERE status = 'FAILED'")
    suspend fun getFailedTasks(): List<UploadQueueEntity>

    /**
     * Every queue row, regardless of status.
     *
     * Used by
     * [app.acloudroid.qwq.feature.photos.data.PhotoRepository.healUnconfirmedFingerprints] to learn
     * which upload fingerprints still have a live attempt behind them. Deliberately a one-shot list
     * rather than a `Flow`: this runs once per worker pass and the table is normally near-empty.
     */
    @Query("SELECT * FROM upload_queue")
    suspend fun getAllTasks(): List<UploadQueueEntity>
}
