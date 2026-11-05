package app.acloudroid.qwq.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the magic-byte gate that guards the picker's thumbnail cache (ISS-035).
 *
 * The cache check used to be `file.length() > 0`. A `200` response is not proof of image bytes:
 * `cvws` can answer a dead signature with a short HTML or XML error document under success status,
 * and `FileOutputStream` happily writes that to disk. Once a non-empty non-image landed in
 * `cmp_previews/<mediaId>.thumb`, every later `onOpenPreview` short-circuited on the cached bytes
 * and handed Glide something undecodable — a permanently blank tile that no amount of scrolling
 * repaired, because the file never stopped being "non-empty".
 *
 * The rule now: a cached preview is only served if it starts with a container magic we recognise,
 * and it must clear a small size floor. These checks are intentionally about *containers*, not
 * dimensions — decoding every cold tile would cost more than the download it guards.
 */
class PreviewValidationTest {

    // Mirrors ICloudMediaProvider.MIN_PREVIEW_BYTES.
    private val minBytes = 512L

    /** Mirrors `ICloudMediaProvider.isKnownImageMagic`. */
    private fun isKnownImageMagic(head: ByteArray): Boolean {
        fun u(i: Int) = head[i].toInt() and 0xFF

        if (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return true
        if (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) return true
        if (u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x38) return true
        if (u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
            u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50
        ) return true
        if (u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) return true
        return false
    }

    /** Mirrors `ICloudMediaProvider.isPlausiblePreview`, over an in-memory buffer. */
    private fun isPlausible(bytes: ByteArray): Boolean {
        if (bytes.size.toLong() < minBytes) return false
        if (bytes.size < 12) return false
        return isKnownImageMagic(bytes.copyOf(12))
    }

    private fun jpeg(size: Int = 1024) =
        ByteArray(size).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte(); it[3] = 0xE0.toByte()
        }

    private fun png(size: Int = 1024) =
        ByteArray(size).also {
            it[0] = 0x89.toByte(); it[1] = 0x50; it[2] = 0x4E; it[3] = 0x47
            it[4] = 0x0D; it[5] = 0x0A; it[6] = 0x1A; it[7] = 0x0A
        }

    private fun gif(size: Int = 1024) =
        ByteArray(size).also {
            it[0] = 0x47; it[1] = 0x49; it[2] = 0x46; it[3] = 0x38 // "GIF8"
        }

    private fun webp(size: Int = 1024) =
        ByteArray(size).also {
            it[0] = 0x52; it[1] = 0x49; it[2] = 0x46; it[3] = 0x46 // "RIFF"
            it[8] = 0x57; it[9] = 0x45; it[10] = 0x42; it[11] = 0x50 // "WEBP"
        }

    private fun heic(size: Int = 1024) =
        ByteArray(size).also {
            // ISO-BMFF layout: a 4-byte box size then "ftyp"
            it[0] = 0x00; it[1] = 0x00; it[2] = 0x00; it[3] = 0x18
            it[4] = 0x66; it[5] = 0x74; it[6] = 0x79; it[7] = 0x70 // "ftyp"
        }

    @Test
    fun `jpeg is accepted`() {
        assertTrue(isPlausible(jpeg()))
    }

    @Test
    fun `png is accepted`() {
        assertTrue(isPlausible(png()))
    }

    @Test
    fun `gif is accepted`() {
        assertTrue(isPlausible(gif()))
    }

    @Test
    fun `webp is accepted`() {
        assertTrue(isPlausible(webp()))
    }

    @Test
    fun `heic is accepted because original-quality bytes may be heif`() {
        assertTrue(isPlausible(heic()))
    }

    @Test
    fun `empty file is rejected`() {
        assertFalse(isPlausible(ByteArray(0)))
    }

    @Test
    fun `short error body under a 200 is rejected`() {
        // The exact failure this guards: a CDN error document saved as "success".
        val html = "<html><body>410 Gone</body></html>".toByteArray()
        assertFalse(isPlausible(html))
    }

    @Test
    fun `large non-image payload is still rejected on magic`() {
        // Size alone must never be trusted — only the container magic is authoritative.
        val garbage = ByteArray(4096) { 0x41 } // 'A'
        assertFalse(isPlausible(garbage))
    }

    @Test
    fun `truncated header below the size floor is rejected`() {
        // 8 bytes of a valid JPEG prefix: real magic, no usable payload.
        val stub = jpeg(size = 8)
        assertFalse(isPlausible(stub))
    }

    @Test
    fun `jpeg just above the size floor is accepted`() {
        assertTrue(isPlausible(jpeg(size = minBytes.toInt())))
    }

    @Test
    fun `an mp4 is indistinguishable from heic by magic alone`() {
        // Documents the limit of this check: `ftyp` at offset 4 is shared by HEIC and MP4, so the
        // magic gate cannot reject a video container. The real guard for videos is
        // resolveThumbUrl refusing to fall back to medUrl/originalUrl for VIDEO assets — see
        // VideoThumbUrlTest. If you tighten this check, keep that one in place.
        val mp4 = ByteArray(1024).also {
            it[4] = 0x66; it[5] = 0x74; it[6] = 0x79; it[7] = 0x70 // "ftyp"
        }
        assertTrue(isPlausible(mp4))
    }
}
