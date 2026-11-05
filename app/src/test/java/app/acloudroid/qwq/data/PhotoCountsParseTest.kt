package app.acloudroid.qwq.data

import app.acloudroid.qwq.ui.auth.parsePhotoCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the login-page count scrape (P2-016).
 *
 * CloudKit's cursor API never reports a library total, so the only source for a progress
 * denominator is the "13,689 张照片, 2,247 个视频" line on the iCloud landing page. This is scraped
 * with `evaluateJavascript` just before the WebView is torn down.
 *
 * Every failure mode must degrade to `null` — Apple changing the copy, the page not having settled,
 * the script throwing — because a missing denominator only means an indeterminate bar, whereas
 * throwing here would break login.
 */
class PhotoCountsParseTest {

    @Test
    fun `parses a doubly encoded result`() {
        // evaluateJavascript returns a JSON *string* whose value is itself JSON, so the payload
        // arrives wrapped in an extra layer of quoting.
        val raw = "\"{\\\"photos\\\":13689,\\\"videos\\\":2247}\""
        val counts = parsePhotoCounts(raw)
        assertNotNull(counts)
        assertEquals(13689, counts!!.photos)
        assertEquals(2247, counts.videos)
        assertEquals(15936, counts.total)
    }

    @Test
    fun `parses a bare json object`() {
        val counts = parsePhotoCounts("""{"photos":100,"videos":20}""")
        assertNotNull(counts)
        assertEquals(100, counts!!.photos)
        assertEquals(20, counts.videos)
    }

    @Test
    fun `photos only is still a valid total`() {
        val counts = parsePhotoCounts("""{"photos":500,"videos":0}""")
        assertNotNull(counts)
        assertEquals(500, counts!!.total)
    }

    @Test
    fun `videos only is still a valid total`() {
        val counts = parsePhotoCounts("""{"photos":0,"videos":42}""")
        assertNotNull(counts)
        assertEquals(42, counts!!.total)
    }

    @Test
    fun `all zero is treated as unknown`() {
        // The page rendered but the copy was not matched; reporting "0 items" would make the
        // progress bar divide by zero.
        assertNull(parsePhotoCounts("""{"photos":0,"videos":0}"""))
    }

    @Test
    fun `js null is unknown`() {
        assertNull(parsePhotoCounts("null"))
        assertNull(parsePhotoCounts("\"null\""))
    }

    @Test
    fun `blank and empty are unknown`() {
        assertNull(parsePhotoCounts(null))
        assertNull(parsePhotoCounts(""))
        assertNull(parsePhotoCounts("   "))
    }

    @Test
    fun `malformed payload is unknown and does not throw`() {
        assertNull(parsePhotoCounts("<html>502</html>"))
        assertNull(parsePhotoCounts("{not json"))
        assertNull(parsePhotoCounts("\"{\\\"photos\\\":abc}\""))
    }
}
