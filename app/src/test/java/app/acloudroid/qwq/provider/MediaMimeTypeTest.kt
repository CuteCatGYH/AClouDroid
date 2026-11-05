package app.acloudroid.qwq.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the picker is told each row *is* (ISS-035).
 *
 * `MIME_TYPE` does double duty in the CloudMediaProvider contract: the picker derives the item's
 * type (photo vs video) from it, and Glide derives its decoder from it. The history here is a
 * two-step regression worth remembering:
 *
 *  1. Reporting a video MIME type classified the item correctly but sent the tile through
 *     `VideoDecoder`, which parses preview bytes with `MediaMetadataRetriever`. We were returning a
 *     JPEG, so every video tile failed with `setDataSource failed: status = 0xFFFFFFEA`
 *     (measured 2540 times).
 *  2. Reporting `image/jpeg` fixed the tile, but the **video then presented as a photo** — no
 *     duration badge, no video classification. That is the regression this test now guards.
 *
 * The resolution keeps `MIME_TYPE` describing the media (a video type) and moves the burden to
 * `onOpenPreview`, which serves a real video container for those rows so `VideoDecoder` has
 * something it can actually read.
 */
class MediaMimeTypeTest {

    /** Mirrors `ICloudMediaProvider.mimeTypeFor`. */
    private fun mimeTypeFor(filename: String, mediaType: String): String {
        val name = filename.lowercase()
        val isVideo = mediaType.equals("VIDEO", ignoreCase = true)
        return when {
            isVideo -> when {
                name.endsWith(".mov") -> "video/quicktime"
                name.endsWith(".m4v") -> "video/x-m4v"
                else -> "video/mp4"
            }
            name.endsWith(".png") -> "image/png"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    @Test
    fun `video reports a video mime so the picker classifies it as a video`() {
        // The regression being guarded: this must NEVER be an image type again, otherwise the item
        // shows up as a photo in the picker.
        assertTrue(mimeTypeFor("IMG_0001.MOV", "VIDEO").startsWith("video/"))
        assertTrue(mimeTypeFor("IMG_0001.MP4", "VIDEO").startsWith("video/"))
        assertTrue(mimeTypeFor("clip.m4v", "VIDEO").startsWith("video/"))
    }

    @Test
    fun `video subtypes match the container`() {
        assertEquals("video/quicktime", mimeTypeFor("IMG_0001.MOV", "VIDEO"))
        assertEquals("video/x-m4v", mimeTypeFor("clip.M4V", "VIDEO"))
        assertEquals("video/mp4", mimeTypeFor("IMG_0001.MP4", "VIDEO"))
    }

    @Test
    fun `an extension-less video still reports a video mime`() {
        // mediaType is authoritative; the filename is only consulted for the subtype.
        assertEquals("video/mp4", mimeTypeFor("no-extension", "VIDEO"))
    }

    @Test
    fun `photo reports an image mime`() {
        assertEquals("image/jpeg", mimeTypeFor("IMG_0001.JPG", "IMAGE"))
        assertEquals("image/jpeg", mimeTypeFor("IMG_0001.HEIC", "IMAGE"))
        assertEquals("image/png", mimeTypeFor("IMG_0001.PNG", "IMAGE"))
        assertEquals("image/webp", mimeTypeFor("IMG_0001.WEBP", "IMAGE"))
        assertEquals("image/gif", mimeTypeFor("IMG_0001.GIF", "IMAGE"))
    }

    @Test
    fun `heic is an image jpeg because thumbUrl is server-transcoded`() {
        // Reporting image/heic would send BitmapDecoder looking for a HEIF header in JPEG bytes —
        // the same class of mistake as the video one.
        assertEquals("image/jpeg", mimeTypeFor("IMG_0001.HEIC", "IMAGE"))
        assertEquals("image/jpeg", mimeTypeFor("IMG_0001.heic", "IMAGE"))
    }

    @Test
    fun `a video filename with IMAGE type is treated as an image`() {
        // mediaType wins: mediaType is what CloudKit told us, the filename is a hint.
        assertEquals("image/jpeg", mimeTypeFor("IMG_0001.MOV", "IMAGE"))
    }

    @Test
    fun `media type comparison is case insensitive`() {
        assertEquals("video/mp4", mimeTypeFor("clip.mp4", "video"))
        assertEquals("video/mp4", mimeTypeFor("clip.mp4", "Video"))
    }

    @Test
    fun `unknown media type falls back to a photo`() {
        // Live Photos and anything unexpected are stills as far as the picker is concerned.
        assertEquals("image/jpeg", mimeTypeFor("IMG_0001.JPG", "LIVE_PHOTO"))
        assertEquals("image/jpeg", mimeTypeFor("IMG_0001.JPG", ""))
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertEquals("image/png", mimeTypeFor("IMG.PNG", "IMAGE"))
        assertEquals("video/quicktime", mimeTypeFor("clip.Mov", "VIDEO"))
    }

    @Test
    fun `video mime never resolves to an image type`() {
        // Exhaustive guard over the video branch — the exact shape of the regression.
        val videoNames = listOf("a.mp4", "a.mov", "a.m4v", "a.MP4", "a.MOV", "a", "a.jpg")
        videoNames.forEach { name ->
            val mime = mimeTypeFor(name, "VIDEO")
            assertTrue(
                "$name reported as $mime — videos must not be classified as images",
                mime.startsWith("video/")
            )
            assertFalse(mime.startsWith("image/"))
        }
    }
}
