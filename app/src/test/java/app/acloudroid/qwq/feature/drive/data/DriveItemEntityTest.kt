package app.acloudroid.qwq.feature.drive.data

import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveItemEntityTest {

    @Test
    fun `displayName appends extension for FILE when missing`() {
        val item = createEntity(
            name = "A测试文件",
            type = DriveItemEntity.TYPE_FILE,
            extension = "pdf"
        )
        assertEquals("A测试文件.pdf", item.displayName)
    }

    @Test
    fun `displayName preserves name when extension is already present`() {
        val item = createEntity(
            name = "report.pdf",
            type = DriveItemEntity.TYPE_FILE,
            extension = "pdf"
        )
        assertEquals("report.pdf", item.displayName)
    }

    @Test
    fun `displayName matching is case-insensitive for extension`() {
        val item = createEntity(
            name = "photo.HEIC",
            type = DriveItemEntity.TYPE_FILE,
            extension = "heic"
        )
        assertEquals("photo.HEIC", item.displayName)
    }

    @Test
    fun `displayName does not append extension for FOLDER`() {
        val item = createEntity(
            name = "Documents",
            type = DriveItemEntity.TYPE_FOLDER,
            extension = "folder"
        )
        assertEquals("Documents", item.displayName)
    }

    @Test
    fun `displayName falls back to default when name is blank`() {
        val item = createEntity(
            name = "",
            type = DriveItemEntity.TYPE_FILE,
            extension = "txt"
        )
        assertEquals("未命名.txt", item.displayName)
    }

    private fun createEntity(
        name: String,
        type: String,
        extension: String?
    ): DriveItemEntity {
        return DriveItemEntity(
            drivewsid = "FILE::com.apple.CloudDocs::test-123",
            docwsid = "test-123",
            parentDrivewsid = DriveItemEntity.ROOT_DRIVEWSID,
            name = name,
            type = type,
            zone = DriveItemEntity.CLOUD_DOCS_ZONE,
            size = 1024L,
            dateModified = null,
            dateChanged = null,
            dateCreated = null,
            etag = "1",
            extension = extension,
            directChildrenCount = 0,
            itemId = "CKH_123",
            iconUrl = null,
            childrenFetched = true,
            lastSyncedAt = System.currentTimeMillis()
        )
    }
}
