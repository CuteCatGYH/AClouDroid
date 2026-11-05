package app.acloudroid.qwq.feature.photos.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import app.acloudroid.qwq.R

/**
 * Builds the ongoing "photo backup in progress" notification.
 *
 * Designed to provide background progress feedback during auto-backup without sound or vibration.
 */
object BackupNotificationHelper {

    const val CHANNEL_ID = "icloud_photo_backup"
    const val NOTIFICATION_ID = 4202

    @Volatile
    private var channelReady = false

    fun ensureChannel(context: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "相册备份进度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台相册自动备份到 iCloud 时的进度提示"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        channelReady = true
    }

    /**
     * Builds the ongoing progress notification.
     */
    fun buildOngoing(
        context: Context,
        currentFilename: String,
        completedCount: Int,
        totalCount: Int
    ): Notification {
        ensureChannel(context)

        val title = "正在备份照片到 iCloud"
        val text = if (totalCount > 0) {
            "正在上传 $currentFilename ($completedCount/$totalCount)"
        } else {
            "正在上传 $currentFilename"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (totalCount > 0) {
                    setProgress(totalCount, completedCount, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    /**
     * Terminal notification showing backup completion.
     */
    fun buildFinished(context: Context, totalUploaded: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("相册备份完成")
            .setContentText("已成功上传 $totalUploaded 项照片和视频")
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showOngoing(
        context: Context,
        currentFilename: String,
        completedCount: Int,
        totalCount: Int
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(
                NOTIFICATION_ID,
                buildOngoing(context, currentFilename, completedCount, totalCount)
            )
        } catch (e: Exception) {
            android.util.Log.w("BackupNotificationHelper", "Failed to post ongoing backup notification: ${e.message}")
        }
    }

    fun showFinished(context: Context, totalUploaded: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildFinished(context, totalUploaded))
        } catch (e: Exception) {
            android.util.Log.w("BackupNotificationHelper", "Failed to post finished backup notification: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }
}
