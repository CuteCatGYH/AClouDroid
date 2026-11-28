package app.acloudroid.qwq.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins matching cloud photos/videos (including Samsung Motion Photos whose local byte size differs
 * from CloudKit's split still size) to local MediaStore entries for Photo Picker deduplication.
 */
class LocalMediaStoreMatcherTest {

    @Test
    fun `matches samsung motion photo with millisecond vs second timestamp difference and differing size`() {
        val index = LocalMediaStoreIndex(
            listOf(
                LocalMediaStoreIndex.Entry(
                    id = 2325L,
                    displayName = "20260925_210825.jpg",
                    dateTakenMs = 1790341705770L,
                    dateModifiedSec = 1790341707L,
                    size = 7779090L,
                    isVideo = false
                )
            )
        )
        assertEquals(
            2325L,
            index.findMatchingId(
                isVideo = false,
                filename = "20260925_210825.jpg",
                assetDateMs = 1790341705000L,
                sizeBytes = 2798346L
            )
        )
    }

    @Test
    fun `matches static photo or screenshot with exact filename size and date`() {
        val index = LocalMediaStoreIndex(
            listOf(
                LocalMediaStoreIndex.Entry(
                    id = 2682L,
                    displayName = "Screenshot_20261001_131915_Meituan.png",
                    dateTakenMs = 1790831955185L,
                    dateModifiedSec = 1790831955L,
                    size = 475339L,
                    isVideo = false
                )
            )
        )
        assertEquals(
            2682L,
            index.findMatchingId(
                isVideo = false,
                filename = "Screenshot_20261001_131915_Meituan.png",
                assetDateMs = 1790831955000L,
                sizeBytes = 475339L
            )
        )
    }

    @Test
    fun `matches downloaded live photo with heic to jpg extension change at exact date`() {
        val index = LocalMediaStoreIndex(
            listOf(
                LocalMediaStoreIndex.Entry(
                    id = 9001L,
                    displayName = "IMG_2199.jpg",
                    dateTakenMs = 1795769060121L,
                    dateModifiedSec = 1795769060L,
                    size = 5100000L,
                    isVideo = false
                )
            )
        )
        assertEquals(
            9001L,
            index.findMatchingId(
                isVideo = false,
                filename = "IMG_2199.HEIC",
                assetDateMs = 1795769060121L,
                sizeBytes = 2100000L
            )
        )
    }

    @Test
    fun `does not false match different dates even when filename collides`() {
        val index = LocalMediaStoreIndex(
            listOf(
                LocalMediaStoreIndex.Entry(
                    id = 100L,
                    displayName = "IMG_0001.JPG",
                    dateTakenMs = 1700000000000L,
                    dateModifiedSec = 1700000000L,
                    size = 1500000L,
                    isVideo = false
                )
            )
        )
        assertNull(
            index.findMatchingId(
                isVideo = false,
                filename = "IMG_0001.JPG",
                assetDateMs = 1790000000000L,
                sizeBytes = 1600000L
            )
        )
    }

    @Test
    fun `keeps video and image buckets separate`() {
        val index = LocalMediaStoreIndex(
            listOf(
                LocalMediaStoreIndex.Entry(
                    id = 55L,
                    displayName = "clip.mp4",
                    dateTakenMs = 1790000000000L,
                    dateModifiedSec = 1790000000L,
                    size = 5000000L,
                    isVideo = true
                )
            )
        )
        assertNull(
            index.findMatchingId(
                isVideo = false,
                filename = "clip.mp4",
                assetDateMs = 1790000000000L,
                sizeBytes = 5000000L
            )
        )
        assertEquals(
            55L,
            index.findMatchingId(
                isVideo = true,
                filename = "clip.mp4",
                assetDateMs = 1790000000000L,
                sizeBytes = 5000000L
            )
        )
    }
}
