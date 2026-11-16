package app.acloudroid.qwq.feature.photos.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.feature.photos.data.MediaStoreDownloader
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadItemEntity
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BatchDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "BatchDownloadWorker"
        const val KEY_BATCH_ID = "batch_id"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as IClouDroidApp
        val container = app.container
        val repo = container.photoRepository
        val db = container.photoDatabase
        val batchDao = db.batchDownloadDao()
        val photoDao = db.photoDao()

        if (repo.isWipeInProgress()) {
            Log.d(TAG, "BatchDownloadWorker skipped - logout in progress")
            return@withContext Result.success()
        }

        val batchId = inputData.getString(KEY_BATCH_ID)
            ?: batchDao.getActiveTask()?.batchId
            ?: return@withContext Result.success()

        val task = batchDao.getTask(batchId)
        if (task == null || task.status == BatchDownloadStatus.CANCELLED) {
            return@withContext Result.success()
        }

        // Reset interrupted downloading items back to PENDING
        batchDao.resetDownloadingToPending(batchId)
        batchDao.updateTaskStatus(batchId, BatchDownloadStatus.DOWNLOADING, System.currentTimeMillis())

        // Initial foreground notification
        val initialNotification = BatchDownloadNotificationHelper.buildOngoing(
            applicationContext,
            batchId,
            "",
            task.successCount,
            task.totalCount,
            null
        )
        try {
            val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    BatchDownloadNotificationHelper.NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(
                    BatchDownloadNotificationHelper.NOTIFICATION_ID,
                    initialNotification
                )
            }
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground service: ${e.message}")
        }

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "iClouDroid:BatchDownloadWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 min safe timeout per tick
        }

        val successCount = AtomicInteger(task.successCount)
        val failedCount = AtomicInteger(task.failedCount)
        val downloadedBytes = AtomicLong(task.downloadedBytes)

        // Rolling speed window (2 seconds)
        var winStartTime = System.currentTimeMillis()
        var winStartBytes = downloadedBytes.get()
        var lastNotifyTime = 0L

        val semaphore = Semaphore(2)

        try {
            while (!isStopped) {
                if (repo.isWipeInProgress()) {
                    Log.d(TAG, "Aborting batch download: wipe in progress")
                    break
                }

                // Check if task status has been paused or cancelled externally
                val currentTask = batchDao.getTask(batchId)
                if (currentTask == null ||
                    currentTask.status == BatchDownloadStatus.PAUSED ||
                    currentTask.status == BatchDownloadStatus.CANCELLED
                ) {
                    Log.i(TAG, "Task $batchId is ${currentTask?.status}, worker exiting")
                    break
                }

                val batchItems = batchDao.getNextPendingItems(batchId, limit = 4)
                if (batchItems.isEmpty()) {
                    // Check if all items finished
                    val pendingLeft = batchDao.countItemsByStatus(batchId, BatchDownloadStatus.ITEM_PENDING)
                    val downloadingLeft = batchDao.countItemsByStatus(batchId, BatchDownloadStatus.ITEM_DOWNLOADING)
                    if (pendingLeft == 0 && downloadingLeft == 0) {
                        Log.i(TAG, "All items in batch $batchId finished!")
                        batchDao.updateTaskStatus(batchId, BatchDownloadStatus.COMPLETED, System.currentTimeMillis())
                        val totalSuccess = successCount.get()
                        BatchDownloadNotificationHelper.cancel(applicationContext)
                        val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
                        manager?.notify(
                            BatchDownloadNotificationHelper.NOTIFICATION_ID,
                            BatchDownloadNotificationHelper.buildFinished(applicationContext, totalSuccess)
                        )
                    }
                    break
                }

                coroutineScope {
                    batchItems.map { item ->
                        async {
                            semaphore.withPermit {
                                if (isStopped || repo.isWipeInProgress()) return@withPermit

                                val curStatus = batchDao.getTask(batchId)?.status
                                if (curStatus == BatchDownloadStatus.PAUSED || curStatus == BatchDownloadStatus.CANCELLED) {
                                    return@withPermit
                                }

                                processDownloadItem(
                                    item = item,
                                    targetPath = task.targetRelativePath,
                                    container = container,
                                    batchId = batchId,
                                    successCount = successCount,
                                    failedCount = failedCount,
                                    downloadedBytes = downloadedBytes,
                                    onByteProgress = { addedBytes ->
                                        downloadedBytes.addAndGet(addedBytes)
                                        val now = System.currentTimeMillis()
                                        if (now - lastNotifyTime >= 1000) {
                                            lastNotifyTime = now
                                            val dt = (now - winStartTime).coerceAtLeast(1)
                                            val speedBytesPerSec = if (dt >= 1000) {
                                                val bytesDelta = downloadedBytes.get() - winStartBytes
                                                val spd = (bytesDelta * 1000L) / dt
                                                winStartTime = now
                                                winStartBytes = downloadedBytes.get()
                                                spd
                                            } else {
                                                0L
                                            }
                                            val speedText = if (speedBytesPerSec > 0) formatSpeed(speedBytesPerSec) else null
                                            val updatedNotification = BatchDownloadNotificationHelper.buildOngoing(
                                                applicationContext,
                                                batchId,
                                                item.filename,
                                                successCount.get(),
                                                task.totalCount,
                                                speedText
                                            )
                                            val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
                                            nm?.notify(BatchDownloadNotificationHelper.NOTIFICATION_ID, updatedNotification)
                                        }
                                    }
                                )
                            }
                        }
                    }.awaitAll()
                }

                // Persist current counts periodically
                batchDao.updateTaskProgress(
                    batchId = batchId,
                    successCount = successCount.get(),
                    failedCount = failedCount.get(),
                    downloadedBytes = downloadedBytes.get(),
                    updatedAt = System.currentTimeMillis()
                )
            }
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (e: Exception) {
                // Ignore
            }

            // If stopped due to pause, update notification
            val finalTask = batchDao.getTask(batchId)
            if (finalTask?.status == BatchDownloadStatus.PAUSED) {
                val pausedNotification = BatchDownloadNotificationHelper.buildPaused(
                    applicationContext,
                    batchId,
                    successCount.get(),
                    finalTask.totalCount
                )
                val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
                nm?.notify(BatchDownloadNotificationHelper.NOTIFICATION_ID, pausedNotification)
            }
        }

        Result.success()
    }

    private suspend fun processDownloadItem(
        item: BatchDownloadItemEntity,
        targetPath: String,
        container: app.acloudroid.qwq.di.AppContainer,
        batchId: String,
        successCount: AtomicInteger,
        failedCount: AtomicInteger,
        downloadedBytes: AtomicLong,
        onByteProgress: (Long) -> Unit
    ) {
        val db = container.photoDatabase
        val batchDao = db.batchDownloadDao()
        val photoDao = db.photoDao()
        val repo = container.photoRepository

        batchDao.updateItemStatus(item.id, BatchDownloadStatus.ITEM_DOWNLOADING, null, null, System.currentTimeMillis())

        val photoAsset = photoDao.getById(item.assetId)
        if (photoAsset == null) {
            batchDao.updateItemStatus(item.id, BatchDownloadStatus.ITEM_SKIPPED, null, "Asset not found locally", System.currentTimeMillis())
            return
        }

        var lastTrackedBytes = 0L
        val result = MediaStoreDownloader.downloadAsset(
            context = applicationContext,
            photo = photoAsset,
            repository = repo,
            customRelativePath = targetPath,
            showNotification = false,
            onProgress = { _, currentBytes ->
                val delta = currentBytes - lastTrackedBytes
                if (delta > 0) {
                    lastTrackedBytes = currentBytes
                    onByteProgress(delta)
                }
            }
        )

        if (result.isSuccess) {
            val uri = result.getOrNull()
            batchDao.updateItemStatus(
                id = item.id,
                status = BatchDownloadStatus.ITEM_SUCCESS,
                mediaStoreUri = uri?.toString(),
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            successCount.incrementAndGet()
        } else {
            val err = result.exceptionOrNull()?.message ?: "Unknown error"
            batchDao.updateItemStatus(
                id = item.id,
                status = BatchDownloadStatus.ITEM_FAILED,
                mediaStoreUri = null,
                errorMessage = err,
                updatedAt = System.currentTimeMillis()
            )
            failedCount.incrementAndGet()
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB/s", kb)
        val mb = kb / 1024.0
        return String.format(java.util.Locale.getDefault(), "%.1f MB/s", mb)
    }
}
