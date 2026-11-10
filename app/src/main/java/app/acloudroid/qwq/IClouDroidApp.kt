package app.acloudroid.qwq

import android.app.Application
import android.util.Log
import app.acloudroid.qwq.di.AppContainer
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application entry point; owns the single [AppContainer].
 *
 * ViewModels retrieve their dependencies via `(application as IClouDroidApp).container`, which
 * keeps them free of static singletons and makes each feature's dependencies explicit.
 */
class IClouDroidApp : Application() {

    lateinit var container: AppContainer
        private set

    lateinit var mediaStoreObserver: app.acloudroid.qwq.feature.photos.data.MediaStoreObserver
        private set

    /** Outlives any screen; used only for fire-and-forget warm-up work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        app.acloudroid.qwq.util.AppLogger.init(this)
        app.acloudroid.qwq.util.AppLogger.i("IClouDroidApp", "Application starting...")
        container = AppContainer(this)

        // Register MediaStoreObserver for real-time photo backup detection
        mediaStoreObserver = app.acloudroid.qwq.feature.photos.data.MediaStoreObserver(this)
        mediaStoreObserver.register()

        // Warm the Coil disk cache off the main thread.
        //
        // On a cold start the first screen asks for ~24 thumbnails at once. Coil's
        // DiskLruCache.initialize() holds a monitor while it scans the journal, so all those
        // lookups queue behind it and the UI thread waits: we measured 160ms of monitor
        // contention and a ~47 frame drop. Paying that cost here, before the grid is composed,
        // moves it off the critical path.
        appScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    ICloudImageLoaderFactory.warmUp(this@IClouDroidApp, container)
                }
            } catch (e: Exception) {
                // Warm-up is best effort; a failure here must never break startup.
                Log.w(TAG, "Coil warm-up failed: ${e.message}")
            }
        }

        // Schedule background backup worker on startup
        app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker.scheduleBackup(this)

        // Start KeepAliveBackupService if enabled in settings
        if (container.appSettings.keepBackgroundAlive.value) {
            app.acloudroid.qwq.feature.photos.work.KeepAliveBackupService.start(this)
        }

        // Repair a queue left behind by a process kill.
        //
        // Two separate pieces of state can be orphaned, and both must be cleared or uploads appear
        // to hang at "排队中" forever:
        //
        //   1. A WorkManager work stuck in RUNNING. Under ExistingWorkPolicy.KEEP it occupies the
        //      unique name permanently, so every later enqueue is silently dropped.
        //   2. A `drive_upload_queue` row stuck in UPLOADING. `drainUploadQueue` calls
        //      resetUploadingToQueued() at the start of each pass, but only for a pass that runs —
        //      and (1) is exactly what prevents one from running. Clearing it here as well makes
        //      recovery independent of whether an upload happens to be triggered.
        //
        // Both are cheap and idempotent, so they run unconditionally off the main thread.
        appScope.launch {
            try {
                app.acloudroid.qwq.feature.drive.work.DriveUploadWorker
                    .pruneStaleWork(this@IClouDroidApp)
                Log.i(TAG, "Drive upload work prune completed")
            } catch (e: Exception) {
                Log.w(TAG, "Drive upload work prune failed: ${e.message}", e)
            }

            try {
                container.driveRepository.uploadQueue.resetUploadingToQueued()
                Log.i(TAG, "Drive queue orphan reset completed")
            } catch (e: Exception) {
                Log.w(TAG, "Drive queue orphan reset failed: ${e.message}", e)
            }

            // Rescheduling is the other half of the repair.
            //
            // Pruning frees the unique name but schedules nothing, so without this a queue left
            // over from a previous session stays QUEUED until the user happens to add another
            // file. This was visible on device: `#10` survived a restart, sat at "排队中", and no
            // worker was ever started for it.
            //
            // The Go session must be ready first. Scheduling straight after startup raced it and
            // the very first upload died with `AUTH_EXPIRED: docws endpoint is not set` — a
            // terminal failure that is NOT retried, so the queued file was lost rather than
            // delayed. ensureGoSession() is idempotent and cheap when a session already exists.
            try {
                if (container.driveRepository.uploadQueue.hasPendingWork()) {
                    Log.i(TAG, "Pending uploads found at startup; priming session before draining")
                    val session = container.driveRepository.ensureGoSession()
                    if (session.isSuccess) {
                        app.acloudroid.qwq.feature.drive.work.DriveUploadWorker
                            .forceEnqueue(this@IClouDroidApp)
                    } else {
                        Log.w(
                            TAG,
                            "Session unavailable (${session.exceptionOrNull()?.message}); " +
                                "leaving uploads QUEUED for the next attempt"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Drive startup reschedule failed: ${e.message}", e)
            }
        }
    }

    private companion object {
        private const val TAG = "IClouDroidApp"
    }
}
