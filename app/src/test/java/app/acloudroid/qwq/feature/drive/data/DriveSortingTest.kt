package app.acloudroid.qwq.feature.drive.data

import app.acloudroid.qwq.data.settings.DriveSortField
import app.acloudroid.qwq.data.settings.DriveSortOrder
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.drive.ui.sortDriveItems
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveSortingTest {

    private val folderA = DriveItemEntity(
        drivewsid = "FOLDER::zone::a",
        docwsid = "a",
        parentDrivewsid = "ROOT",
        name = "Alpha Folder",
        type = DriveItemEntity.TYPE_FOLDER,
        zone = "com.apple.CloudDocs",
        size = 0L,
        dateModified = "2026-01-01T10:00:00Z",
        dateChanged = null,
        dateCreated = null,
        etag = null,
        extension = null,
        directChildrenCount = 5,
        itemId = null,
        iconUrl = null,
        childrenFetched = true,
        lastSyncedAt = 0L
    )

    private val folderB = DriveItemEntity(
        drivewsid = "FOLDER::zone::b",
        docwsid = "b",
        parentDrivewsid = "ROOT",
        name = "Zebra Folder",
        type = DriveItemEntity.TYPE_FOLDER,
        zone = "com.apple.CloudDocs",
        size = 0L,
        dateModified = "2026-02-01T10:00:00Z",
        dateCreated = null,
        dateChanged = null,
        etag = null,
        extension = null,
        directChildrenCount = 2,
        itemId = null,
        iconUrl = null,
        childrenFetched = true,
        lastSyncedAt = 0L
    )

    private val fileSmall = DriveItemEntity(
        drivewsid = "FILE::zone::c",
        docwsid = "c",
        parentDrivewsid = "ROOT",
        name = "beta_small.txt",
        type = DriveItemEntity.TYPE_FILE,
        zone = "com.apple.CloudDocs",
        size = 100L,
        dateModified = "2026-03-01T10:00:00Z",
        dateCreated = null,
        dateChanged = null,
        etag = null,
        extension = "txt",
        directChildrenCount = 0,
        itemId = "c",
        iconUrl = null,
        childrenFetched = true,
        lastSyncedAt = 0L
    )

    private val fileLarge = DriveItemEntity(
        drivewsid = "FILE::zone::d",
        docwsid = "d",
        parentDrivewsid = "ROOT",
        name = "alpha_large.pdf",
        type = DriveItemEntity.TYPE_FILE,
        zone = "com.apple.CloudDocs",
        size = 5000L,
        dateModified = "2025-12-01T10:00:00Z",
        dateCreated = null,
        dateChanged = null,
        etag = null,
        extension = "pdf",
        directChildrenCount = 0,
        itemId = "d",
        iconUrl = null,
        childrenFetched = true,
        lastSyncedAt = 0L
    )

    private val items = listOf(fileSmall, folderB, fileLarge, folderA)

    @Test
    fun sort_byNameAscending_keepsFoldersFirst() {
        val sorted = sortDriveItems(items, DriveSortField.NAME, DriveSortOrder.ASCENDING, foldersFirst = true)
        val expected = listOf(folderA, folderB, fileLarge, fileSmall)
        assertEquals(expected.map { it.name }, sorted.map { it.name })
    }

    @Test
    fun sort_bySizeDescending_keepsFoldersFirst() {
        val sorted = sortDriveItems(items, DriveSortField.SIZE, DriveSortOrder.DESCENDING, foldersFirst = true)
        val expected = listOf(folderB, folderA, fileLarge, fileSmall)
        assertEquals(expected.map { it.drivewsid }, sorted.map { it.drivewsid })
    }

    @Test
    fun sort_byTypeAscending_sortsFilesByExtension() {
        val sorted = sortDriveItems(items, DriveSortField.TYPE, DriveSortOrder.ASCENDING, foldersFirst = true)
        val expected = listOf(folderB, folderA, fileLarge, fileSmall)
        assertEquals(expected.map { it.drivewsid }, sorted.map { it.drivewsid })
    }
}
