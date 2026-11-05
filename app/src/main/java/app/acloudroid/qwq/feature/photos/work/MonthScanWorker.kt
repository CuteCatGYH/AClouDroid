package app.acloudroid.qwq.feature.photos.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.feature.photos.data.MonthScanEngine
import kotlinx.coroutines.CancellationException
import android.app.NotificationManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the month scan running while the app is in the background (P2-016).
 *
 * ## Relationship with the foreground path
 *
 * The scan itself lives in [MonthScanEngine], which is app-scoped and holds its own mutex. This
 * worker does not own any state: it simply asks the engine to continue. If the UI is already
 * scanning, `scanAll()` returns immediately and the worker finishes — there is never a second
 * concurrent walk, and the persisted cursor means whichever path runs last still makes progress.
 *
 * ## Foreground service
 *
 * The scan is a long, network-bound task, so it runs as a foreground service to avoid being killed
 * mid-way. On Android 14+ that requires the `dataSync` type, which also caps the run at six hours —
 * far beyond what this needs, but the failure path is handled anyway (`Result.retry()` lets the
 * system reschedule).
 */
class MonthScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val container = (applicationContext as IClouDroidApp).container
        val engine = container.monthScanEngine

        // If the account is being wiped (logout), there is nothing to scan and writing rows would
        // resurrect rows the teardown is deleting (P2-018).
        if (container.photoRepository.isWipeInProgress()) {
            Log.d(TAG, "Scan worker skipped - logout teardown in progress")
            return@coroutineScope Result.success()
        }

        // Collect progress concurrently while scanAll runs.
        val progressJob = launch {
            try {
                engine.progress.collect { progress ->
                    if (progress.isScanning || progress.isCachingCovers) {
                        try {
                            setForeground(
                                ForegroundInfo(
                                    ScanNotificationHelper.NOTIFICATION_ID,
                                    ScanNotificationHelper.buildOngoing(applicationContext, progress),
                                    foregroundServiceType()
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update foreground info: ${e.message}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Progress notification collector threw: ${e.message}")
            }
        }

        try {
            setForeground(createForegroundInfo(engine))

            val force = inputData.getBoolean("force", false)
            var result = engine.scanAll(force = force)

            // If another job (e.g. PhotosViewModel) was running the scan or if cover caching is active,
            // keep the worker alive as a foreground service so WorkManager does not strip the notification.
            while (engine.progress.value.isScanning || engine.progress.value.isCachingCovers) {
                kotlinx.coroutines.delay(500)
                if (!engine.progress.value.isComplete && !engine.progress.value.isScanning && !engine.progress.value.isCachingCovers) {
                    result = engine.scanAll(force = false)
                }
            }

            progressJob.cancel()

            val finalProgress = engine.progress.value
            if (finalProgress.isComplete) {
                Log.i(TAG, "Background scan finished complete, scanned=${finalProgress.scanned}")
                if (finalProgress.scanned > 0) {
                    val manager = applicationContext.getSystemService(NotificationManager::class.java)
                    manager?.notify(
                        ScanNotificationHelper.NOTIFICATION_ID,
                        ScanNotificationHelper.buildFinished(applicationContext, finalProgress.scanned)
                    )
                } else {
                    ScanNotificationHelper.cancel(applicationContext)
                }
                Result.success()
            } else if (result.isSuccess) {
                Log.i(TAG, "Background scan paused at scanned=${finalProgress.scanned}")
                ScanNotificationHelper.cancel(applicationContext)
                Result.success()
            } else if (runAttemptCount < MAX_ATTEMPTS) {
                Log.w(TAG, "Background scan failed, will retry (attempt $runAttemptCount)")
                Result.retry()
            } else {
                Log.e(TAG, "Background scan failed permanently")
                Result.failure()
            }
        } catch (e: CancellationException) {
            progressJob.cancel()
            throw e
        } catch (e: Exception) {
            progressJob.cancel()
            Log.e(TAG, "Background scan threw", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun createForegroundInfo(engine: MonthScanEngine): ForegroundInfo {
        val progress = engine.progress.value
        return ForegroundInfo(
            ScanNotificationHelper.NOTIFICATION_ID,
            ScanNotificationHelper.buildOngoing(applicationContext, progress),
            foregroundServiceType()
        )
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    companion object {
        private const val TAG = "MonthScanWorker"
        const val UNIQUE_WORK_NAME = "month_scan"
        private const val MAX_ATTEMPTS = 3
    }
}
