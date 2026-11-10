package app.acloudroid.qwq.feature.photos.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.acloudroid.qwq.MainActivity
import app.acloudroid.qwq.R

/**
 * Foreground service to maintain process priority and prevent OEM background freezing/freezing
 * during photo auto-backup when "保持后台常驻" is enabled in App Settings.
 */
class KeepAliveBackupService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "KeepAliveBackupService started")
        val notification = buildNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground for KeepAliveBackupService", e)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "KeepAliveBackupService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "后台常驻备份服务"
            val descriptionText = "保持应用后台常驻运行"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AClouDroid 后台备份服务")
            .setContentText("保持后台常驻运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveBackupService"
        private const val CHANNEL_ID = "keep_alive_backup_channel"
        private const val NOTIFICATION_ID = 9982

        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveBackupService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "Requested KeepAliveBackupService start")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start KeepAliveBackupService", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, KeepAliveBackupService::class.java)
                context.stopService(intent)
                Log.i(TAG, "Requested KeepAliveBackupService stop")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop KeepAliveBackupService", e)
            }
        }
    }
}
