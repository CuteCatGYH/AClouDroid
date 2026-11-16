package app.acloudroid.qwq.feature.photos.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadDao
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadItemEntity
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadStatus
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadTaskEntity
import app.acloudroid.qwq.feature.photos.data.db.PhotoDao
import app.acloudroid.qwq.feature.photos.work.BatchDownloadNotificationHelper
import app.acloudroid.qwq.feature.photos.work.BatchDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class LibraryEstimate(
    val totalCount: Int,
    val totalBytes: Long,
    val availableBytes: Long
)

class BatchDownloadManager(
    private val context: Context,
    private val batchDao: BatchDownloadDao,
    private val photoDao: PhotoDao
) {
    private val workManager = WorkManager.getInstance(context)

    companion object {
        private const val TAG = "BatchDownloadManager"
        const val UNIQUE_WORK_NAME = "batch_download_work"
        const val DEFAULT_RELATIVE_PATH = "DCIM/Camera"
    }

    fun observeActiveTask(): Flow<BatchDownloadTaskEntity?> {
        return batchDao.observeLatestTask()
    }

    suspend fun getActiveTask(): BatchDownloadTaskEntity? = withContext(Dispatchers.IO) {
        batchDao.getActiveTask()
    }

    suspend fun estimateLibrary(): LibraryEstimate = withContext(Dispatchers.IO) {
        val totalCount = photoDao.countActivePhotos()
        val totalBytes = photoDao.sumActivePhotosSize()
        val statFs = StatFs(Environment.getDataDirectory().path)
        val availableBytes = statFs.availableBytes
        LibraryEstimate(
            totalCount = totalCount,
            totalBytes = totalBytes,
            availableBytes = availableBytes
        )
    }

    suspend fun prepareAndStartBatch(targetRelativePath: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Check if there is already an active task
            val existing = batchDao.getActiveTask()
            if (existing != null && (existing.status == BatchDownloadStatus.DOWNLOADING || existing.status == BatchDownloadStatus.PREPARING)) {
                return@withContext Result.failure(Exception("已有下载任务正在进行中"))
            }

            val totalCount = photoDao.countActivePhotos()
            if (totalCount == 0) {
                return@withContext Result.failure(Exception("没有可下载的云端照片或视频"))
            }
            val totalBytes = photoDao.sumActivePhotosSize()

            val batchId = "batch_${System.currentTimeMillis()}"
            val task = BatchDownloadTaskEntity(
                batchId = batchId,
                status = BatchDownloadStatus.PREPARING,
                targetRelativePath = targetRelativePath.trim().ifBlank { DEFAULT_RELATIVE_PATH },
                totalCount = totalCount,
                successCount = 0,
                failedCount = 0,
                skippedCount = 0,
                totalBytes = totalBytes,
                downloadedBytes = 0L,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            batchDao.insertTask(task)

            // Ingest items in chunks of 1000 to prevent OOM
            val chunkSize = 1000
            var offset = 0
            while (offset < totalCount) {
                val candidates = photoDao.getDownloadCandidatesPage(limit = chunkSize, offset = offset)
                if (candidates.isEmpty()) break

                val now = System.currentTimeMillis()
                val items = candidates.map { c ->
                    BatchDownloadItemEntity(
                        batchId = batchId,
                        assetId = c.id,
                        masterId = c.masterId,
                        filename = c.filename,
                        mediaType = c.mediaType,
                        sizeBytes = c.size,
                        assetDate = c.assetDate,
                        status = BatchDownloadStatus.ITEM_PENDING,
                        mediaStoreUri = null,
                        errorMessage = null,
                        createdAt = now,
                        updatedAt = now
                    )
                }
                batchDao.insertItems(items)
                offset += candidates.size
            }

            batchDao.updateTaskStatus(batchId, BatchDownloadStatus.DOWNLOADING, System.currentTimeMillis())
            enqueueWorker(batchId)

            Log.i(TAG, "Batch $batchId prepared with $totalCount items, worker enqueued")
            Result.success(batchId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare batch download", e)
            Result.failure(e)
        }
    }

    suspend fun startOrResume(batchId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val task = batchDao.getTask(batchId)
            ?: return@withContext Result.failure(Exception("找不到任务 $batchId"))

        batchDao.updateTaskStatus(batchId, BatchDownloadStatus.DOWNLOADING, System.currentTimeMillis())
        enqueueWorker(batchId)
        Result.success(Unit)
    }

    suspend fun pause(batchId: String): Result<Unit> = withContext(Dispatchers.IO) {
        batchDao.updateTaskStatus(batchId, BatchDownloadStatus.PAUSED, System.currentTimeMillis())
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        val task = batchDao.getTask(batchId)
        if (task != null) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm?.notify(
                BatchDownloadNotificationHelper.NOTIFICATION_ID,
                BatchDownloadNotificationHelper.buildPaused(context, batchId, task.successCount, task.totalCount)
            )
        }
        Result.success(Unit)
    }

    suspend fun cancelAndRollback(batchId: String, deleteDownloaded: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        try {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            batchDao.updateTaskStatus(batchId, BatchDownloadStatus.CANCELLED, System.currentTimeMillis())
            BatchDownloadNotificationHelper.cancel(context)

            var deletedCount = 0
            if (deleteDownloaded) {
                val uris = batchDao.getDownloadedUris(batchId)
                val resolver = context.contentResolver
                for (uriStr in uris) {
                    try {
                        val uri = Uri.parse(uriStr)
                        val count = resolver.delete(uri, null, null)
                        if (count > 0) deletedCount += count
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete uri $uriStr: ${e.message}")
                    }
                }
                batchDao.deleteItemsByBatchId(batchId)
                batchDao.deleteTask(batchId)
                Log.i(TAG, "Cancelled batch $batchId and rolled back $deletedCount files from MediaStore")
            } else {
                Log.i(TAG, "Cancelled batch $batchId, keeping downloaded files")
            }

            Result.success(deletedCount)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel batch $batchId", e)
            Result.failure(e)
        }
    }

    private fun enqueueWorker(batchId: String) {
        val request = OneTimeWorkRequestBuilder<BatchDownloadWorker>()
            .setInputData(workDataOf(BatchDownloadWorker.KEY_BATCH_ID to batchId))
            .addTag("batch_download")
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
