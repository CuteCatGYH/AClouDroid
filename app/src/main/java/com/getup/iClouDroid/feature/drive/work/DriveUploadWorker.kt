package com.getup.iClouDroid.feature.drive.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.getup.iClouDroid.R
import com.getup.iClouDroid.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background WorkManager worker that drains the Drive upload queue and notifies progress.
 */
class DriveUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val driveRepository = applicationContext.appContainer().driveRepository

        ensureNotificationChannel()

        Log.i(TAG, "DriveUploadWorker started processing upload queue...")

        // Promote to a foreground worker.
        //
        // A 300 MB file on mobile data takes many minutes. As a plain background worker the OS is
        // free to cancel it whenever it wants memory — which is exactly what happened on device
        // (WM-WorkerWrapper: Work ... was cancelled at 22:21:06, followed by a restart that
        // re-executed the same task). Foreground execution is the only way WorkManager guarantees
        // the transfer is allowed to run to completion, and it also gives the user a visible
        // ongoing notification, which an upload of this length needs regardless.
        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground promotion (background start restrictions).
            // Falling through is correct: the upload still works, it just loses the guarantee.
            Log.w(TAG, "setForeground failed, continuing as a background worker: ${e.message}")
        }

        // Poll the repository's active-task flow on a short cadence and push to the notification
        // directly.
        //
        // ## Why the notification used to freeze and then jump to 100%
        //
        // This used to `collect(observeActive())` in a side scope, while the *same* worker also ran
        // the drain loop. Two things went wrong:
        //
        //   1. `observeActive()` is a Room Flow over `drive_upload_queue`. `updateProgressAndSpeed`
        //      writes every 250 ms, so the flow emitted constantly, but each emission re-ran
        //      `notify()` on the SAME notification id. Android coalesces notification updates and
        //      will happily drop intermediate ones — the bar was being redrawn far more often than
        //      the system visually updates it, so it looked frozen at whatever value happened to
        //      survive coalescing (18%) and then snapped to 100% at completion.
        //   2. The side scope was cancelled in `finally`, so the last few hundred ms of progress
        //      were never delivered at all.
        //
        // Polling at a rate below Android's coalescing threshold, and emitting only when the
        // rendered percent actually changes, fixes both. The UI banner keeps using the Room flow
        // (it is real Compose state and has no coalescing problem).
        val notificationJob = CoroutineScope(Dispatchers.IO).launch {
            var lastPercent = -1
            var lastText: String? = null
            while (isActive) {
                val active = driveRepository.uploadQueue.observeActiveSnapshot()
                val current = active.firstOrNull()
                if (current != null) {
                    val percent = if (current.filesize > 0) {
                        ((current.bytesUploaded * 100) / current.filesize).toInt().coerceIn(0, 100)
                    } else 0
                    if (percent != lastPercent || current.speedText != lastText) {
                        lastPercent = percent
                        lastText = current.speedText
                        showProgressNotification(
                            filename = current.filename,
                            bytesUploaded = current.bytesUploaded,
                            filesize = current.filesize,
                            speedText = current.speedText
                        )
                    }
                }
                delay(NOTIFICATION_REFRESH_MS)
            }
        }

        val (processedCount, failureCount) = try {
            driveRepository.drainUploadQueue()
        } finally {
            // Let the poller deliver one final frame before the completion notification replaces it.
            delay(200)
            notificationJob.cancel()
        }

        if (processedCount > 0) {
            showCompletionNotification(processedCount)
        } else {
            notificationManager.cancel(NOTIFICATION_ID)
        }

        Log.i(TAG, "DriveUploadWorker finished pass: processed=$processedCount, failed=$failureCount")

        // Bounded retry.
        //
        // WorkManager's default retry backoff keeps re-running the worker forever. With a long
        // upload that the OS already cancelled, that produced a visible cancel/restart loop on
        // device (worker cancelled at 22:21:06, restarted at 22:21:20, then re-executed the same
        // task at 22:21:40). Retrying is still right for a transient network failure, but only a
        // limited number of times — after that the task is surfaced as FAILED in the UI instead of
        // silently looping.
        return when {
            failureCount == 0 -> Result.success()
            runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
            else -> {
                Log.w(TAG, "DriveUploadWorker giving up after ${runAttemptCount + 1} attempts")
                Result.failure()
            }
        }
    }

    /**
     * ForegroundServiceType on Android 14+ must be declared in the manifest for the promotion to
     * be accepted. We use `dataSync`, which is the correct type for a user-initiated file upload.
     */
    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在上传至 iCloud Drive")
            .setContentText("准备中...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification, type)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "云盘文件上传",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "展示 iCloud Drive 文件上传进度与结果"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showProgressNotification(
        filename: String,
        bytesUploaded: Long,
        filesize: Long,
        speedText: String?
    ) {
        val percent = if (filesize > 0) ((bytesUploaded * 100) / filesize).toInt().coerceIn(0, 100) else 0
        // Indeterminate only when there is genuinely nothing to measure. `speedText` carries
        // "校验中 42%" / "8.7 MB/s (77%)" / "正在写入云盘...", so its presence of a '%' is the
        // signal that a determinate bar is meaningful.
        val hasMeasurableProgress = speedText?.contains('%') == true
        val isIndeterminate = !hasMeasurableProgress
        val subtitle = speedText?.takeIf { it.isNotBlank() } ?: "准备中..."

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在上传至 iCloud Drive")
            .setContentText("$filename · $subtitle")
            .setOngoing(true)
            .setProgress(100, percent, isIndeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(count: Int) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("iCloud Drive 上传完成")
            .setContentText("已成功上传 $count 个文件")
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "DriveUploadWorker"
        private const val UNIQUE_WORK_NAME = "icloud_drive_upload_worker"
        private const val CHANNEL_ID = "icloud_drive_upload"
        private const val NOTIFICATION_ID = 2001

        /**
         * Notification redraw cadence.
         *
         * Deliberately slower than the 250 ms progress write. Android rate-limits notification
         * updates (and OEM variants throttle harder), so pushing every sample made intermediate
         * frames get dropped wholesale. 500 ms still looks smooth on a progress bar while staying
         * under the coalescing threshold.
         */
        private const val NOTIFICATION_REFRESH_MS = 500L

        /**
         * How many times a failing drain pass may be retried before the task is surfaced as FAILED.
         *
         * `resume`-style infinite retry is wrong for uploads: the OS will cancel the worker anyway
         * when it needs memory, and every restart re-runs the drain from scratch.
         */
        private const val MAX_RETRY_ATTEMPTS = 3

        /**
         * Must be distinct from [NOTIFICATION_ID]; the foreground notification is owned by the
         * system while the worker runs, and reusing the id fights with our own progress updates.
         */
        private const val FOREGROUND_NOTIFICATION_ID = 2002

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setConstraints(constraints)
                .build()

            // APPEND_OR_REPLACE, not REPLACE.
            //
            // REPLACE cancels the running worker outright. On device this was observable as the
            // upload restarting from zero (`Work ... was cancelled` followed by
            // `DriveUploadWorker started processing upload queue...` for the same file), because
            // picking a second file enqueued a brand-new unique work while the first was mid-flight.
            // APPEND_OR_REPLACE lets the current pass finish and then runs the new request, and the
            // shared drain mutex in DriveRepository makes the mutual exclusion explicit either way.
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
