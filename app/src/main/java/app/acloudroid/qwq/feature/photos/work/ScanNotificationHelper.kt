package app.acloudroid.qwq.feature.photos.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import app.acloudroid.qwq.R
import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import app.acloudroid.qwq.feature.photos.data.ScanProgress

/**
 * Builds the ongoing "scanning in the background" notification (P2-016).
 *
 * ## Design
 *
 * The scan can take minutes on a large library, and it continues while the app is backgrounded.
 * Without a notification that is invisible work the system may kill at will. So:
 *
 *  - **Ongoing**, so it cannot be dismissed while the scan is genuinely running.
 *  - **Silent**, low importance, `setOnlyAlertOnce` — it must not ping on every batch.
 *  - **Never a hard 100%**: the denominator comes from a scraped estimate, so the bar is clamped
 *    until the walk actually completes (see [ScanProgress.fraction]).
 *
 * The text shows the same two dimensions as the in-app bar: how many items so far and which month
 * is currently being written. A percentage alone is meaningless when the total is a guess.
 */
object ScanNotificationHelper {

    const val CHANNEL_ID = "icloud_photo_scan"
    const val NOTIFICATION_ID = 4201

    /** Creating a channel is idempotent; this just avoids repeated IPC on every update. */
    @Volatile
    private var channelReady = false

    fun ensureChannel(context: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "照片扫描",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台扫描 iCloud 照片库时的进度提示"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        channelReady = true
    }

    /**
     * Builds the ongoing notification for [progress].
     *
     * Deterministic bar only when the login-page scrape produced a total; otherwise the spinner
     * form. Both carry the item count and current month.
     */
    fun buildOngoing(context: Context, progress: ScanProgress): Notification {
        ensureChannel(context)

        val fraction = progress.fraction
        val text = if (progress.estimatedTotal > 0) {
            "已扫描 ${format(progress.scanned)} / ${format(progress.estimatedTotal)} 项" +
                monthSuffix(progress.currentYearMonth)
        } else {
            "已扫描 ${format(progress.scanned)} 项" + monthSuffix(progress.currentYearMonth)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在后台静默扫描 iCloud 照片")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (fraction != null) {
                    setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    /**
     * Terminal notification: a short "finished" message that is *not* ongoing, so the user can
     * dismiss it and it disappears on its own.
     */
    fun buildFinished(context: Context, scanned: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("iCloud 照片扫描完成")
            .setContentText("共整理 ${format(scanned)} 项照片与视频")
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showOngoing(context: Context, progress: ScanProgress) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildOngoing(context, progress))
        } catch (e: Exception) {
            android.util.Log.w("ScanNotificationHelper", "Failed to post ongoing notification: ${e.message}")
        }
    }

    fun showFinished(context: Context, scanned: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildFinished(context, scanned))
        } catch (e: Exception) {
            android.util.Log.w("ScanNotificationHelper", "Failed to post finished notification: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }

    private fun monthSuffix(yearMonth: Int): String =
        if (yearMonth > 0) " · ${MonthBucketing.labelOf(yearMonth)}" else ""

    private fun format(value: Int): String = "%,d".format(value)
}
