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

    @Query("UPDATE upload_queue SET status = 'PENDING', retryCount = 0 WHERE status = 'FAILED'")
    suspend fun retryFailedTasks()

    @Query("UPDATE upload_queue SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetUploadingToPending()
}
