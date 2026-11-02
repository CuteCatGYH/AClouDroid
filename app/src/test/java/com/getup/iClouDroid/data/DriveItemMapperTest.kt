package com.getup.iClouDroid.data

import com.getup.iClouDroid.feature.drive.data.DriveItemMapper
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing rules for `retrieveItemDetailsInFolders`, pinned against the shapes D0 captured from a
 * real account (docs/REFERENCE.md §5).
 */
class DriveItemMapperTest {

    private fun listing(vararg items: String) = JSONObject(
        """{"success":true,"drivewsid":"FOLDER::com.apple.CloudDocs::root","items":[${items.joinToString(",")}]}"""
    )

    @Test
    fun `file node parses size extension and iso dates`() {
        val json = listing(
            """
            {"drivewsid":"FILE::com.apple.CloudDocs::2ABAAD23","docwsid":"2ABAAD23",
             "parentId":"FOLDER::com.apple.CloudDocs::root","name":"clip.mp4","type":"FILE",
             "zone":"com.apple.CloudDocs","size":2540192,"etag":"gqp::gm9",
             "extension":"mp4","item_id":"CKH_sample",
             "dateCreated":"2026-04-06T13:25:03Z","dateModified":"2026-04-06T13:25:03Z",
             "dateChanged":"2026-04-26T09:59:17Z"}
            """
        )

        val items = DriveItemMapper.parseFolderList(DriveItemEntity.ROOT_DRIVEWSID, json)!!
        assertEquals(1, items.size)
        val item = items.first()

        assertEquals("FILE::com.apple.CloudDocs::2ABAAD23", item.drivewsid)
        assertEquals(DriveItemEntity.TYPE_FILE, item.type)
        assertEquals(2540192L, item.size)
        assertEquals("mp4", item.extension)
        assertEquals("CKH_sample", item.itemId)
        assertFalse("a FILE is not descendable", item.isContainer)

        // Raw ISO8601 strings, NOT epoch millis: the photo parser would turn these into 0 and
        // the UI would render 1970 for every row.
        assertEquals("2026-04-06T13:25:03Z", item.dateModified)
        assertEquals("2026-04-26T09:59:17Z", item.dateChanged)
    }

    @Test
    fun `folder node exposes child count and is descendable`() {
        val json = listing(
            """
            {"drivewsid":"FOLDER::com.apple.CloudDocs::D2D2","docwsid":"D2D2",
             "parentId":"FOLDER::com.apple.CloudDocs::root","name":"Documents","type":"FOLDER",
             "zone":"com.apple.CloudDocs","etag":"npq","directChildrenCount":4,
             "dateCreated":"2026-02-05T05:57:15Z"}
            """
        )

        val item = DriveItemMapper.parseFolderList(DriveItemEntity.ROOT_DRIVEWSID, json)!!.single()
        assertEquals(DriveItemEntity.TYPE_FOLDER, item.type)
        assertEquals(4, item.directChildrenCount)
        assertTrue(item.isContainer)
        // Folders carry no size; it must stay 0 rather than borrow some other number.
        assertEquals(0L, item.size)
    }

    @Test
    fun `app library keeps its own zone and picks the largest icon`() {
        val json = listing(
            """
            {"drivewsid":"FOLDER::com.apple.Keynote::documents","docwsid":"documents",
             "parentId":"FOLDER::com.apple.CloudDocs::root","name":"Keynote","type":"APP_LIBRARY",
             "zone":"com.apple.Keynote","maxDepth":"ANY",
             "icons":[
               {"url":"https://x/icon40","type":"IOS","size":40},
               {"url":"https://x/icon120","type":"IOS","size":120},
               {"url":"https://x/icon80","type":"IOS","size":80}
             ]}
            """
        )

        val item = DriveItemMapper.parseFolderList(DriveItemEntity.ROOT_DRIVEWSID, json)!!.single()

        assertEquals(DriveItemEntity.TYPE_APP_LIBRARY, item.type)
        // The zone is the *app's* container, not com.apple.CloudDocs — this is what makes
        // drivewsid unique, since docwsid is the literal "documents" for every APP_LIBRARY.
        assertEquals("com.apple.Keynote", item.zone)
        assertEquals("documents", item.docwsid)
        assertTrue(item.isContainer)
        // Largest icon wins; the API does not guarantee array order.
        assertEquals("https://x/icon120", item.iconUrl)
    }

