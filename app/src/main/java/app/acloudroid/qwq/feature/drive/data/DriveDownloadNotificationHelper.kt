package app.acloudroid.qwq.feature.drive.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import app.acloudroid.qwq.R
import app.acloudroid.qwq.di.appContainer
import com.getup.icloudcore.Icloudcore
import java.util.Locale
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Handles notification bar progress and completion alerts for iCloud Drive file downloads.
 */
object DriveDownloadNotificationHelper {

    const val CHANNEL_ID = "icloud_drive_download"
    private const val TAG = "DriveDownloadNotif"

    @Volatile
    private var channelReady = false

    fun ensureChannel(context: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "云盘文件下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "下载 iCloud 云盘文件时的进度与完成提示"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        channelReady = true
    }

    const val ACTION_CANCEL_DOWNLOAD = "app.acloudroid.qwq.ACTION_CANCEL_DOWNLOAD"
    const val EXTRA_DRIVEWSID = "drivewsid"
    const val EXTRA_NOTIFICATION_ID = "notificationId"

    /**
     * Shows or updates downloading progress in the notification bar.
     *
     * @param notificationId Unique ID for this file download request
     * @param filename Display name of the file being downloaded
     * @param progressPercent Progress (0 to 100), or -1 for indeterminate progress
     * @param downloadedBytes Bytes downloaded so far
     * @param drivewsid Optional item ID for adding a "Cancel" action button
     */
    fun showProgress(
        context: Context,
        notificationId: Int,
        filename: String,
        progressPercent: Int,
        downloadedBytes: Long = 0L,
        drivewsid: String? = null
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
                "正在下载 $filename (${formatSize(downloadedBytes)})"
            } else {
                "正在下载 $filename..."
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("iCloud 云盘下载")
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

        if (!drivewsid.isNullOrBlank()) {
            val cancelIntent = Intent(ACTION_CANCEL_DOWNLOAD).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_DRIVEWSID, drivewsid)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            val cancelPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "取消", cancelPendingIntent)
        }

        try {
            manager.notify(notificationId, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post download progress notification: ${e.message}")
        }
    }

    /**
     * Posts a terminal "download completed" notification with an Intent to open the local file.
     */
    fun showFinished(
        context: Context,
        notificationId: Int,
        filename: String,
        contentUri: Uri?,
        mimeType: String
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("文件下载完成")
            .setContentText("$filename · 点击打开")
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (contentUri != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, mimeType.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }
}

class DriveDownloadReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == DriveDownloadNotificationHelper.ACTION_CANCEL_DOWNLOAD) {
            val drivewsid = intent.getStringExtra(DriveDownloadNotificationHelper.EXTRA_DRIVEWSID)
            val notifId = intent.getIntExtra(DriveDownloadNotificationHelper.EXTRA_NOTIFICATION_ID, 0)
            val docwsid = drivewsid?.let { if (it.contains("::")) it.substringAfterLast("::") else it }

            Log.i("DriveDownloadReceiver", "Cancelling download for drivewsid=$drivewsid, docwsid=$docwsid")

            if (!docwsid.isNullOrBlank()) {
                Icloudcore.cancelDownload(docwsid)
            }
            if (!drivewsid.isNullOrBlank()) {
                Icloudcore.cancelDownload(drivewsid)
                runCatching {
                    val repository = context.appContainer().driveRepository
                    GlobalScope.launch {
                        repository.cancelDownload(drivewsid)
                    }
                }
            }
            if (notifId != 0) {
                DriveDownloadNotificationHelper.cancel(context, notifId)
            }
        }
    }
}
