package app.acloudroid.qwq.feature.photos.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import app.acloudroid.qwq.IClouDroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [BroadcastReceiver] triggered by [BackupAlarmScheduler]'s idle-penetrating alarm.
 *
 * Scans enabled local photo albums and enqueues immediate [PhotoBackupWorker] execution,
 * then reschedules the next alarm.
 */
class BackupAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TRIGGER_BACKUP_ALARM) return
        Log.i(TAG, "BackupAlarmReceiver triggered")

        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "iClouDroid:BackupAlarmReceiverWakeLock"
        )?.apply {
            acquire(30_000L) // 30s timeout max
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? IClouDroidApp
                val repo = app?.container?.localAlbumRepository
                val settings = app?.container?.appSettings
                val db = app?.container?.photoDatabase

                val enabledBucketIds = settings?.enabledBackupBucketIds?.value ?: emptySet()
                if (enabledBucketIds.isNotEmpty() && repo != null && db != null) {
                    var totalNewlyEnqueued = 0
                    for (bucketId in enabledBucketIds) {
                        val newlyEnqueued = repo.scanAndEnqueueBucket(
                            bucketId,
                            db.uploadQueueDao(),
                            db.uploadedFingerprintDao()
                        )
                        totalNewlyEnqueued += newlyEnqueued
                    }
                    Log.i(TAG, "BackupAlarmReceiver scan completed. Newly enqueued: $totalNewlyEnqueued")
                }

                PhotoBackupWorker.scheduleBackup(context)

                // Reschedule next alarm only if periodic interval mode (> 0L) is active
                val intervalMinutes = settings?.periodicBackupInterval?.value ?: 0L
                if (intervalMinutes > 0L) {
                    BackupAlarmScheduler.schedule(context, intervalMinutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in BackupAlarmReceiver execution", e)
            } finally {
                wakeLock?.let {
                    if (it.isHeld) {
                        runCatching { it.release() }
                    }
                }
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BackupAlarmReceiver"
        const val ACTION_TRIGGER_BACKUP_ALARM = "app.acloudroid.qwq.action.TRIGGER_BACKUP_ALARM"
    }
}
