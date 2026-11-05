package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photo_assets",
    indices = [
        Index(value = ["assetDate"]),
        Index(value = ["masterId"]),
        Index(value = ["isHidden"]),
        // P2-012: cover the paging query's exact ORDER BY so the stable sort stays cheap.
        Index(value = ["isHidden", "assetDate", "id"]),
        // P2-016: the month-section queries filter on yearMonth and order inside the month.
        Index(value = ["yearMonth", "assetDate", "id"])
    ]
)
data class PhotoAssetEntity(
    @PrimaryKey
    val id: String,
    val masterId: String,
    val filename: String,
    val assetDate: Long,
    val addedDate: Long,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val duration: Long = 0,
    val mediaType: String = "IMAGE", // "IMAGE", "VIDEO", "LIVE_PHOTO"
    val itemType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val thumbUrl: String? = null,
    val medUrl: String? = null,
    val originalUrl: String? = null,
    val videoComplUrl: String? = null,
    val recordChangeTag: String? = null,
    val urlFetchedAt: Long = System.currentTimeMillis(),
    /**
     * Go core session generation at the time these URLs were minted.
     *
     * Measured behaviour (ISS-026): a rebuilt Go session does **not** invalidate previously
     * issued CDN signatures — they stay valid until they age out (~15-18 min). The generation is
     * therefore only a hint for the account-switch case, not a hard expiry signal.
     */
    val sessionGeneration: Int = 0,

    /**
     * Month bucket key, formatted `yyyyMM` (e.g. `202508`), computed from [assetDate] in the
     * **device's local timezone**.
     *
     * P2-016: this is the scroll anchor. The gallery groups photos into month sections and keys
     * the lazy list by month, so position survives any list rebuild — an integer index does not,
     * and invalidating itself on every APPEND is exactly what made the old grid jump to the top.
     *
     * `0` means "not yet bucketed". That happens for rows written before the v4 migration and
     * for rows the scanner has not reached yet; [MonthScanEngine] backfills them.
     */
    val yearMonth: Int = 0,

    /**
     * Resolved EXIF/GPS for this asset, as the flat JSON emitted by `icloudcore.FetchPhotoMetadata`.
     *
     * Three states, all meaningful:
     *  - `null` — never fetched. The info panel loads it on demand.
     *  - `""`   — fetched, and the record genuinely has no `mediaMetaDataEnc` (third-party imports,
     *    some screenshots). Cached as a negative result so the panel never asks twice.
     *  - JSON   — parsed metadata; absent fields are absent from the JSON too, never zero-filled.
     *
     * Deliberately NOT part of the scan path: `mediaMetaDataEnc` runs 742–3859 bytes per record,
     * which would add over a hundred megabytes across a full 15k-item `/records/query` walk.
     */
    val exifJson: String? = null,

    /**
     * When [exifJson] was written (0 = never).
     *
     * Doubles as the "is this a cached miss?" marker, so [PhotoRepository.fetchMetadata] can
     * answer from the database without a network round trip.
     */
    val exifFetchedAt: Long = 0
)
