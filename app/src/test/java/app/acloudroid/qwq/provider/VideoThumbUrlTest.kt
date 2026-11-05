package app.acloudroid.qwq.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the URL fallback chain `onOpenPreview` uses per media type (ISS-035).
 *
 * The chain used to be `thumbUrl ?: medUrl ?: originalUrl` for everything. That is safe for a
 * photo — all three are images of the same subject at different sizes — and wrong for a video,
 * where only `thumbUrl` is an image and `medUrl` / `originalUrl` are the `mp4`/`mov` bytes
 * themselves.
 *
 * Falling back there means handing Glide a video container while `resolveMimeType` reports
 * `image/jpeg`, and that tile stays blank. Measured on device, `thumbUrl` is occasionally absent
 * from a `/records/lookup` response, and the damage is concentrated in the early years because
 * that is where the video density is (48–336 videos per 1000 assets). Returning null turns it into
 * a logged miss a later request can recover, instead of a file that can never decode.
 */
class VideoThumbUrlTest {

    /**
     * Mirrors `ICloudMediaProvider.resolveThumbUrl`'s selection.
     *
     * @return the URL to download, or null when nothing usable exists.
     */
    private fun pick(
        mediaType: String,
        thumb: String?,
        med: String?,
        original: String?
    ): String? {
        val isVideo = mediaType.equals("VIDEO", ignoreCase = true) ||
            mediaType.equals("LIVE_PHOTO", ignoreCase = true)
        // Blank is as good as absent: `/records/lookup` can return `"thumbUrl": ""` for a record
        // whose still has not been transcoded yet.
        val t = thumb?.takeIf { it.isNotBlank() }
        val m = med?.takeIf { it.isNotBlank() }
        val o = original?.takeIf { it.isNotBlank() }
        return if (isVideo) t else t ?: m ?: o
    }

    @Test
    fun `photo falls back to med when thumb is missing`() {
        assertEquals("med", pick("IMAGE", thumb = null, med = "med", original = "orig"))
    }

    @Test
    fun `photo falls back to original when thumb and med are missing`() {
        assertEquals("orig", pick("IMAGE", thumb = null, med = null, original = "orig"))
    }

    @Test
    fun `photo prefers thumb`() {
        assertEquals("thumb", pick("IMAGE", thumb = "thumb", med = "med", original = "orig"))
    }

    @Test
    fun `video with a thumb uses it`() {
        assertEquals("thumb", pick("VIDEO", thumb = "thumb", med = "med", original = "orig"))
    }

    @Test
    fun `video never falls back to med`() {
        // medUrl for a video is the video file, not a still.
        assertNull(pick("VIDEO", thumb = null, med = "med", original = "orig"))
    }

    @Test
    fun `video never falls back to original`() {
        assertNull(pick("VIDEO", thumb = null, med = null, original = "orig"))
    }

    @Test
    fun `live photo is treated as video for thumb selection`() {
        // A Live Photo's originalUrl is a still, but iCloud serves the still through thumbUrl;
        // keeping the same rule for both avoids a second path that can drift.
        assertNull(pick("LIVE_PHOTO", thumb = null, med = "med", original = "orig"))
    }

    @Test
    fun `blank thumb is treated as missing`() {
        assertNull(pick("VIDEO", thumb = "", med = "med", original = "orig"))
        assertEquals("orig", pick("IMAGE", thumb = "", med = null, original = "orig"))
    }

    @Test
    fun `mediaType comparison is case insensitive`() {
        assertEquals("thumb", pick("video", thumb = "thumb", med = "med", original = "orig"))
        assertNull(pick("Video", thumb = null, med = "med", original = "orig"))
    }

    @Test
    fun `unknown media type is treated like a photo`() {
        // Anything that is not explicitly VIDEO/LIVE_PHOTO is assumed to be a still, which matches
        // PhotoAssetEntity's default of "IMAGE".
        assertEquals("med", pick("", thumb = null, med = "med", original = "orig"))
    }
}
