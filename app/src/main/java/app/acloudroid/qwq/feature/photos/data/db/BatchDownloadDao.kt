package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: BatchDownloadTaskEntity)

    @Update
    suspend fun updateTask(task: BatchDownloadTaskEntity)

    @Query("SELECT * FROM batch_download_tasks WHERE batchId = :batchId LIMIT 1")
    suspend fun getTask(batchId: String): BatchDownloadTaskEntity?

    @Query("SELECT * FROM batch_download_tasks WHERE status IN ('PREPARING', 'DOWNLOADING', 'PAUSED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveTask(): BatchDownloadTaskEntity?

    @Query("SELECT * FROM batch_download_tasks ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestTask(): Flow<BatchDownloadTaskEntity?>

    @Query("UPDATE batch_download_tasks SET status = :status, updatedAt = :updatedAt WHERE batchId = :batchId")
    suspend fun updateTaskStatus(batchId: String, status: String, updatedAt: Long)

    @Query("""
        UPDATE batch_download_tasks 
        SET successCount = :successCount, 
            failedCount = :failedCount, 
            downloadedBytes = :downloadedBytes, 
            updatedAt = :updatedAt 
        WHERE batchId = :batchId
    """)
    suspend fun updateTaskProgress(
        batchId: String,
        successCount: Int,
        failedCount: Int,
        downloadedBytes: Long,
        updatedAt: Long
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<BatchDownloadItemEntity>)

    @Query("SELECT * FROM batch_download_items WHERE batchId = :batchId AND status = 'PENDING' ORDER BY id ASC LIMIT 1")
    suspend fun getNextPendingItem(batchId: String): BatchDownloadItemEntity?

    @Query("SELECT * FROM batch_download_items WHERE batchId = :batchId AND status = 'PENDING' ORDER BY id ASC LIMIT :limit")
    suspend fun getNextPendingItems(batchId: String, limit: Int): List<BatchDownloadItemEntity>

    @Query("""
        UPDATE batch_download_items 
        SET status = :status, 
            mediaStoreUri = :mediaStoreUri, 
            errorMessage = :errorMessage, 
            updatedAt = :updatedAt 
        WHERE id = :id
    """)
    suspend fun updateItemStatus(
        id: Long,
        status: String,
        mediaStoreUri: String?,
        errorMessage: String?,
        updatedAt: Long
    )

    @Query("UPDATE batch_download_items SET status = 'PENDING' WHERE batchId = :batchId AND status = 'DOWNLOADING'")
    suspend fun resetDownloadingToPending(batchId: String)

    @Query("SELECT mediaStoreUri FROM batch_download_items WHERE batchId = :batchId AND mediaStoreUri IS NOT NULL")
    suspend fun getDownloadedUris(batchId: String): List<String>

    @Query("SELECT COUNT(*) FROM batch_download_items WHERE batchId = :batchId AND status = :status")
    suspend fun countItemsByStatus(batchId: String, status: String): Int

    @Query("DELETE FROM batch_download_items WHERE batchId = :batchId")
    suspend fun deleteItemsByBatchId(batchId: String)

    @Query("DELETE FROM batch_download_tasks WHERE batchId = :batchId")
    suspend fun deleteTask(batchId: String)

    @Query("DELETE FROM batch_download_items")
    suspend fun clearAllItems()

    @Query("DELETE FROM batch_download_tasks")
    suspend fun clearAllTasks()

    suspend fun clearAll() {
        clearAllItems()
        clearAllTasks()
    }
}
