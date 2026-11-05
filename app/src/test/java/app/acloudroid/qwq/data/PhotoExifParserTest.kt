package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.PhotoExifParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of the JSON emitted by `icloudcore.FetchPhotoMetadata`.
 *
 * The contracts that matter here, and that the info panel depends on:
 *  - a missing field stays `null` and is NOT silently turned into 0
 *  - a record without `{GPS}` yields `gps == null`, not a block of zeroes
 *  - a payload with no metadata at all is still a valid result (`hasMetadata == false`)
 */
class PhotoExifParserTest {

    @Test
    fun `parses a full payload`() {
        val json = """
            {
              "success": true,
              "hasMetadata": true,
              "cameraMake": "Apple",
              "cameraModel": "iPhone 15 Pro",
              "lensModel": "iPhone 15 Pro back triple camera",
              "fNumber": 1.85,
              "exposureTime": 0.001353,
              "iso": 64,
              "focalLength": 6.764,
              "focalLength35": 24,
              "exposureBias": -0.3,
              "dateTimeOriginal": "2026:10:21 11:03:54",
              "pixelWidth": 4032,
              "pixelHeight": 3024,
              "colorModel": "RGB",
              "profileName": "Display P3",
              "gps": {
                "latitude": 36.0671,
                "longitude": 120.3826,
                "altitude": 15.5,
                "imgDirection": 273.4,
                "hPositioningError": 8.0,
                "timestamp": "2026:10:21 03:03:54"
              }
            }
        """.trimIndent()

        val exif = PhotoExifParser.parse(json)
        assertNotNull(exif)
        exif!!

        assertTrue(exif.hasMetadata)
        assertEquals("Apple", exif.cameraMake)
        assertEquals("iPhone 15 Pro", exif.cameraModel)
        assertEquals(1.85, exif.fNumber!!, 0.0001)
        assertEquals(0.001353, exif.exposureTime!!, 0.0000001)
        assertEquals(64, exif.iso)
        assertEquals(6.764, exif.focalLength!!, 0.0001)
        assertEquals(24, exif.focalLength35)
        assertEquals(-0.3, exif.exposureBias!!, 0.0001)
        assertEquals("2026:10:21 11:03:54", exif.dateTimeOriginal)
        assertEquals(4032, exif.pixelWidth)
        assertEquals(3024, exif.pixelHeight)
        assertEquals("Display P3", exif.profileName)

        val gps = exif.gps
        assertNotNull(gps)
        assertEquals(36.0671, gps!!.latitude, 0.0001)
        assertEquals(120.3826, gps.longitude, 0.0001)
        assertEquals(15.5, gps.altitude!!, 0.0001)
        assertEquals(273.4, gps.imgDirection!!, 0.0001)
    }

    @Test
    fun `missing fields stay null instead of becoming zero`() {
        // This is the "no GPS / partial EXIF" case: 17 of 25 sampled records look like this.
        val json = """{"success":true,"hasMetadata":true,"cameraModel":"Pixel 9","fNumber":1.7}"""

        val exif = PhotoExifParser.parse(json)!!
        assertEquals("Pixel 9", exif.cameraModel)
        assertEquals(1.7, exif.fNumber!!, 0.0001)

        // Everything else must be absent, not 0 — a panel that renders "ISO 0" is lying.
        assertNull(exif.iso)
        assertNull(exif.exposureTime)
        assertNull(exif.focalLength)
        assertNull(exif.gps)
        assertNull(exif.lensModel)
    }

    @Test
    fun `no metadata payload is a valid result`() {
        val json = """{"success":true,"hasMetadata":false}"""
        val exif = PhotoExifParser.parse(json)
        assertNotNull(exif)
        assertFalse(exif!!.hasMetadata)
        assertNull(exif.cameraModel)
        assertNull(exif.gps)
    }

    @Test
    fun `gps object without coordinates is not a location`() {
        // A `{GPS}` block that carries only a timestamp/altitude must not claim a position.
        val json = """{"success":true,"hasMetadata":true,"gps":{"altitude":15.5}}"""
        val exif = PhotoExifParser.parse(json)!!
        assertNull(exif.gps)
    }

    @Test
    fun `zero coordinates on the equator are preserved`() {
        val json = """
            {"success":true,"hasMetadata":true,
             "gps":{"latitude":0,"longitude":0}}
        """.trimIndent()
        val exif = PhotoExifParser.parse(json)!!
        assertNotNull(exif.gps)
        assertEquals(0.0, exif.gps!!.latitude, 0.0)
        assertEquals(0.0, exif.gps.longitude, 0.0)
    }

    @Test
    fun `blank and malformed payloads return null`() {
        assertNull(PhotoExifParser.parse(null))
        assertNull(PhotoExifParser.parse(""))
        assertNull(PhotoExifParser.parse("   "))
        // A cached negative is stored as "" upstream; it must not parse into anything.
        assertNull(PhotoExifParser.parse("not json at all"))
    }

    @Test
    fun `explicit null values are treated as absent`() {
        val json = """{"success":true,"hasMetadata":true,"cameraModel":null,"fNumber":null}"""
        val exif = PhotoExifParser.parse(json)!!
        assertNull(exif.cameraModel)
        assertNull(exif.fNumber)
    }
}
