package app.acloudroid.qwq.feature.drive.data

import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translates the JSON that `icloudcore.retrieveItemDetailsInFolders` emits into [DriveItemEntity].
 *
 * Kept as pure functions (no Android, no IO) so the parsing rules can be unit-tested without a
 * device — the same split `PhotoExif` uses for EXIF.
 *
 * ## Two rules worth stating, both learned in D0
 *
 * 1. **`type` is authoritative.** The live API has no `bundleType`; nodes are `FOLDER` / `FILE` /
 *    `APP_LIBRARY`. Nothing here guesses a type from the extension.
 * 2. **`dateCreated` is an ISO8601 string, not epoch millis.** Reusing the photo date parser would
 *    silently produce 0 for every row (which is how `yearMonth == 0` rows became invisible in
 *    Photos — PITFALLS §12). The raw string is stored instead, and formatted at the UI edge.
 */
object DriveItemMapper {

    /**
     * Parses one folder listing.
     *
     * @param parentDrivewsid the container that was queried. Used for every produced row rather
     *   than the payload's own `parentId`, because `APP_LIBRARY` children sometimes omit it and a
     *   row with an empty parent is unreachable from the UI.
     * @return the parsed children, or `null` when the payload is not a successful listing. An
     *   **empty list is a valid result** (a genuinely empty folder) and must not be conflated
     *   with a failure — doing so would make an empty folder look like a broken one.
     */
    fun parseFolderList(parentDrivewsid: String, json: JSONObject): List<DriveItemEntity>? {
        if (!json.optBoolean("success", false)) return null
        val arr = json.optJSONArray("items") ?: JSONArray()
        val now = System.currentTimeMillis()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseItem(obj, parentDrivewsid, now)?.let { add(it) }
            }
        }
    }

    /**
     * Parses the single-node response from `retrieveItemDetails`.
     *
     * That endpoint wraps its payload (`{"items":[{…}]}`) while `retrieveItemDetailsInFolders`
     * returns a bare array — the one shape difference between two otherwise identical calls
     * (REFERENCE.md §5.7). Getting it wrong produces a 400 from the server rather than a parse
     * error here, which is why the wrapper is handled explicitly instead of by a generic helper.
     *
     * @return the node, or null when the payload is not a successful single-item response.
     */
    fun parseSingleItem(json: JSONObject): DriveItemEntity? {
        if (!json.optBoolean("success", false)) return null

        // `retrieveItemDetails` wraps the node in a **singular `item` object**; it does not use the
        // `items` array that `retrieveItemDetailsInFolders` returns. Reading `items` here made
        // every probe return null, which the planner correctly treats as "cannot vouch" and
        // escalates to LIST_STALE — so D1 re-listed all 165 containers on every pass (~2.5 min)
        // and `skipped` was permanently 0. Verified against a real response:
        //   {"success":true,"item":{...,"etag":"o53","directChildrenCount":46}}
        val obj = json.optJSONObject("item")
            // Tolerate the array shape too, so a future server change (or a caller passing folder
            // output by mistake) degrades to "first element" instead of silently returning null.
            ?: json.optJSONArray("items")?.takeIf { it.length() > 0 }?.optJSONObject(0)
            ?: return null

        // No parent fallback: a probe is about the node itself, and inventing a parent here would
        // make an unreachable row look reachable.
        return parseItem(obj, parentDrivewsidFallback = obj.optString("parentId"))
    }

    /** Parses a single node. Returns null when the row could not be addressed (no `drivewsid`). */
    fun parseItem(
        obj: JSONObject,
        parentDrivewsidFallback: String,
        now: Long = System.currentTimeMillis()
    ): DriveItemEntity? {
        val drivewsid = obj.optString("drivewsid").takeIf { it.isNotBlank() } ?: return null

        // A node's own parentId is the *fully qualified* drivewsid, never a bare uuid (verified:
        // the API rejects the short form). Fall back to the container we asked about.
        val parent = obj.optString("parentId").takeIf { it.isNotBlank() }
            ?: parentDrivewsidFallback

        val type = obj.optString("type").takeIf { it.isNotBlank() }
            ?: DriveItemEntity.TYPE_FILE

        return DriveItemEntity(
            drivewsid = drivewsid,
            docwsid = obj.optString("docwsid"),
            parentDrivewsid = parent,
            name = obj.optString("name"),
            type = type,
            zone = obj.optString("zone"),
            size = obj.optLong("size", 0L),
            // Kept verbatim: these are ISO8601 UTC strings (`2026-02-05T05:57:15Z`), NOT epoch
            // millis. Converting here would bake the raw format into the storage layer.
            dateModified = obj.optString("dateModified").takeIf { it.isNotBlank() },
            dateChanged = obj.optString("dateChanged").takeIf { it.isNotBlank() },
            dateCreated = obj.optString("dateCreated").takeIf { it.isNotBlank() },
            etag = obj.optString("etag").takeIf { it.isNotBlank() },
            extension = obj.optString("extension").takeIf { it.isNotBlank() },
            directChildrenCount = obj.optInt("directChildrenCount", 0),
            itemId = obj.optString("item_id").takeIf { it.isNotBlank() },
            iconUrl = firstIconUrl(obj.optJSONArray("icons")),
            // Children are only marked fetched by replaceChildren(); a freshly listed row has not
            // had its own subtree walked yet.
            childrenFetched = false,
            lastSyncedAt = now
        )
    }

    /**
     * Picks the largest available icon.
     *
     * `APP_LIBRARY` nodes carry `icons[]` with several sizes; the API does not guarantee order,
     * so this sorts by size rather than taking `[0]`. Returns null when there is no icon, which
     * the UI renders as a generic folder glyph.
     */
    private fun firstIconUrl(icons: JSONArray?): String? {
        if (icons == null || icons.length() == 0) return null
        var best: String? = null
        var bestSize = -1
        for (i in 0 until icons.length()) {
            val icon = icons.optJSONObject(i) ?: continue
            val url = icon.optString("url").takeIf { it.isNotBlank() } ?: continue
            val size = icon.optInt("size", 0)
            if (size > bestSize) {
                bestSize = size
                best = url
            }
        }
        return best
    }

    /** The synthetic root row, inserted so the UI always has a container to subscribe to. */
    fun rootEntity(now: Long = System.currentTimeMillis()): DriveItemEntity = DriveItemEntity(
        drivewsid = DriveItemEntity.ROOT_DRIVEWSID,
        docwsid = "root",
        parentDrivewsid = "", // the root has no parent; it is the entry point
        name = "Drive",
        type = DriveItemEntity.TYPE_FOLDER,
        zone = DriveItemEntity.CLOUD_DOCS_ZONE,
        size = 0L,
        dateModified = null,
        dateChanged = null,
        dateCreated = null,
        etag = null,
        extension = null,
        directChildrenCount = 0,
        itemId = null,
        iconUrl = null,
        childrenFetched = false,
        lastSyncedAt = now
    )
}
