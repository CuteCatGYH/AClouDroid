package app.acloudroid.qwq.feature.photos.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.acloudroid.qwq.IClouDroidApp

/**
 * Schedules exact/inexact idle-penetrating alarms using [AlarmManager] to wake up
 * photo backup scanning during deep Android Doze mode.
 */
object BackupAlarmScheduler {

    private const val TAG = "BackupAlarmScheduler"
    private const val REQUEST_CODE = 9981

    fun schedule(context: Context, intervalMinutes: Long? = null) {
        val app = context.applicationContext as? IClouDroidApp
        val settings = app?.container?.appSettings
        val interval = intervalMinutes ?: settings?.periodicBackupInterval?.value?.coerceAtLeast(0L) ?: 0L
        if (interval <= 0L) {
            cancel(context)
            return
        }
        val intervalMillis = interval * 60_000L

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, BackupAlarmReceiver::class.java).apply {
            action = BackupAlarmReceiver.ACTION_TRIGGER_BACKUP_ALARM
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = System.currentTimeMillis() + intervalMillis

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }

                if (canScheduleExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.i(TAG, "Scheduled Doze-penetrating backup alarm in $interval minutes (triggerAtMillis=$triggerAtMillis)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule backup alarm", e)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, BackupAlarmReceiver::class.java).apply {
            action = BackupAlarmReceiver.ACTION_TRIGGER_BACKUP_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "Cancelled Doze-penetrating backup alarm")
        }
    }
}
