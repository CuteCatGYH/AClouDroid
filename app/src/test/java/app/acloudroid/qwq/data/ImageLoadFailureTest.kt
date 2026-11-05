package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.image.ImageLoadFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * P2-011: only a dead signature (HTTP 403/410) is worth re-minting. A decoder failure must never
 * be classified as a URL problem, otherwise the detail viewer fires pointless `/records/lookup`
 * calls that the dedup window then has to swallow.
 *
 * Note: constructing a real Coil `HttpException` needs okhttp, which is not on the unit-test
 * classpath. [ImageLoadFailure.httpCodeOf] is therefore exercised through the plain-exception
 * paths here; the HTTP-code mapping itself is a one-line comparison over a nullable Int.
 */
class ImageLoadFailureTest {

    @Test
    fun `non http failures are not url issues`() {
        // A BitmapFactory / ImageDecoder failure surfaces as a plain exception with no response.
        assertFalse(ImageLoadFailure.isExpiredUrl(IOException("BitmapFactory returned a null bitmap")))
        assertFalse(ImageLoadFailure.isExpiredUrl(RuntimeException("decode failed")))
        assertFalse(ImageLoadFailure.isExpiredUrl(null))
    }

    @Test
    fun `http code is null for non http failures`() {
        assertNull(ImageLoadFailure.httpCodeOf(IOException("decode failed")))
        assertNull(ImageLoadFailure.httpCodeOf(RuntimeException("decode failed")))
        assertNull(ImageLoadFailure.httpCodeOf(null))
    }

    @Test
    fun `expired url codes are the two the CDN uses`() {
        // Guards the rule itself; see ImageLoadFailure.isExpiredUrl.
        assertTrue(ImageLoadFailure.isExpiredUrlCode(410))
        assertTrue(ImageLoadFailure.isExpiredUrlCode(403))
        assertFalse(ImageLoadFailure.isExpiredUrlCode(404))
        assertFalse(ImageLoadFailure.isExpiredUrlCode(500))
        assertFalse(ImageLoadFailure.isExpiredUrlCode(200))
        assertFalse(ImageLoadFailure.isExpiredUrlCode(null))
    }
}
