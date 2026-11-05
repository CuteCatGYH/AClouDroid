package app.acloudroid.qwq.feature.drive.data

import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DriveDownloadQueueTest {

    @Test
    fun `enqueue item updates status and executes task`() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val queue = DriveDownloadQueue(scope = testScope)
        val item = createTestItem("id-1", "file1.pdf")

        var downloadActionCalled = false
        queue.enqueue(item) { _, _ ->
            downloadActionCalled = true
            Result.success(DriveDownloadOutcome(null, "Download/iClouDroid", 100L))
        }

        val state = queue.tasks.value[item.drivewsid]
        assertNotNull(state)
        assertEquals(true, downloadActionCalled)
        assertEquals(DownloadStatus.SUCCESS, state?.status)
    }

    @Test
    fun `cancel item updates task status to CANCELLED`() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val queue = DriveDownloadQueue(scope = testScope)
        val item = createTestItem("id-2", "file2.pdf")

        queue.enqueue(item) { _, _ ->
            Result.failure(Exception("Cancelled"))
        }

        queue.cancel(item.drivewsid)
        val state = queue.tasks.value[item.drivewsid]
        assertNotNull(state)
        assertEquals(DownloadStatus.CANCELLED, state?.status)
    }

    private fun createTestItem(id: String, name: String): DriveItemEntity {
        return DriveItemEntity(
            drivewsid = "FILE::com.apple.CloudDocs::$id",
            docwsid = id,
            parentDrivewsid = DriveItemEntity.ROOT_DRIVEWSID,
            name = name,
            type = DriveItemEntity.TYPE_FILE,
            zone = DriveItemEntity.CLOUD_DOCS_ZONE,
            size = 1024L,
            dateModified = null,
            dateChanged = null,
            dateCreated = null,
            etag = "1",
            extension = "pdf",
            directChildrenCount = 0,
            itemId = "CKH_$id",
            iconUrl = null,
            childrenFetched = true,
            lastSyncedAt = System.currentTimeMillis()
        )
    }
}
