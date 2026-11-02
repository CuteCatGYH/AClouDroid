package com.getup.iClouDroid.feature.drive.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One node of the iCloud Drive tree, as returned by `retrieveItemDetailsInFolders`.
 *
 * ## Primary key is `drivewsid`, not `docwsid`
 *
 * `docwsid` is **not unique across the account**: every APP_LIBRARY uses the literal string
 * `"documents"` (verified on a real account — Keynote, GaragePreview, GarageBand, Shortcuts all
 * report `docwsid == "documents"`). Only `drivewsid` (`<TYPE>::<zone>::<docwsid>`) is unique,
 * because it carries the zone that disambiguates them.
 *
 * ## Why `type` and not a `bundleType`
 *
 * The live API has no `bundleType` field at all (D0 finding, REFERENCE.md §5.1). Nodes are
 * `FOLDER` / `FILE` / `APP_LIBRARY`, and only the first two are "real" Drive objects —
 * `APP_LIBRARY` is an app's document container, living in that app's own zone.
 */
@Entity(
    tableName = "drive_items",
    indices = [
        Index(value = ["parentDrivewsid", "type", "name"]),
        Index(value = ["docwsid"]),
        Index(value = ["zone"])
    ]
)
data class DriveItemEntity(
    /** `<TYPE>::<zone>::<docwsid>` — the only globally unique handle. */
    @PrimaryKey val drivewsid: String,
    /** Bare uuid (or the literal `root` / `documents`). Used by download/upload endpoints. */
    val docwsid: String,
    /** Full `drivewsid` of the container, never a bare id (verified: the API rejects short form). */
    val parentDrivewsid: String,
    val name: String,
    /** `FOLDER` | `FILE` | `APP_LIBRARY`. Stored verbatim, never inferred in Kotlin. */
    val type: String,
    /** `com.apple.CloudDocs` for the user's own drive; the app's container id for APP_LIBRARY. */
    val zone: String,
    val size: Long,
    /** ISO8601 UTC from the API. Kept as the raw string; **not** epoch millis like photos. */
    val dateModified: String?,
    val dateChanged: String?,
    val dateCreated: String?,
    val etag: String?,
    /** Extension without the dot, e.g. `mp4`. Empty for folders. */
    val extension: String?,
    /** Only meaningful for FOLDER; 0 elsewhere. */
    val directChildrenCount: Int,
    /** CloudKit-side id (`CKH_…`), present on FOLDER and FILE but not on APP_LIBRARY. */
    val itemId: String?,
    /** Icon URLs for APP_LIBRARY (and occasionally folders). Newline-joined by the mapper. */
    val iconUrl: String?,
    /**
     * When this row was last confirmed against the server.
     *
     * A row can exist locally while its folder was never expanded. `true` means the children of
     * this container have been written at some point; D3's reconciliation uses it to know which
     * subtrees it is allowed to judge.
     */
    val childrenFetched: Boolean,
    /** Local wall-clock millis of the last write, for debugging staleness. */
    val lastSyncedAt: Long
) {
    /** `true` when this node can be descended into. `FILE` cannot. */
    val isContainer: Boolean get() = type == TYPE_FOLDER || type == TYPE_APP_LIBRARY

    companion object {
        const val TYPE_FOLDER = "FOLDER"
        const val TYPE_FILE = "FILE"
        const val TYPE_APP_LIBRARY = "APP_LIBRARY"

        /** The root `drivewsid`. Note the `FOLDER::` and zone prefix — the short form is rejected. */
        const val ROOT_DRIVEWSID = "FOLDER::com.apple.CloudDocs::root"
        const val CLOUD_DOCS_ZONE = "com.apple.CloudDocs"
    }
}
