package app.acloudroid.qwq.feature.photos.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import app.acloudroid.qwq.R

/**
 * Handles notification bar progress and status alerts during photo and video downloads.
 */
object DownloadNotificationHelper {

    const val CHANNEL_ID = "icloud_photo_download"
    private const val TAG = "DownloadNotification"

    @Volatile
    private var channelReady = false

    fun ensureChannel(context: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "下载 iCloud 照片和视频时的进度提示"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        channelReady = true
    }

    /**
     * Shows or updates downloading progress notification.
     *
     * @param notificationId Unique ID for this download request
     * @param filename Display name of the file being downloaded
     * @param progressPercent Progress (0 to 100), or -1 for indeterminate progress
     * @param downloadedBytes Bytes downloaded so far (used for MB display fallback)
     */
    fun showProgress(
        context: Context,
        notificationId: Int,
        filename: String,
        progressPercent: Int,
        downloadedBytes: Long = 0L
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val text = if (progressPercent in 0..100) {
            if (downloadedBytes > 0) {
                "正在下载 $filename ($progressPercent% · ${formatSize(downloadedBytes)})"
            } else {
                "正在下载 $filename ($progressPercent%)"
            }
        } else {
            if (downloadedBytes > 0) {
                "正在下载 $filename (已下载 ${formatSize(downloadedBytes)})"
            } else {
                "正在下载 $filename..."
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("iCloud 文件下载")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (progressPercent in 0..100) {
                    setProgress(100, progressPercent, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()

        try {
            manager.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post download progress notification: ${e.message}")
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.getDefault(), "%.2f GB", gb)
    }

    /**
     * Posts a terminal "download completed" notification.
     */
    fun showFinished(
        context: Context,
        notificationId: Int,
        filename: String,
        contentUri: Uri? = null
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("文件下载完成")
            .setContentText(filename)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (contentUri != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, context.contentResolver.getType(contentUri) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pendingIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach pending intent for $contentUri: ${e.message}")
            }
        }

        try {
            manager.notify(notificationId, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post finished download notification: ${e.message}")
        }
    }

    /**
     * Posts a terminal "download failed" notification.
     */
    fun showFailed(
        context: Context,
        notificationId: Int,
        filename: String,
        errorMsg: String? = null
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val text = "下载失败：$filename" + if (!errorMsg.isNullOrBlank()) " ($errorMsg)" else ""

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("下载失败")
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            manager.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post failed download notification: ${e.message}")
        }
    }

    fun cancel(context: Context, notificationId: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.cancel(notificationId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification $notificationId: ${e.message}")
        }
    }
}
