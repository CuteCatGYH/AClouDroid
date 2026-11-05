package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per month that has at least one photo (P2-016).
 *
 * This is the index the gallery actually scrolls over: the `LazyColumn` is built from these rows
 * (a few hundred at most) rather than from the tens of thousands of photo rows, and each month's
 * contents are subscribed separately. It also supplies the labels for the year/month jump bar
 * without touching `photo_assets` at all.
 *
 * The index is derived data — it can always be rebuilt from `photo_assets` — so keeping it in sync
 * is a cheap `SELECT ... GROUP BY yearMonth` after each scan batch rather than a source of truth.
 */
@Entity(tableName = "photo_month_index")
data class PhotoMonthIndexEntity(
    /** `yyyyMM`, e.g. `202508`. Matches [PhotoAssetEntity.yearMonth] exactly. */
    @PrimaryKey
    val yearMonth: Int,
    /** Number of non-hidden assets in this month. */
    val itemCount: Int,
    /** Newest `assetDate` in the month — used to order and to label edges. */
    val firstAssetDate: Long,
    /** Oldest `assetDate` in the month. */
    val lastAssetDate: Long,
    /**
     * True when the scanner has seen every asset that belongs to this month.
     *
     * A month that is not complete is still rendered; it simply may gain more photos later. When
     * the whole library scan finishes, every month it covers becomes complete.
     */
    val isComplete: Boolean = false,

    /**
     * `id` of the asset that represents this month in the jump card — the newest one
     * (`assetDate DESC` first).
     *
     * Stores the record id **only, never a URL**: a `downloadURL` signature dies after ~15 minutes
     * (AGENTS §4.4), so a stored URL would almost always be stale by the time the card renders.
     * The UI resolves the id against `photo_assets` and then through Coil's
     * `icloud_{recordName}_thumb` key, which the grid has usually already populated.
     *
     * Null means "no cover to show", for any of:
     *  - the row predates schema v8 and has not been re-scanned yet,
     *  - the month was scanned while the user had cover previews switched off,
     *  - every asset in the month was hidden.
     */
    val coverAssetId: String? = null
)
