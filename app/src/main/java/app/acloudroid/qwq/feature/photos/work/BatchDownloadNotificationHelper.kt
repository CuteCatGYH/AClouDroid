package app.acloudroid.qwq.feature.photos.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.acloudroid.qwq.R

/**
 * Builds ongoing and terminal notifications for full-library batch downloads.
 */
object BatchDownloadNotificationHelper {

    const val CHANNEL_ID = "icloud_batch_download"
    const val NOTIFICATION_ID = 4203

    const val ACTION_PAUSE = "app.acloudroid.qwq.ACTION_BATCH_DOWNLOAD_PAUSE"
    const val ACTION_RESUME = "app.acloudroid.qwq.ACTION_BATCH_DOWNLOAD_RESUME"
    const val ACTION_CANCEL = "app.acloudroid.qwq.ACTION_BATCH_DOWNLOAD_CANCEL"

    @Volatile
    private var channelReady = false

    fun ensureChannel(context: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "全量照片下载进度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "下载所有 iCloud 照片和视频到本地相册时的常驻进度"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        channelReady = true
    }

    fun buildOngoing(
        context: Context,
        batchId: String,
        currentFilename: String,
        completedCount: Int,
        totalCount: Int,
        speedText: String? = null
    ): Notification {
        ensureChannel(context)

        val title = "正在下载 iCloud 照片与视频"
        val subtitle = StringBuilder().apply {
            if (currentFilename.isNotBlank()) {
                append("正在保存: ").append(currentFilename).append(" ")
            }
            if (totalCount > 0) {
                append("(").append(completedCount).append("/").append(totalCount).append(")")
            }
            if (!speedText.isNullOrBlank()) {
                append(" · ").append(speedText)
            }
        }.toString()

        val pauseIntent = Intent(context, BatchDownloadReceiver::class.java).apply {
            action = ACTION_PAUSE
            putExtra("batchId", batchId)
        }
        val pausePendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val cancelIntent = Intent(context, BatchDownloadReceiver::class.java).apply {
            action = ACTION_CANCEL
            putExtra("batchId", batchId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
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
            .addAction(0, "暂停", pausePendingIntent)
            .addAction(0, "终止", cancelPendingIntent)
            .build()
    }

    fun buildPaused(
        context: Context,
        batchId: String,
        completedCount: Int,
        totalCount: Int
    ): Notification {
        ensureChannel(context)

        val title = "iCloud 下载已暂停"
        val text = "已下载 $completedCount / $totalCount 项"

        val resumeIntent = Intent(context, BatchDownloadReceiver::class.java).apply {
            action = ACTION_RESUME
            putExtra("batchId", batchId)
        }
        val resumePendingIntent = PendingIntent.getBroadcast(
            context,
            3,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (totalCount > 0) {
                    setProgress(totalCount, completedCount, false)
                }
            }
            .addAction(0, "继续下载", resumePendingIntent)
            .build()
    }

    fun buildFinished(context: Context, totalDownloaded: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("全量下载完成")
            .setContentText("已成功将 $totalDownloaded 项照片和视频保存到本地相册")
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            // Ignored
        }
    }
}
