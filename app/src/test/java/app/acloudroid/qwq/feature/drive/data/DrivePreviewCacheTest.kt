package app.acloudroid.qwq.feature.drive.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DrivePreviewCacheTest {

    @Test
    fun sanitize_removesSpecialCharacters() {
        val raw = "FILE::com.apple.CloudDocs::doc-123/bad..name"
        val sanitized = DrivePreviewCache.sanitize(raw)
        assertEquals("FILE__com.apple.CloudDocs__doc-123_bad..name", sanitized)
    }

    @Test
    fun sanitizeExtension_stripsLeadingDotAndLowercases() {
        val raw = ".PDF "
        val sanitized = DrivePreviewCache.sanitizeExtension(raw)
        assertEquals("pdf", sanitized)
    }

    @Test
    fun sanitizeExtension_handlesEmpty() {
        assertEquals("", DrivePreviewCache.sanitizeExtension(""))
        assertEquals("", DrivePreviewCache.sanitizeExtension("   "))
    }
}
