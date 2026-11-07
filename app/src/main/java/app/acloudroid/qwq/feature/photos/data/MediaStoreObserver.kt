package app.acloudroid.qwq.feature.photos.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listens to [MediaStore] image and video changes to trigger immediate incremental photo backup scanning.
 *
 * Uses a dedicated background [HandlerThread] so [onChange] is delivered even when the UI main thread
 * is paused/frozen in the background.
 */
class MediaStoreObserver private constructor(
    private val context: Context,
    bgThread: HandlerThread
) : ContentObserver(android.os.Handler(bgThread.looper)) {

    constructor(context: Context) : this(
        context,
        HandlerThread("MediaStoreObserverThread").apply { start() }
    )

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var debounceJob: Job? = null

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d(TAG, "MediaStore change detected: uri=$uri")

        // 2-second debounce to merge burst camera shots
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            triggerIncrementalBackupScan()
        }
    }

    private suspend fun triggerIncrementalBackupScan() {
        val app = context.applicationContext as? IClouDroidApp ?: return
        val repo = app.container.localAlbumRepository
        val settings = app.container.appSettings
        val db = app.container.photoDatabase

        val enabledBucketIds = settings.enabledBackupBucketIds.value
        if (enabledBucketIds.isEmpty()) {
            Log.d(TAG, "No enabled backup buckets, skipping scan")
            return
        }

        try {
            var totalNewlyEnqueued = 0
            for (bucketId in enabledBucketIds) {
                val newlyEnqueued = repo.scanAndEnqueueBucket(
                    bucketId,
                    db.uploadQueueDao(),
                    db.uploadedFingerprintDao()
                )
                totalNewlyEnqueued += newlyEnqueued
            }

            Log.i(TAG, "MediaStoreObserver scan completed. Newly enqueued: $totalNewlyEnqueued")
            if (totalNewlyEnqueued > 0) {
                PhotoBackupWorker.scheduleBackup(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during MediaStoreObserver scan", e)
        }
    }

    fun register() {
        try {
            val cr = context.contentResolver
            cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
            cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, this)
            Log.i(TAG, "Registered MediaStoreObserver for Images and Videos")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register MediaStoreObserver", e)
        }
    }

    fun unregister() {
        try {
            context.contentResolver.unregisterContentObserver(this)
            Log.i(TAG, "Unregistered MediaStoreObserver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister MediaStoreObserver", e)
        }
    }

    companion object {
        private const val TAG = "MediaStoreObserver"
        private const val DEBOUNCE_MS = 2000L
    }
}
