package app.acloudroid.qwq.feature.drive.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `retrieveItemDetails` and `retrieveItemDetailsInFolders` use **different wrapper shapes**:
 *
 * ```jsonc
 * // retrieveItemDetails  -> singular `item` object
 * { "success": true, "item": { "drivewsid": "...", "etag": "o53", ... } }
 *
 * // retrieveItemDetailsInFolders -> plural `items` array
 * { "success": true, "items": [ { ... }, { ... } ] }
 * ```
 *
 * `parseSingleItem` read `items`, so it returned null for every probe. The sync planner then could
 * not vouch for "unchanged" and escalated every container to `LIST_STALE` — the walk re-listed all
 * 165 containers on every pass (about 2.5 minutes) and `skipped` was permanently 0.
 *
 * That failure was silent by construction: a null probe and a genuinely-changed etag both produce
 * `LIST_STALE`, so the only visible symptom was a slow, endless progress bar.
 */
class DriveSingleItemParseTest {

    private fun response(itemJson: String) = JSONObject(
        """{"success":true,"drivewsid":"FOLDER::com.apple.CloudDocs::x","item":$itemJson}"""
    )

    /**
     * The shape the server actually returns. This is the regression: reading `items` here used to
     * yield null and kill incremental sync entirely.
     */
    @Test
    fun parsesSingularItemWrapper() {
        val json = response(
            """
            {
              "drivewsid": "FOLDER::com.apple.CloudDocs::08070B70",
              "docwsid": "08070B70",
              "parentId": "FOLDER::com.apple.CloudDocs::root",
              "name": "Downloads",
              "type": "FOLDER",
              "zone": "com.apple.CloudDocs",
              "etag": "ho2",
              "directChildrenCount": 15
            }
            """
        )

        val node = DriveItemMapper.parseSingleItem(json)

        assertNotNull("the singular `item` wrapper must parse", node)
        assertEquals("ho2", node!!.etag)
        assertEquals("Downloads", node.name)
        assertEquals(15, node.directChildrenCount)
        assertEquals("FOLDER::com.apple.CloudDocs::root", node.parentDrivewsid)
    }

    /**
     * The etag is what makes an unchanged tree cost one small request instead of a full re-list.
     * It must survive parsing intact.
     */
    @Test
    fun etagSurvivesParsing() {
        listOf("o53", "ho2", "b6", "70", "gl5").forEach { etag ->
            val node = DriveItemMapper.parseSingleItem(
                response("""{"drivewsid":"FOLDER::a","type":"FOLDER","etag":"$etag"}""")
            )
            assertEquals("etag `$etag` must round-trip", etag, node?.etag)
        }
    }

    /** A node with no etag must parse with `etag == null`, not fail outright. */
    @Test
    fun missingEtagIsNullNotFailure() {
        val node = DriveItemMapper.parseSingleItem(
            response("""{"drivewsid":"FOLDER::a","type":"FOLDER"}""")
        )
        assertNotNull(node)
        assertNull(node!!.etag)
    }

    /**
     * Tolerating the plural array shape keeps the parser from being brittle if the endpoint or the
     * caller ever changes. It is a fallback, not the documented contract.
     */
    @Test
    fun toleratesPluralArrayShape() {
        val json = JSONObject(
            """{"success":true,"items":[{"drivewsid":"FOLDER::a","type":"FOLDER","etag":"zz9"}]}"""
        )
        assertEquals("zz9", DriveItemMapper.parseSingleItem(json)?.etag)
    }

    /** An unsuccessful response is not a node. */
    @Test
    fun unsuccessfulResponseIsNull() {
        assertNull(
            DriveItemMapper.parseSingleItem(
                JSONObject("""{"success":false,"errorMessage":"nope","item":{"drivewsid":"FOLDER::a"}}""")
            )
        )
    }

    /** An empty payload is not a node either. */
    @Test
    fun emptyResponseIsNull() {
        assertNull(DriveItemMapper.parseSingleItem(JSONObject("""{"success":true}""")))
        assertNull(DriveItemMapper.parseSingleItem(JSONObject("""{"success":true,"items":[]}""")))
    }
}
