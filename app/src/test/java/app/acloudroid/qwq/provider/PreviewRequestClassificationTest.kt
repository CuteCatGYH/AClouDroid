package app.acloudroid.qwq.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how `ICloudMediaProvider.onOpenPreview` distinguishes lightweight grid thumbnails from
 * full-screen photo/video previews.
 */
class PreviewRequestClassificationTest {

    @Test
    fun `grid tile with MEDIASTORE_THUMB true is a grid thumbnail`() {
        assertTrue(
            ICloudMediaProvider.isGridThumbnailRequest(
                width = 478,
                height = 478,
                previewThumbnail = true,
                mediaStoreThumb = true
            )
        )
    }

    @Test
    fun `fullscreen photo preview with MEDIASTORE_THUMB false is not a grid thumbnail`() {
        // Note that com.google.android.photopicker still keeps EXTRA_PREVIEW_THUMBNAIL=true here,
        // so MEDIASTORE_THUMB=false is the signal that distinguishes full-screen from grid.
        assertFalse(
            ICloudMediaProvider.isGridThumbnailRequest(
                width = 2256,
                height = 1818,
                previewThumbnail = true,
                mediaStoreThumb = false
            )
        )
    }

    @Test
    fun `fullscreen video preview from ExoPlayer is not a grid thumbnail`() {
        assertFalse(
            ICloudMediaProvider.isGridThumbnailRequest(
                width = 2256,
                height = 2256,
                previewThumbnail = false,
                mediaStoreThumb = false
            )
        )
    }

    @Test
    fun `legacy picker without MEDIASTORE_THUMB classifies small PREVIEW_THUMBNAIL as grid`() {
        assertTrue(
            ICloudMediaProvider.isGridThumbnailRequest(
                width = 512,
                height = 512,
                previewThumbnail = true,
                mediaStoreThumb = null
            )
        )
    }

    @Test
    fun `legacy picker without MEDIASTORE_THUMB treats large size or false flag as fullscreen`() {
        assertFalse(
            ICloudMediaProvider.isGridThumbnailRequest(
                width = 1080,
                height = 1920,
                previewThumbnail = true,
                mediaStoreThumb = null
            )
        )
        assertFalse(
            ICloudMediaProvider.isGridThumbnailRequest(
                width = 478,
                height = 478,
                previewThumbnail = false,
                mediaStoreThumb = null
            )
        )
    }
}
