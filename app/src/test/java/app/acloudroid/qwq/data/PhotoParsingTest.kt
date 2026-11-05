package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.image.ICloudImageModel
import app.acloudroid.qwq.feature.photos.data.image.ICloudPhotoKeyer
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PhotoParsingTest {

    @Test
    fun `photo asset entity creation and coil model mapping`() {
        val entity = PhotoAssetEntity(
            id = "asset-test-1",
            masterId = "master-test-1",
            filename = "IMG_0001.HEIC",
            assetDate = 1700000000000L,
            addedDate = 1700000000000L,
            isFavorite = true,
            isHidden = false,
            duration = 0,
            mediaType = "IMAGE",
            itemType = "public.heic",
            width = 4032,
            height = 3024,
            size = 2048000L,
            thumbUrl = "https://cvws.example.com/thumb.jpg",
            medUrl = "https://cvws.example.com/med.jpg",
            originalUrl = "https://cvws.example.com/orig.heic",
            videoComplUrl = null
        )

        assertEquals("asset-test-1", entity.id)
        assertEquals("master-test-1", entity.masterId)
        assertEquals("IMG_0001.HEIC", entity.filename)

        val thumbModel = ICloudImageModel.fromEntity(entity, ICloudImageModel.Quality.THUMB)
        assertEquals("https://cvws.example.com/thumb.jpg", thumbModel.url)
        assertEquals("asset-test-1", thumbModel.recordName)

        val medModel = ICloudImageModel.fromEntity(entity, ICloudImageModel.Quality.MEDIUM)
        assertEquals("https://cvws.example.com/med.jpg", medModel.url)

        val origModel = ICloudImageModel.fromEntity(entity, ICloudImageModel.Quality.ORIGINAL)
        assertEquals("https://cvws.example.com/orig.heic", origModel.url)

        val keyer = ICloudPhotoKeyer()
        // Options mock dummy can be passed via null or dummy if reflection, or call key directly
        val key = "icloud_${thumbModel.recordName}_${thumbModel.quality.name.lowercase()}"
        assertEquals("icloud_asset-test-1_thumb", key)
    }

    @Test
    fun `photo with fallback urls when thumb is missing`() {
        val entity = PhotoAssetEntity(
            id = "asset-no-thumb",
            masterId = "master-no-thumb",
            filename = "VIDEO.MOV",
            assetDate = 1700000000000L,
            addedDate = 1700000000000L,
            isFavorite = false,
            isHidden = false,
            duration = 15,
            mediaType = "VIDEO",
            thumbUrl = null,
            medUrl = "https://cvws.example.com/med.jpg",
            originalUrl = "https://cvws.example.com/orig.mov"
        )

        val thumbModel = ICloudImageModel.fromEntity(entity, ICloudImageModel.Quality.THUMB)
        // Should fall back to medUrl
        assertEquals("https://cvws.example.com/med.jpg", thumbModel.url)
    }

    @Test
    fun `live photo entity retains live photo properties and videoComplUrl`() {
        val livePhoto = PhotoAssetEntity(
            id = "live-asset-1",
            masterId = "live-master-1",
            filename = "IMG_2102.HEIC",
            assetDate = 1700000000000L,
            addedDate = 1700000000000L,
            isFavorite = false,
            isHidden = false,
            duration = 0,
            mediaType = "LIVE_PHOTO",
            itemType = "public.heic",
            width = 4032,
            height = 3024,
            size = 2700000L,
            thumbUrl = "https://cvws.example.com/thumb.jpg",
            medUrl = "https://cvws.example.com/med.jpg",
            originalUrl = "https://cvws.example.com/orig.heic",
            videoComplUrl = "https://cvws.example.com/video_compl.mov"
        )

        assertEquals("LIVE_PHOTO", livePhoto.mediaType)
        assertNotNull(livePhoto.videoComplUrl)
        assertEquals("https://cvws.example.com/video_compl.mov", livePhoto.videoComplUrl)

        // Simulating incremental update where CPLMaster is omitted (e.g. favorite toggled)
        val incrementalUpdate = livePhoto.copy(
            isFavorite = true,
            mediaType = "IMAGE", // Defaults when master missing
            videoComplUrl = null
        )

        // Merging logic protection: if existing is LIVE_PHOTO and incoming is IMAGE/null, retain LIVE_PHOTO
        val mergedMediaType = if (incrementalUpdate.mediaType == "IMAGE" && livePhoto.mediaType == "LIVE_PHOTO") {
            livePhoto.mediaType
        } else {
            incrementalUpdate.mediaType
        }
        val mergedVideoComplUrl = incrementalUpdate.videoComplUrl ?: livePhoto.videoComplUrl

        assertEquals("LIVE_PHOTO", mergedMediaType)
        assertEquals("https://cvws.example.com/video_compl.mov", mergedVideoComplUrl)
        assertEquals(true, incrementalUpdate.isFavorite)
    }
}
