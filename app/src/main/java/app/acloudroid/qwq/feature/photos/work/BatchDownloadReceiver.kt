package app.acloudroid.qwq.feature.photos.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.acloudroid.qwq.IClouDroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BatchDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val batchId = intent.getStringExtra("batchId") ?: return
        val app = context.applicationContext as? IClouDroidApp ?: return
        val batchDownloadManager = app.container.batchDownloadManager

        Log.i("BatchDownloadReceiver", "Received action $action for batchId $batchId")

        when (action) {
            BatchDownloadNotificationHelper.ACTION_PAUSE -> {
                CoroutineScope(Dispatchers.IO).launch {
                    batchDownloadManager.pause(batchId)
                }
            }
            BatchDownloadNotificationHelper.ACTION_RESUME -> {
                CoroutineScope(Dispatchers.IO).launch {
                    batchDownloadManager.startOrResume(batchId)
                }
            }
            BatchDownloadNotificationHelper.ACTION_CANCEL -> {
                CoroutineScope(Dispatchers.IO).launch {
                    // Default to non-delete rollback for notification-triggered cancel
                    batchDownloadManager.cancelAndRollback(batchId, deleteDownloaded = false)
                }
            }
        }
    }
}
