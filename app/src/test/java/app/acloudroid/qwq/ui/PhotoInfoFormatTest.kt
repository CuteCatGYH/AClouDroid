package app.acloudroid.qwq.ui

import app.acloudroid.qwq.feature.photos.ui.PhotoInfoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Formatting rules for the info panel.
 *
 * These are plain string transforms, so they are verified here rather than on a device. The
 * interesting cases are the ones a photographer would notice immediately: a shutter shown as
 * "1/739s" rather than a rounded "1/700s", and an aperture without a phantom trailing zero.
 */
class PhotoInfoFormatTest {

    // ---------------------------------------------------------------- aperture

    @Test
    fun `aperture keeps two decimals without phantom zeros`() {
        assertEquals("f/1.85", PhotoInfoFormat.aperture(1.85))
        // 1.8 must not render as "f/1.80" — it reads as a rounding error next to a spec sheet.
        assertEquals("f/1.8", PhotoInfoFormat.aperture(1.8))
        assertEquals("f/2", PhotoInfoFormat.aperture(2.0))
        assertEquals("f/22", PhotoInfoFormat.aperture(22.0))
    }

    @Test
    fun `aperture is null when unrecorded`() {
        assertNull(PhotoInfoFormat.aperture(null))
        // 0 is "not present", never a real aperture.
        assertNull(PhotoInfoFormat.aperture(0.0))
        assertNull(PhotoInfoFormat.aperture(-1.0))
    }

    // ---------------------------------------------------------------- shutter

    @Test
    fun `shutter below one second is a real fraction`() {
        // 0.001353s is ~1/739s. Rounding to a "nice" 1/750 would misreport the capture.
        assertEquals("1/739s", PhotoInfoFormat.shutter(0.001353))
        assertEquals("1/8000s", PhotoInfoFormat.shutter(0.000125))
        assertEquals("1/2s", PhotoInfoFormat.shutter(0.5))
    }

    @Test
    fun `shutter at or above one second is decimal`() {
        assertEquals("1s", PhotoInfoFormat.shutter(1.0))
        assertEquals("2.5s", PhotoInfoFormat.shutter(2.5))
        assertEquals("30s", PhotoInfoFormat.shutter(30.0))
    }

    @Test
    fun `shutter is null when unrecorded`() {
        assertNull(PhotoInfoFormat.shutter(null))
        assertNull(PhotoInfoFormat.shutter(0.0))
    }

    // ---------------------------------------------------------------- focal length

    @Test
    fun `focal length shows 35mm equivalent when it differs`() {
        assertEquals("6.76 mm · 24 mm 等效", PhotoInfoFormat.focalLength(6.764, 24))
    }

    @Test
    fun `focal length survives partial data`() {
        assertEquals("6.76 mm", PhotoInfoFormat.focalLength(6.764, null))
        assertEquals("24 mm 等效", PhotoInfoFormat.focalLength(null, 24))
        assertNull(PhotoInfoFormat.focalLength(null, null))
    }

    // ---------------------------------------------------------------- iso / bias

    @Test
    fun `iso renders only when positive`() {
        assertEquals("ISO 64", PhotoInfoFormat.iso(64))
        assertNull(PhotoInfoFormat.iso(null))
        assertNull(PhotoInfoFormat.iso(0))
    }

    @Test
    fun `exposure bias carries an explicit sign`() {
        assertEquals("+0.3 EV", PhotoInfoFormat.exposureBias(0.3))
        assertEquals("-0.3 EV", PhotoInfoFormat.exposureBias(-0.3))
        assertEquals("0 EV", PhotoInfoFormat.exposureBias(0.0))
        assertNull(PhotoInfoFormat.exposureBias(null))
    }

    // ---------------------------------------------------------------- GPS

    @Test
    fun `coordinates use six decimals`() {
        assertEquals("36.067100, 120.382600", PhotoInfoFormat.coordinates(36.0671, 120.3826))
    }

    @Test
    fun `negative coordinates keep their sign`() {
        // The sign is the whole point: this is Sydney / the Americas, not their mirror image.
        assertEquals("-33.870000, 151.210000", PhotoInfoFormat.coordinates(-33.87, 151.21))
    }

    @Test
    fun `altitude and direction are humanised`() {
        assertEquals("15.5 m", PhotoInfoFormat.altitude(15.5))
        assertEquals("0 m", PhotoInfoFormat.altitude(0.0))
        assertNull(PhotoInfoFormat.altitude(null))

        assertEquals("273° 西", PhotoInfoFormat.direction(273.4))
        assertNull(PhotoInfoFormat.direction(null))
    }

    @Test
    fun `direction normalises out-of-range headings`() {
        assertEquals("0° 北", PhotoInfoFormat.direction(360.0))
        assertEquals("10° 北", PhotoInfoFormat.direction(370.0))
        assertEquals("350° 北", PhotoInfoFormat.direction(-10.0))
    }

    // ---------------------------------------------------------------- exif datetime

    @Test
    fun `exif date is rewritten from Apple's colon form`() {
        assertEquals(
            "2026年10月21日 11:03:54",
            PhotoInfoFormat.exifDateTime("2026:10:21 11:03:54")
        )
    }

    @Test
    fun `exif date falls back to the raw value when unusual`() {
        // Don't silently drop something we don't understand.
        assertEquals("2026-10-21", PhotoInfoFormat.exifDateTime("2026-10-21"))
        assertNull(PhotoInfoFormat.exifDateTime(null))
        assertNull(PhotoInfoFormat.exifDateTime("  "))
    }

    @Test
    fun `exif date rejects impossible month`() {
        assertEquals("2026:13:21 11:03:54", PhotoInfoFormat.exifDateTime("2026:13:21 11:03:54"))
    }
}
