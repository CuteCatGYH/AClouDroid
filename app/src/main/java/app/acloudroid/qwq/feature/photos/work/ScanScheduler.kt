package app.acloudroid.qwq.feature.photos.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues and cancels the background month scan (P2-016).
 *
 * ## Policies
 *
 *  - **Network required.** There is nothing to scan offline.
 *  - **No charging requirement.** The scan is minutes, not hours; requiring a charger means a user
 *    who never plugs in sees a half-populated gallery indefinitely. Battery cost is bounded by the
 *    network constraint and the fact that the scan only runs until the cursor is exhausted.
 *  - **Exponential backoff from 30s.** A throttle should not turn into a tight retry loop.
 *  - **`KEEP` on re-enqueue.** Whichever trigger fires (app start, going to background, a manual
 *    refresh), an already-queued scan is the same job and must not be duplicated.
 */
object ScanScheduler {

    private const val TAG = "ScanScheduler"

    fun ensureScheduled(context: Context) {
        val request = OneTimeWorkRequestBuilder<MonthScanWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(MonthScanWorker.UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MonthScanWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        Log.d(TAG, "Background scan ensured")
    }

    /**
     * Cancels the scan and removes its notification.
     *
     * Called on logout: leaving the worker running would keep writing rows for an account whose
     * data is being deleted, and would strand an un-dismissable notification on the login screen.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MonthScanWorker.UNIQUE_WORK_NAME)
        ScanNotificationHelper.cancel(context)
        Log.d(TAG, "Background scan cancelled")
    }

    /** Cancels only the notification, leaving the work queued (used when the scan completes). */
    fun clearNotification(context: Context) {
        ScanNotificationHelper.cancel(context)
    }
}
