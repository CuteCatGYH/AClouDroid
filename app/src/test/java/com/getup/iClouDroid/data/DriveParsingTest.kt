package com.getup.iClouDroid.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveParsingTest {

    @Test
    fun `parse successful drive folder list result`() {
        val jsonStr = """
        {
            "success": true,
            "drivewsid": "FOLDER::com.apple.CloudDocs::root",
            "items": [
                {
                    "dateCreated": "2026-04-06T13:25:03Z",
                    "drivewsid": "FILE::com.apple.CloudDocs::2ABAAD23-9AB9-4615-ACB0-428594E87AB2",
                    "docwsid": "2ABAAD23-9AB9-4615-ACB0-428594E87AB2",
                    "zone": "com.apple.CloudDocs",
                    "name": "sample.mp4",
                    "extension": "mp4",
                    "parentId": "FOLDER::com.apple.CloudDocs::root",
                    "item_id": "CKH_sample",
                    "dateModified": "2026-04-06T13:25:03Z",
                    "dateChanged": "2026-04-26T09:59:17Z",
                    "size": 2540192,
                    "etag": "gqp::gm9",
                    "type": "FILE"
                },
                {
                    "dateCreated": "2026-02-05T05:57:15Z",
                    "drivewsid": "FOLDER::com.apple.CloudDocs::D2D2D2D2-D2D2-D2D2-D2D2-D2D2D2D2D2D2",
                    "docwsid": "D2D2D2D2-D2D2-D2D2-D2D2-D2D2D2D2D2D2",
                    "zone": "com.apple.CloudDocs",
                    "name": "Documents",
                    "parentId": "FOLDER::com.apple.CloudDocs::root",
                    "etag": "npq",
                    "type": "FOLDER",
                    "directChildrenCount": 4
                },
                {
                    "dateCreated": "2025-09-07T13:00:14Z",
                    "drivewsid": "FOLDER::iCloud.com.chii.idays.storage::documents",
                    "docwsid": "documents",
                    "zone": "iCloud.com.chii.idays.storage",
                    "name": "Days Matter",
                    "parentId": "FOLDER::com.apple.CloudDocs::root",
                    "etag": "b6",
                    "type": "APP_LIBRARY",
                    "icons": [
                        {
                            "url": "https://p203-drivews.icloud.com.cn/getIcons?id=iCloud.com.chii.idays.storage&field=icon120x120_iOS",
                            "type": "IOS",
                            "size": 120
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val root = JSONObject(jsonStr)
        assertTrue(root.optBoolean("success", false))
        assertEquals("FOLDER::com.apple.CloudDocs::root", root.optString("drivewsid"))

        val items = root.optJSONArray("items")
        assertNotNull(items)
        assertEquals(3, items!!.length())

        // Validate FILE
        val fileObj = items.getJSONObject(0)
        assertEquals("FILE::com.apple.CloudDocs::2ABAAD23-9AB9-4615-ACB0-428594E87AB2", fileObj.getString("drivewsid"))
        assertEquals("2ABAAD23-9AB9-4615-ACB0-428594E87AB2", fileObj.getString("docwsid"))
        assertEquals("sample.mp4", fileObj.getString("name"))
        assertEquals("mp4", fileObj.getString("extension"))
        assertEquals("FILE", fileObj.getString("type"))
        assertEquals(2540192L, fileObj.getLong("size"))
        assertEquals("CKH_sample", fileObj.getString("item_id"))

        // Validate FOLDER
        val folderObj = items.getJSONObject(1)
        assertEquals("FOLDER::com.apple.CloudDocs::D2D2D2D2-D2D2-D2D2-D2D2-D2D2D2D2D2D2", folderObj.getString("drivewsid"))
        assertEquals("Documents", folderObj.getString("name"))
        assertEquals("FOLDER", folderObj.getString("type"))
        assertEquals(4, folderObj.getInt("directChildrenCount"))

        // Validate APP_LIBRARY
        val appLibObj = items.getJSONObject(2)
        assertEquals("FOLDER::iCloud.com.chii.idays.storage::documents", appLibObj.getString("drivewsid"))
        assertEquals("Days Matter", appLibObj.getString("name"))
        assertEquals("APP_LIBRARY", appLibObj.getString("type"))
        val icons = appLibObj.getJSONArray("icons")
        assertEquals(1, icons.length())
        assertEquals(120, icons.getJSONObject(0).getInt("size"))
    }

    @Test
    fun `parse drive error result`() {
        val errorJson = """
        {
            "success": false,
            "errorCode": "AUTH_EXPIRED",
            "httpStatus": 401,
            "errorMessage": "HTTP 401: Unauthorized",
            "drivewsid": "FOLDER::com.apple.CloudDocs::root",
            "items": []
        }
        """.trimIndent()

        val root = JSONObject(errorJson)
        assertFalse(root.optBoolean("success", true))
        assertEquals("AUTH_EXPIRED", root.optString("errorCode"))
        assertEquals(401, root.optInt("httpStatus"))
        assertEquals("HTTP 401: Unauthorized", root.optString("errorMessage"))
    }
}
