package app.acloudroid.qwq.provider

import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ICloudDocumentsProviderTest {

    @Test
    fun testResolvePhotoMimeType() {
        val imagePng = PhotoAssetEntity(
            id = "1",
            masterId = "m1",
            filename = "photo.png",
            assetDate = 1000L,
            addedDate = 1000L,
            isFavorite = false,
            isHidden = false,
            mediaType = "IMAGE"
        )
        assertEquals("image/png", ICloudDocumentsProvider.resolvePhotoMimeType(imagePng))

        val videoAsset = PhotoAssetEntity(
            id = "2",
            masterId = "m2",
            filename = "clip.mov",
            assetDate = 1000L,
            addedDate = 1000L,
            isFavorite = false,
            isHidden = false,
            mediaType = "VIDEO"
        )
        assertEquals("video/mp4", ICloudDocumentsProvider.resolvePhotoMimeType(videoAsset))

        val fallbackAsset = PhotoAssetEntity(
            id = "3",
            masterId = "m3",
            filename = "",
            assetDate = 1000L,
            addedDate = 1000L,
            isFavorite = false,
            isHidden = false,
            mediaType = "IMAGE"
        )
        assertEquals("image/jpeg", ICloudDocumentsProvider.resolvePhotoMimeType(fallbackAsset))
    }

    @Test
    fun testResolveDriveMimeType() {
        val pdfItem = DriveItemEntity(
            drivewsid = "FOLDER::zone::doc1",
            docwsid = "doc1",
            parentDrivewsid = DriveItemEntity.ROOT_DRIVEWSID,
            name = "Document.pdf",
            type = DriveItemEntity.TYPE_FILE,
            zone = DriveItemEntity.CLOUD_DOCS_ZONE,
            size = 1024L,
            dateModified = "2025-01-01T00:00:00Z",
            dateChanged = null,
            dateCreated = null,
            etag = null,
            extension = "pdf",
            directChildrenCount = 0,
            itemId = "item1",
            iconUrl = null,
            childrenFetched = true,
            lastSyncedAt = 1000L
        )
        assertEquals("application/pdf", ICloudDocumentsProvider.resolveDriveMimeType(pdfItem))

        val unknownItem = pdfItem.copy(extension = "unknownext123")
        assertEquals("application/octet-stream", ICloudDocumentsProvider.resolveDriveMimeType(unknownItem))
    }

    @Test
    fun testParseIsoToEpochMillis() {
        val isoStr = "2025-05-15T12:00:00Z"
        val millis = ICloudDocumentsProvider.parseIsoToEpochMillis(isoStr)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = millis
        }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY))

        val invalidMillis = ICloudDocumentsProvider.parseIsoToEpochMillis("invalid-date")
        assertTrue(invalidMillis > 0L)
    }
}
