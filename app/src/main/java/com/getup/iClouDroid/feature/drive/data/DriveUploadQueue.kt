package com.getup.iClouDroid.feature.drive.data

import com.getup.iClouDroid.feature.drive.data.db.DriveUploadDao
import com.getup.iClouDroid.feature.drive.data.db.DriveUploadEntity
import com.getup.iClouDroid.feature.drive.data.db.DriveUploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe queue manager for Drive uploads.
 */
class DriveUploadQueue(
    private val dao: DriveUploadDao
) {
    private val queueMutex = Mutex()

    fun observeAll(): Flow<List<DriveUploadEntity>> = dao.observeAllUploads()

    fun observeActive(): Flow<List<DriveUploadEntity>> = dao.observeActiveUploads()

    /**
     * Active tasks plus failures from the last [FAILED_VISIBLE_WINDOW_MS].
     *
     * This is what the upload banner renders. Using [observeActive] alone made a rejected file
     * disappear silently, which is indistinguishable from "the upload never started".
     */
    fun observeVisible(): Flow<List<DriveUploadEntity>> =
        dao.observeVisibleUploads(System.currentTimeMillis() - FAILED_VISIBLE_WINDOW_MS)

    /** How long a failed upload stays on screen so the reason can be read. */
    companion object {
        const val FAILED_VISIBLE_WINDOW_MS = 10 * 60 * 1000L
    }

    /**
     * One-shot read of the active tasks.
     *
     * The notification poller uses this instead of collecting [observeActive]. Room emits a new
     * list on every write, and progress is written every 250 ms — far faster than Android redraws
     * a notification. Feeding every emission to `notify()` made the system coalesce updates away,
     * which is why the bar appeared to freeze at 18 % and then jump straight to 100 %.
     */
    suspend fun observeActiveSnapshot(): List<DriveUploadEntity> = dao.getPendingUploads()

    suspend fun enqueue(upload: DriveUploadEntity): Long = queueMutex.withLock {
        dao.insert(upload)
    }

    /**
     * Claims the oldest queued task, if any.
     *
     * ## Why this must NOT reset UPLOADING rows
     *
     * It used to call `dao.resetUploadingToQueued()` on every invocation. Because the worker loop
     * calls this once per iteration, every pass flipped the task it had just claimed back to
     * QUEUED — so the next pass returned the *same* row forever and the loop never advanced.
     *
     * Recovering orphaned UPLOADING rows is a per-run concern, not a per-claim one. It now lives
     * in [resetUploadingToQueued], which the processors call exactly once before starting.
     */
    suspend fun getNextPending(): DriveUploadEntity? = queueMutex.withLock {
        dao.getPendingUploads().firstOrNull { it.status == DriveUploadStatus.QUEUED }
    }

    /**
     * True when at least one row is still waiting to be picked up.
     *
     * Used by the enqueue recovery path to tell "the queue is genuinely empty" apart from
     * "there is work but nothing is draining it" — the latter is the state that used to hang
     * at "排队中" forever.
     */
    suspend fun hasPendingWork(): Boolean = queueMutex.withLock {
        dao.getPendingUploads().any { it.status == DriveUploadStatus.QUEUED }
    }

    suspend fun resetUploadingToQueued() = queueMutex.withLock {
        dao.resetUploadingToQueued()
    }

    suspend fun markUploading(id: Long) = queueMutex.withLock {
        dao.updateStatus(id, DriveUploadStatus.UPLOADING)
    }

    /**
     * Parks a task back to QUEUED.
     *
     * Used when a drain pass is cancelled mid-flight. The Go upload is a blocking JNI call that
     * ignores coroutine cancellation, so a cancelled pass would otherwise leave the row in UPLOADING
     * and the next pass would treat it as an orphan to be restarted — which is how the same file got
     * uploaded twice on device.
     */
    suspend fun markQueued(id: Long) = queueMutex.withLock {
        dao.updateStatus(id, DriveUploadStatus.QUEUED)
    }

    suspend fun updateProgressAndSpeed(id: Long, bytesUploaded: Long, speedText: String?) = queueMutex.withLock {
        dao.updateProgressAndSpeed(id, bytesUploaded, speedText)
    }

    suspend fun markSuccess(id: Long, documentId: String) = queueMutex.withLock {
        dao.markSuccess(id, documentId)
    }

    suspend fun markFailed(id: Long, errorMessage: String) = queueMutex.withLock {
        dao.markFailed(id, errorMessage)
    }

    suspend fun retryUpload(id: Long) = queueMutex.withLock {
        dao.resetForRetry(id)
    }

    suspend fun delete(id: Long) = queueMutex.withLock {
        dao.deleteById(id)
    }

    suspend fun clearCompleted() = queueMutex.withLock {
        dao.clearCompleted()
    }

    suspend fun deleteAll() = queueMutex.withLock {
        dao.deleteAll()
    }
}
