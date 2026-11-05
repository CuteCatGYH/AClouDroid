package app.acloudroid.qwq.feature.drive.data

import android.util.Log
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class DriveDownloadTaskState(
    val drivewsid: String,
    val item: DriveItemEntity,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progressPercent: Int = 0,
    val errorMessage: String? = null,
    val outcome: DriveDownloadOutcome? = null
)

/**
 * Manages serialized or capped concurrent file downloads for iCloud Drive.
 *
 * Enforces a FIFO queue so multiple file taps do not compete for network bandwidth or JNI heap,
 * while giving the UI and Notification bar a single place to inspect progress and cancel tasks.
 */
class DriveDownloadQueue(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val _tasks = MutableStateFlow<Map<String, DriveDownloadTaskState>>(emptyMap())
    val tasks: StateFlow<Map<String, DriveDownloadTaskState>> = _tasks.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()
    private val queueLock = Mutex()

    /**
     * Enqueues [item] for download if not already queued or downloading.
     *
     * @param downloadAction function that executes the download with a progress callback.
     */
    suspend fun enqueue(
        item: DriveItemEntity,
        downloadAction: suspend (DriveItemEntity, (Int) -> Unit) -> Result<DriveDownloadOutcome>
    ) = queueLock.withLock {
        val current = _tasks.value[item.drivewsid]
        if (current?.status == DownloadStatus.DOWNLOADING || current?.status == DownloadStatus.QUEUED) {
            runCatching { Log.d(TAG, "Item ${item.name} is already ${current.status}") }
            return@withLock
        }

        val newState = DriveDownloadTaskState(
            drivewsid = item.drivewsid,
            item = item,
            status = DownloadStatus.QUEUED,
            progressPercent = 0
        )
        _tasks.value = _tasks.value + (item.drivewsid to newState)
        processQueueLocked(downloadAction)
    }

    fun getActiveJob(drivewsid: String): Job? = activeJobs[drivewsid]

    /**
     * Cancels an in-flight or queued download task for [drivewsid].
     *
     * ## Why Go is signalled synchronously, before the job is cancelled
     *
     * The download runs inside a blocking JNI call (`io.Copy` inside Go). Cancelling the
     * coroutine does not interrupt it — the native frame keeps draining the socket and writes
     * the complete file to the cache directory, so the user sees a stopped progress bar and then
     * a finished download. The only lever that actually stops the transfer is
     * `Icloudcore.cancelDownload`, which cancels the Go-side `context` and closes the TCP stream.
     *
     * It is called first, and synchronously, so the native call is already unwinding by the time
     * the coroutine is cancelled.
     */
    suspend fun cancel(drivewsid: String) {
        val task = _tasks.value[drivewsid]
        if (task != null) {
            DriveDownloader.signalGoCancel(task.item)
        }

        queueLock.withLock {
            val job = activeJobs[drivewsid]
            if (job != null && job.isActive) {
                job.cancel(CancellationException("User cancelled download"))
                activeJobs.remove(drivewsid)
            }
            val current = _tasks.value[drivewsid]
            if (current != null) {
                _tasks.value = _tasks.value + (drivewsid to current.copy(
                    status = DownloadStatus.CANCELLED,
                    errorMessage = "已取消下载"
                ))
            }
        }
    }

    /** Clears finished/failed/cancelled tasks from memory. */
    suspend fun clearTerminalTasks() = queueLock.withLock {
        _tasks.value = _tasks.value.filterValues {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
    }

    private fun processQueueLocked(
        downloadAction: suspend (DriveItemEntity, (Int) -> Unit) -> Result<DriveDownloadOutcome>
    ) {
        val activeCount = _tasks.value.values.count { it.status == DownloadStatus.DOWNLOADING }
        if (activeCount >= MAX_CONCURRENT_DOWNLOADS) return

        val nextCandidate = _tasks.value.values.firstOrNull { it.status == DownloadStatus.QUEUED } ?: return

        val drivewsid = nextCandidate.drivewsid
        val item = nextCandidate.item

        // Mark as DOWNLOADING
        _tasks.value = _tasks.value + (drivewsid to nextCandidate.copy(status = DownloadStatus.DOWNLOADING))

        val job = scope.launch {
            runTask(drivewsid, item, downloadAction)
        }
        activeJobs[drivewsid] = job
    }

    private suspend fun runTask(
        drivewsid: String,
        item: DriveItemEntity,
        downloadAction: suspend (DriveItemEntity, (Int) -> Unit) -> Result<DriveDownloadOutcome>
    ) {
        try {
            val result = downloadAction(item) { percent ->
                _tasks.value = _tasks.value + (drivewsid to (_tasks.value[drivewsid]?.copy(progressPercent = percent) ?: return@downloadAction))
            }

            queueLock.withLock {
                activeJobs.remove(drivewsid)
                result.fold(
                    onSuccess = { outcome ->
                        _tasks.value = _tasks.value + (drivewsid to DriveDownloadTaskState(
                            drivewsid = drivewsid,
                            item = item,
                            status = DownloadStatus.SUCCESS,
                            progressPercent = 100,
                            outcome = outcome
                        ))
                    },
                    onFailure = { e ->
                        _tasks.value = _tasks.value + (drivewsid to DriveDownloadTaskState(
                            drivewsid = drivewsid,
                            item = item,
                            status = DownloadStatus.FAILED,
                            errorMessage = e.message ?: "下载失败"
                        ))
                    }
                )
                processQueueLocked(downloadAction)
            }
        } catch (e: CancellationException) {
            queueLock.withLock {
                activeJobs.remove(drivewsid)
                _tasks.value = _tasks.value + (drivewsid to DriveDownloadTaskState(
                    drivewsid = drivewsid,
                    item = item,
                    status = DownloadStatus.CANCELLED,
                    errorMessage = "已取消下载"
                ))
                processQueueLocked(downloadAction)
            }
        }
    }

    companion object {
        private const val TAG = "DriveDownloadQueue"
        private const val MAX_CONCURRENT_DOWNLOADS = 1
    }
}
