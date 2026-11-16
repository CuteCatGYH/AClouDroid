package app.acloudroid.qwq.feature.photos.data

import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadItemEntity
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadStatus
import app.acloudroid.qwq.feature.photos.data.db.BatchDownloadTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDownloadStatusTest {

    @Test
    fun `test batch download status constants`() {
        assertEquals("PREPARING", BatchDownloadStatus.PREPARING)
        assertEquals("DOWNLOADING", BatchDownloadStatus.DOWNLOADING)
        assertEquals("PAUSED", BatchDownloadStatus.PAUSED)
        assertEquals("COMPLETED", BatchDownloadStatus.COMPLETED)
        assertEquals("CANCELLED", BatchDownloadStatus.CANCELLED)
        assertEquals("FAILED", BatchDownloadStatus.FAILED)

        assertEquals("PENDING", BatchDownloadStatus.ITEM_PENDING)
        assertEquals("DOWNLOADING", BatchDownloadStatus.ITEM_DOWNLOADING)
        assertEquals("SUCCESS", BatchDownloadStatus.ITEM_SUCCESS)
        assertEquals("FAILED", BatchDownloadStatus.ITEM_FAILED)
        assertEquals("SKIPPED", BatchDownloadStatus.ITEM_SKIPPED)
    }

    @Test
    fun `test task entity progress computation`() {
        val task = BatchDownloadTaskEntity(
            batchId = "batch_123",
            status = BatchDownloadStatus.DOWNLOADING,
            targetRelativePath = "DCIM/Camera",
            totalCount = 100,
            successCount = 25,
            failedCount = 5,
            skippedCount = 0,
            totalBytes = 1024000L,
            downloadedBytes = 256000L,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        assertEquals("DCIM/Camera", task.targetRelativePath)
        assertEquals(100, task.totalCount)
        assertEquals(25, task.successCount)
        val progress = task.successCount.toFloat() / task.totalCount.toFloat()
        assertEquals(0.25f, progress, 0.001f)
    }

    @Test
    fun `test item entity with mediaStoreUri for rollback tracking`() {
        val item = BatchDownloadItemEntity(
            id = 1L,
            batchId = "batch_123",
            assetId = "asset_abc",
            masterId = "master_abc",
            filename = "IMG_0001.HEIC",
            mediaType = "IMAGE",
            sizeBytes = 2048L,
            assetDate = 1700000000000L,
            status = BatchDownloadStatus.ITEM_SUCCESS,
            mediaStoreUri = "content://media/external/images/media/42",
            errorMessage = null,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        assertEquals("content://media/external/images/media/42", item.mediaStoreUri)
        assertTrue(item.status == BatchDownloadStatus.ITEM_SUCCESS)
    }
}
