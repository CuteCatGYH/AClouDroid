package app.acloudroid.qwq.feature.photos.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.acloudroid.qwq.IClouDroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background worker for scanning local enabled albums, enqueuing, and streaming uploads to iCloud.
 */
class PhotoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as IClouDroidApp
        val container = app.container
        val repo = container.photoRepository
        val localRepo = container.localAlbumRepository
        val appSettings = container.appSettings
        val db = container.photoDatabase

        if (repo.isWipeInProgress()) {
            Log.d(TAG, "PhotoBackupWorker skipped - logout teardown in progress")
            return@withContext Result.success()
        }

        try {
            // Reset any stale UPLOADING tasks from interrupted runs back to PENDING
            db.uploadQueueDao().resetUploadingToPending()

            // Heal fingerprints that were claimed but never proved, *before* the scan below.
            //
            // `uploadPhotoFromQueue` now withdraws its own claim when an upload fails, so this is
            // for rows written by builds that did not: they outlived their queue row and made every
            // later scan skip the file, leaving it permanently un-backed-up (ISS-069). Runs first
            // because the scan is what consumes these rows -- healing afterwards would mean the
            // file waits a whole extra work cycle.
            runCatching { repo.healUnconfirmedFingerprints() }
                .onFailure { Log.w(TAG, "Fingerprint healing failed: ${it.message}") }

            // 1. Pre-scan enabled backup buckets to enqueue any newly added local photos/videos
            val enabledBucketIds = appSettings.enabledBackupBucketIds.value
            if (enabledBucketIds.isNotEmpty()) {
                var preScanNewlyEnqueued = 0
                for (bucketId in enabledBucketIds) {
                    if (repo.isWipeInProgress()) break
                    preScanNewlyEnqueued += localRepo.scanAndEnqueueBucket(
                        bucketId,
                        db.uploadQueueDao(),
                        db.uploadedFingerprintDao()
                    )
                }
                Log.d(TAG, "Pre-scan enqueued $preScanNewlyEnqueued new items")
            }

            val maxConcurrent = appSettings.maxConcurrentUploads.value.coerceIn(1, 3)
            val showNotification = appSettings.showBackupNotification.value

            val totalSuccessCount = AtomicInteger(0)
            val completedCounter = AtomicInteger(0)

            // Initial notification setup if enabled
            val initialPendingCount = db.uploadQueueDao().getPendingCount()
            if (showNotification && initialPendingCount > 0) {
                val foregroundNotification = BackupNotificationHelper.buildOngoing(
                    applicationContext,
                    "准备上传...",
                    0,
                    initialPendingCount
                )
                try {
                    val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ForegroundInfo(
                            BackupNotificationHelper.NOTIFICATION_ID,
                            foregroundNotification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        ForegroundInfo(BackupNotificationHelper.NOTIFICATION_ID, foregroundNotification)
                    }
                    setForeground(foregroundInfo)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to setForeground for PhotoBackupWorker: ${e.message}")
                }
            }

            val semaphore = Semaphore(maxConcurrent)

            // 2. Process queue in batches with configured maxConcurrent concurrency
            while (!repo.isWipeInProgress()) {
                val pendingTasks = db.uploadQueueDao().getPendingTasks(status = "PENDING", limit = maxConcurrent * 4)
                if (pendingTasks.isEmpty()) break

                val totalInQueue = db.uploadQueueDao().getPendingCount()

                coroutineScope {
                    for (task in pendingTasks) {
                        if (repo.isWipeInProgress()) break

                        launch {
                            semaphore.withPermit {
                                if (repo.isWipeInProgress()) return@withPermit

                                // Mark task as UPLOADING precisely when acquiring permit to reflect accurate UI state
                                db.uploadQueueDao().markTasksUploading(listOf(task.id))

                                val currentCount = completedCounter.get()
                                if (showNotification) {
                                    BackupNotificationHelper.showOngoing(
                                        applicationContext,
                                        task.filename,
                                        currentCount,
                                        totalInQueue
                                    )
                                }

                                Log.d(TAG, "Processing upload for: ${task.filename} (id=${task.id})")
                                val uploadResult = repo.uploadPhotoFromQueue(task, applicationContext)

                                if (uploadResult.isSuccess) {
                                    totalSuccessCount.incrementAndGet()
                                    Log.i(TAG, "Successfully uploaded ${task.filename}!")
                                } else {
                                    Log.w(TAG, "Failed to upload ${task.filename}: ${uploadResult.exceptionOrNull()?.message}")
                                }

                                val doneCount = completedCounter.incrementAndGet()
                                if (showNotification) {
                                    BackupNotificationHelper.showOngoing(
                                        applicationContext,
                                        task.filename,
                                        doneCount,
                                        totalInQueue
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val uploadedCount = totalSuccessCount.get()
            if (showNotification && uploadedCount > 0) {
                BackupNotificationHelper.showFinished(applicationContext, uploadedCount)
            } else {
                BackupNotificationHelper.cancel(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "PhotoBackupWorker encountered an error", e)
            BackupNotificationHelper.cancel(applicationContext)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "PhotoBackupWorker"
        const val UNIQUE_WORK_NAME = "photo_backup_work"
        const val IMMEDIATE_WORK_NAME = "photo_backup_immediate_work"
        private const val MAX_ATTEMPTS = 3

        /**
         * Schedules a backlog drain.
         *
         * @param userInitiated true when the *user* just asked for these bytes to go up: the share
         *   sheet's "开始上传" button, or the queue sheet's "立即上传". Those two are explicit
         *   "现在传" instructions, so they must not inherit the background policy's
         *   battery/metered-network gates — doing so left a shared photo sitting in the queue until
         *   the user found the backup screen and pressed "立即上传" *again*. That second press was
         *   never a designed step; it was the background constraints silently refusing to run.
         */
        fun scheduleBackup(context: Context) {
            val appSettings = (context.applicationContext as? IClouDroidApp)?.container?.appSettings
            val isForceUpload = appSettings?.isForceUploadActive() == true

            val allowCellular = isForceUpload || (appSettings?.allowCellularUpload?.value ?: false)
            val requireCharging = if (isForceUpload) false else (appSettings?.requireCharging?.value ?: false)
            val requireBatteryNotLow = if (isForceUpload) false else (appSettings?.requireBatteryNotLow?.value ?: true)

            val networkType = if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED
            val intervalMinutes = appSettings?.periodicBackupInterval?.value?.coerceAtLeast(0L) ?: 0L
            val isImmediateMediaTrigger = intervalMinutes == 0L

            /**
             * Background constraints: network + the user's charging / battery policy.
             *
             * Deliberately **without** a content-URI trigger — see [mediaTriggerConstraints].
             */
            fun backgroundConstraints(): Constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresCharging(requireCharging)
                .setRequiresBatteryNotLow(requireBatteryNotLow)
                .build()

            /**
             * Constraints for the *periodic* job in "立即" mode: same gates plus a MediaStore
             * content trigger, so a newly captured photo starts a backup without waiting out the
             * 15-minute interval.
             *
             * ## Why this must NOT be reused for the one-shot request
             *
             * `addContentUriTrigger` is not an "extra wake-up hint" — it is a **trigger source**.
             * A job registered with one is *only* eligible once a monitored URI actually changes.
             * The one-shot job used to share this constraints object, which broke every path whose
             * bytes never touch MediaStore:
             *
             *  - a photo shared into the app is a `file://` in `cache/share_uploads/`, so no
             *    MediaStore row is ever written;
             *  - pressing "立即上传" re-enqueued (REPLACE) another job with the same trigger.
             *
             * Measured on device, `dumpsys jobscheduler` showed the job sitting with
             * `Num Required Constraints: 0` — nothing unsatisfied, simply never triggered — while
             * the queue sheet sat on "排队中" indefinitely. Forcing it with
             * `cmd jobscheduler run -f` uploaded immediately, which is what confirmed the trigger,
             * not the constraints, was the blocker.
             */
            fun mediaTriggerConstraints(): Constraints {
                val builder = Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .setRequiresCharging(requireCharging)
                    .setRequiresBatteryNotLow(requireBatteryNotLow)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder
                        .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                        .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                        .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                        .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                }
                return builder.build()
            }

            // One-shot: "check the queue now". Explicit `CHANGED`/`APPEND` style work, so it takes
            // plain constraints and is eligible as soon as the network gates are met.
            val constraints = backgroundConstraints()

            val immediateRequest = OneTimeWorkRequestBuilder<PhotoBackupWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                immediateRequest
            )

            if (isImmediateMediaTrigger) {
                BackupAlarmScheduler.cancel(context)
                val periodicRequest = PeriodicWorkRequestBuilder<PhotoBackupWorker>(15L, TimeUnit.MINUTES)
                    .setConstraints(mediaTriggerConstraints())
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest
                )
            } else {
                val effectiveInterval = intervalMinutes.coerceAtLeast(15L)
                val periodicRequest = PeriodicWorkRequestBuilder<PhotoBackupWorker>(effectiveInterval, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest
                )
                BackupAlarmScheduler.schedule(context, effectiveInterval)
            }
            Log.i(TAG, "Scheduled PhotoBackupWorker (interval=${intervalMinutes}m, isImmediateMediaTrigger=$isImmediateMediaTrigger, isForceUpload=$isForceUpload, cellular=$allowCellular, charging=$requireCharging, batteryNotLow=$requireBatteryNotLow)")
        }

        fun cancelBackup(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            BackupAlarmScheduler.cancel(context)
            BackupNotificationHelper.cancel(context)
            Log.i(TAG, "Cancelled PhotoBackupWorker")
        }
    }
}
