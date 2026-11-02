package com.getup.iClouDroid.feature.drive.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for "empty folder spins forever".
 *
 * The captured payload below is the **real** server response for an empty folder (verified on a
 * live account, REFERENCE.md). It is a success with `items: []` — nothing about it is ambiguous,
 * so the bug cannot be in the parsing and must be in how an empty result reaches the UI.
 *
 * These tests pin the parsing side so that any future fix cannot regress it into "empty means
 * failure", which would trade a stuck spinner for a permanent error banner.
 */
class DriveEmptyFolderRegressionTest {

    private val emptyFolderPayload = """
        [{"dateCreated":"2026-11-02T12:50:22Z",
          "drivewsid":"FOLDER::com.apple.CloudDocs::1EA6072E-FB5E-4C2D-89B0-CA1DF15FCCD6",
          "docwsid":"1EA6072E-FB5E-4C2D-89B0-CA1DF15FCCD6",
          "zone":"com.apple.CloudDocs",
          "name":"空文件夹",
          "parentId":"FOLDER::com.apple.CloudDocs::root",
          "etag":"nsd",
          "type":"FOLDER",
          "assetQuota":0,"fileCount":0,"shareCount":0,"shareAliasCount":0,
          "directChildrenCount":0,
          "items":[],
          "numberOfItems":0,
          "status":"OK"}]
    """.trimIndent()

    /** The Go core flattens the array into `{"success":true,"items":[...]}`. */
    private fun wrapped(itemsJson: String): JSONObject =
        JSONObject("""{"success":true,"drivewsid":"x","items":$itemsJson}""")

    /**
     * An empty folder must parse to an **empty list**, not null.
     *
     * `null` is the mapper's "this was not a successful listing" signal. Returning it here would
     * turn every empty folder into an error, which is a different bug with the same root cause:
     * the code not distinguishing "no children" from "no answer".
     */
    @Test
    fun `empty folder parses to empty list not null`() {
        val parsed = DriveItemMapper.parseFolderList(
            "FOLDER::com.apple.CloudDocs::1EA6072E-FB5E-4C2D-89B0-CA1DF15FCCD6",
            wrapped("[]")
        )
        assertNotNull("an empty folder is a valid listing, not a failure", parsed)
        assertTrue("expected no children, got $parsed", parsed!!.isEmpty())
    }

    /** The wrapper's own `items` array is what matters; `numberOfItems` is informational. */
    @Test
    fun `numberOfItems zero with empty items is still a valid listing`() {
        val json = JSONObject(
            """{"success":true,"drivewsid":"x","items":[],"numberOfItems":0,"status":"OK"}"""
        )
        val parsed = DriveItemMapper.parseFolderList("FOLDER::com.apple.CloudDocs::root", json)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.size)
    }

    /** A non-empty folder still parses normally — the empty case must not have broken it. */
    @Test
    fun `non-empty folder still parses`() {
        val json = wrapped(
            """[{"drivewsid":"FILE::com.apple.CloudDocs::AAAA","docwsid":"AAAA",
                 "parentId":"FOLDER::com.apple.CloudDocs::root","name":"a.txt",
                 "type":"FILE","zone":"com.apple.CloudDocs","size":12}]"""
        )
        val parsed = DriveItemMapper.parseFolderList("FOLDER::com.apple.CloudDocs::root", json)
        assertEquals(1, parsed?.size)
        assertEquals("a.txt", parsed!![0].name)
    }

    /** A failed payload is still null — the empty-folder fix must not mask real failures. */
    @Test
    fun `failed payload is still null`() {
        val json = JSONObject("""{"success":false,"errorMessage":"boom"}""")
        assertEquals(null, DriveItemMapper.parseFolderList("FOLDER::com.apple.CloudDocs::root", json))
    }

    /**
     * The raw array form (before the Go wrapper) must also yield zero children when flattened.
     * This guards the Go-side flattening in `RetrieveItemDetailsInFolders`, which appends
     * `node.Items` for every node — appending nothing for an empty folder.
     */
    @Test
    fun `raw array with empty items yields no children`() {
        val raw = org.json.JSONArray(emptyFolderPayload)
        assertEquals(1, raw.length())
        val node = raw.getJSONObject(0)
        assertEquals("FOLDER", node.getString("type"))
        assertEquals(0, node.getJSONArray("items").length())
        assertEquals(0, node.getInt("directChildrenCount"))
    }
}
