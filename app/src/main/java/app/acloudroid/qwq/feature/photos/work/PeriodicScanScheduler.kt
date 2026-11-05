package app.acloudroid.qwq.feature.photos.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.acloudroid.qwq.data.settings.AppSettings
import app.acloudroid.qwq.data.settings.ScanCadence
import app.acloudroid.qwq.data.settings.ScanFrequency
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Owns the *periodic* full-library walk (distinct from [ScanScheduler], which is a one-shot
 * "finish the interrupted scan" job).
 *
 * ## Why a separate worker name
 *
 * `ScanScheduler` runs once and then goes away; a periodic job never completes. Sharing the unique
 * name would let a manual refresh cancel the schedule (and vice versa), so they are separate
 * work streams that happen to run the same [MonthScanWorker].
 *
 * ## Why the pattern is UPDATE, not KEEP
 *
 * Every call here comes from a user changing the setting. `KEEP` would silently ignore the change
 * whenever work was already enqueued — the user would pick "每天", see nothing happen, and have no
 * way to tell why.
 */
object PeriodicScanScheduler {

    private const val TAG = "PeriodicScanScheduler"
    private const val UNIQUE_WORK_NAME = "periodic_full_scan"

    /**
     * Applies the current preference to WorkManager.
     *
     * Safe to call on every start and after every settings change: it always reconciles WorkManager
     * with the stored preference, including turning the job off.
     */
    fun apply(context: Context, settings: AppSettings) {
        val frequency = settings.scanFrequency.value
        val customDays = settings.customIntervalDays.value
        val periodMillis = ScanCadence.periodMillis(frequency, customDays)

        if (periodMillis == null) {
            cancel(context)
            return
        }

        val initialDelay = ScanCadence.delayUntilPreferredHour(System.currentTimeMillis())

        val request = PeriodicWorkRequestBuilder<MonthScanWorker>(periodMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("force" to true))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(
            TAG,
            "Periodic scan scheduled: $frequency" +
                (if (frequency == ScanFrequency.CUSTOM) " (${customDays}d)" else "") +
                ", first run in ${initialDelay / 60_000} min"
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        Log.i(TAG, "Periodic scan disabled")
    }
}