    @Test
    fun `multiple app libraries are distinguished only by drivewsid`() {
        val json = listing(
            """{"drivewsid":"FOLDER::com.apple.Keynote::documents","docwsid":"documents",
                "parentId":"r","name":"Keynote","type":"APP_LIBRARY","zone":"com.apple.Keynote"}""",
            """{"drivewsid":"FOLDER::com.apple.Preview::documents","docwsid":"documents",
                "parentId":"r","name":"Preview","type":"APP_LIBRARY","zone":"com.apple.Preview"}"""
        )

        val items = DriveItemMapper.parseFolderList(DriveItemEntity.ROOT_DRIVEWSID, json)!!

        assertEquals(2, items.size)
        assertEquals(2, items.map { it.drivewsid }.toSet().size)
        // docwsid collides across both rows — the DAO must never key on it.
        assertEquals(1, items.map { it.docwsid }.toSet().size)
    }

    @Test
    fun `missing parentId falls back to the container that was queried`() {
        val json = listing(
            """{"drivewsid":"FILE::com.apple.CloudDocs::AAA","docwsid":"AAA",
                "name":"a.txt","type":"FILE","zone":"com.apple.CloudDocs","size":10}"""
        )

        val item = DriveItemMapper.parseFolderList("FOLDER::com.apple.CloudDocs::D2D2", json)!!.single()
        // Without the fallback this row would be unreachable from the UI: its parent would be "".
        assertEquals("FOLDER::com.apple.CloudDocs::D2D2", item.parentDrivewsid)
    }

    @Test
    fun `node without drivewsid is dropped rather than stored unaddressable`() {
        val json = listing(
            """{"docwsid":"ORPHAN","name":"ghost","type":"FILE","zone":"com.apple.CloudDocs"}""",
            """{"drivewsid":"FILE::com.apple.CloudDocs::OK","docwsid":"OK","parentId":"r",
                "name":"real","type":"FILE","zone":"com.apple.CloudDocs"}"""
        )

        val items = DriveItemMapper.parseFolderList(DriveItemEntity.ROOT_DRIVEWSID, json)!!
        assertEquals(1, items.size)
        assertEquals("real", items.single().name)
    }

    @Test
    fun `empty folder is an empty list not a null`() {
        val json = JSONObject("""{"success":true,"drivewsid":"FOLDER::x::root","items":[]}""")
        val items = DriveItemMapper.parseFolderList(DriveItemEntity.ROOT_DRIVEWSID, json)
        // null would be read as a failure and make a genuinely empty folder look broken.
        assertEquals(emptyList<DriveItemEntity>(), items)
    }

    @Test
    fun `failure payload returns null`() {
        val json = JSONObject(
            """{"success":false,"errorCode":"AUTH_EXPIRED","errorMessage":"HTTP 401"}"""
        )
        assertNull(DriveItemMapper.parseFolderList(DriveItemEntity.ROOT_DRIVEWSID, json))
    }

    @Test
    fun `root entity is synthetic and descendable`() {
        val root = DriveItemMapper.rootEntity()
        assertEquals(DriveItemEntity.ROOT_DRIVEWSID, root.drivewsid)
        assertEquals(DriveItemEntity.TYPE_FOLDER, root.type)
        assertEquals(DriveItemEntity.CLOUD_DOCS_ZONE, root.zone)
        assertTrue(root.isContainer)
    }
}
