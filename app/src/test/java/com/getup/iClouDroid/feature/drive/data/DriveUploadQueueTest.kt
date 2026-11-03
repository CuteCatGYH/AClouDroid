package com.getup.iClouDroid.feature.drive.data

import com.getup.iClouDroid.feature.drive.data.db.DriveUploadDao
import com.getup.iClouDroid.feature.drive.data.db.DriveUploadEntity
import com.getup.iClouDroid.feature.drive.data.db.DriveUploadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveUploadQueueTest {

    private class FakeDriveUploadDao : DriveUploadDao {
        private val uploads = mutableMapOf<Long, DriveUploadEntity>()
        private var nextId = 1L

        override suspend fun insert(upload: DriveUploadEntity): Long {
            val id = if (upload.id == 0L) nextId++ else upload.id
            val stored = upload.copy(id = id)
            uploads[id] = stored
            return id
        }

        override suspend fun getById(id: Long): DriveUploadEntity? = uploads[id]

        override suspend fun getByStatus(status: String): List<DriveUploadEntity> =
            uploads.values.filter { it.status == status }.sortedBy { it.createdAt }

        override suspend fun getPendingUploads(): List<DriveUploadEntity> =
            uploads.values.filter { it.status in listOf(DriveUploadStatus.QUEUED, DriveUploadStatus.UPLOADING) }
                .sortedBy { it.createdAt }

        override fun observeAllUploads() = flowOf(uploads.values.sortedByDescending { it.createdAt })

        override fun observeActiveUploads() = flowOf(
            uploads.values.filter { it.status in listOf(DriveUploadStatus.QUEUED, DriveUploadStatus.UPLOADING) }
                .sortedBy { it.createdAt }
        )

        override suspend fun resetUploadingToQueued() {
            uploads.forEach { (id, entity) ->
                if (entity.status == DriveUploadStatus.UPLOADING) {
                    uploads[id] = entity.copy(status = DriveUploadStatus.QUEUED)
                }
            }
        }

        override fun observeVisibleUploads(failedSince: Long) = flowOf(
            uploads.values.filter {
                it.status in listOf(DriveUploadStatus.QUEUED, DriveUploadStatus.UPLOADING) ||
                    (it.status == DriveUploadStatus.FAILED && it.updatedAt >= failedSince)
            }.sortedBy { it.createdAt }
        )

        override suspend fun updateStatus(id: Long, status: String, errorMessage: String?, updatedAt: Long) {
            uploads[id]?.let {
                uploads[id] = it.copy(status = status, errorMessage = errorMessage, updatedAt = updatedAt)
            }
        }

        override suspend fun updateProgressAndSpeed(id: Long, bytesUploaded: Long, speedText: String?, updatedAt: Long) {
            uploads[id]?.let {
                uploads[id] = it.copy(bytesUploaded = bytesUploaded, speedText = speedText, updatedAt = updatedAt)
            }
        }

        override suspend fun markSuccess(id: Long, documentId: String, updatedAt: Long) {
            uploads[id]?.let {
                uploads[id] = it.copy(
                    status = DriveUploadStatus.SUCCESS,
                    documentId = documentId,
                    bytesUploaded = it.filesize,
                    updatedAt = updatedAt
                )
            }
        }

        override suspend fun markFailed(id: Long, errorMessage: String, updatedAt: Long) {
            uploads[id]?.let {
                uploads[id] = it.copy(
                    status = DriveUploadStatus.FAILED,
                    errorMessage = errorMessage,
                    retryCount = it.retryCount + 1,
                    updatedAt = updatedAt
                )
            }
        }

        override suspend fun deleteById(id: Long) {
            uploads.remove(id)
        }

        override suspend fun clearCompleted() {
            uploads.entries.removeIf { it.value.status == DriveUploadStatus.SUCCESS }
        }

        override suspend fun deleteAll() {
            uploads.clear()
        }
    }

    @Test
    fun testQueueLifecycle() = runBlocking {
        val dao = FakeDriveUploadDao()
        val queue = DriveUploadQueue(dao)

        val item1 = DriveUploadEntity(
            localPath = "/cache/file1.txt",
            targetFolderDrivewsId = "FOLDER::root",
            targetFolderPath = "/",
            filename = "file1.txt",
            filesize = 100L,
            mimeType = "text/plain"
        )

        val id1 = queue.enqueue(item1)
        assertEquals(1L, id1)

        val pending = queue.getNextPending()
        assertNotNull(pending)
        assertEquals("file1.txt", pending?.filename)
        assertEquals(DriveUploadStatus.QUEUED, pending?.status)

        queue.markUploading(id1)
        val uploading = dao.getById(id1)
        assertEquals(DriveUploadStatus.UPLOADING, uploading?.status)

        queue.updateProgressAndSpeed(id1, 50L, "1.5 MB/s")
        val progress = dao.getById(id1)
        assertEquals(50L, progress?.bytesUploaded)
        assertEquals("1.5 MB/s", progress?.speedText)

        queue.markSuccess(id1, "DOC-999")
        val success = dao.getById(id1)
        assertEquals(DriveUploadStatus.SUCCESS, success?.status)
        assertEquals("DOC-999", success?.documentId)
        assertEquals(100L, success?.bytesUploaded)

        val noMorePending = queue.getNextPending()
        assertNull(noMorePending)

        queue.clearCompleted()
        assertNull(dao.getById(id1))
    }

    /**
     * Regression: getNextPending() used to call resetUploadingToQueued() on every invocation.
     *
     * Because the drain loop calls it once per iteration, each pass flipped the task it had just
     * claimed back to QUEUED and returned the same row again — the loop could never advance, and
     * the same file was re-uploaded indefinitely.
     */
    @Test
    fun getNextPendingDoesNotReleaseTheTaskItJustReturned() = runBlocking {
        val dao = FakeDriveUploadDao()
        val queue = DriveUploadQueue(dao)

        val id = queue.enqueue(
            DriveUploadEntity(
                localPath = "/cache/a.apk",
                targetFolderDrivewsId = "FOLDER::root",
                targetFolderPath = "/",
                filename = "a.apk",
                filesize = 10L,
                mimeType = "application/vnd.android.package-archive"
            )
        )

        val first = queue.getNextPending()
        assertNotNull(first)
        queue.markUploading(id)

        // A second claim must see the task as UPLOADING and therefore return null, not the same row.
        assertNull("in-flight upload must not be handed out again", queue.getNextPending())

        // The orphan-recovery entry point is still the thing that releases it.
        queue.resetUploadingToQueued()
        assertEquals(id, queue.getNextPending()?.id)
    }

    /** A failed upload must not be retried forever by the same drain pass. */
    @Test
    fun failedUploadIsNotReclaimedByTheSamePass() = runBlocking {
        val dao = FakeDriveUploadDao()
        val queue = DriveUploadQueue(dao)

        val id = queue.enqueue(
            DriveUploadEntity(
                localPath = "/cache/b.zip",
                targetFolderDrivewsId = "FOLDER::root",
                targetFolderPath = "/",
                filename = "b.zip",
                filesize = 10L,
                mimeType = "application/zip"
            )
        )

        queue.markUploading(id)
        queue.markFailed(id, "network")

        assertNull(queue.getNextPending())
    }

    /** The notification poller reads a snapshot; it must reflect live byte counts. */    @Test
    fun activeSnapshotReflectsLiveProgress() = runBlocking {
        val dao = FakeDriveUploadDao()
        val queue = DriveUploadQueue(dao)

        val id = queue.enqueue(
            DriveUploadEntity(
                localPath = "/cache/c.iso",
                targetFolderDrivewsId = "FOLDER::root",
                targetFolderPath = "/",
                filename = "c.iso",
                filesize = 1000L,
                mimeType = "application/octet-stream"
            )
        )
        queue.markUploading(id)
        queue.updateProgressAndSpeed(id, 420L, "校验中 42%")

        val snapshot = queue.observeActiveSnapshot()
        assertEquals(1, snapshot.size)
        assertEquals(420L, snapshot.first().bytesUploaded)
        assertEquals("校验中 42%", snapshot.first().speedText)
    }

    /**
     * Regression: a FAILED task must stay visible.
     *
     * The banner used to observe only QUEUED/UPLOADING, so a 1.2 GB upload rejected by the CDN in
     * 1.7 s vanished from the UI with no message and looked exactly like "the upload never ran".
     */
    @Test
    fun failedUploadRemainsVisibleSoTheReasonCanBeRead() = runBlocking {
        val dao = FakeDriveUploadDao()
        val queue = DriveUploadQueue(dao)

        val id = queue.enqueue(
            DriveUploadEntity(
                localPath = "/cache/big.bin",
                targetFolderDrivewsId = "FOLDER::root",
                targetFolderPath = "/",
                filename = "big.bin",
                filesize = 1_258_291_200L,
                mimeType = "application/octet-stream"
            )
        )
        queue.markUploading(id)
        queue.markFailed(id, "文件过大 (1.2 GB)，服务端拒绝了本次上传")

        // No longer active...
        assertNull("a failed task must not be claimed again", queue.getNextPending())

        // ...but still rendered, with its message intact.
        val visible = dao.observeVisibleUploads(0L).first()
        assertEquals(1, visible.size)
        assertEquals(DriveUploadStatus.FAILED, visible.first().status)
        assertTrue(
            "failure reason must survive so the banner can show it",
            visible.first().errorMessage!!.contains("文件过大")
        )
    }

    /** Old failures must fall outside the window instead of accumulating forever. */
    @Test
    fun staleFailureFallsOutsideTheVisibilityWindow() = runBlocking {
        val dao = FakeDriveUploadDao()
        val queue = DriveUploadQueue(dao)

        val id = queue.enqueue(
            DriveUploadEntity(
                localPath = "/cache/old.bin",
                targetFolderDrivewsId = "FOLDER::root",
                targetFolderPath = "/",
                filename = "old.bin",
                filesize = 10L,
                mimeType = "application/octet-stream"
            )
        )
        queue.markUploading(id)
        queue.markFailed(id, "network")

        // A window that starts after the failure must hide it.
        val future = System.currentTimeMillis() + 60_000
        assertTrue(dao.observeVisibleUploads(future).first().isEmpty())
    }
}
