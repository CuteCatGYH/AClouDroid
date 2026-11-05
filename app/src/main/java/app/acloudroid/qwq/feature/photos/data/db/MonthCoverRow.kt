package app.acloudroid.qwq.feature.photos.data.db

/**
 * One month plus its cover asset, as read by the gallery's jump card.
 *
 * ## Why a join instead of two queries
 *
 * Opening the jump card renders twelve cells for one year. Reading the index rows and then doing a
 * point lookup per month would be twelve round trips through Room on every open; a single
 * `photo_month_index LEFT JOIN photo_assets` costs one.
 *
 * ## Why the fields are nullable
 *
 * The join is a `LEFT JOIN` on `m.coverAssetId`, so three genuinely different situations all arrive
 * here with nulls, and the caller must tell them apart by *which* columns are null:
 *
 * | `coverAssetId` | `coverThumbUrl` | meaning |
 * | --- | --- | --- |
 * | null | null | month has no photos, or was scanned with covers switched off |
 * | set | null | video without a `thumbUrl`, or URLs not minted yet → draw text, never fall back to `medUrl` |
 * | set | set | drawable cover |
 *
 * Collapsing these into a single nullable URL would lose the difference between "no photos" and
 * "photos, but nothing to show", which the UI renders differently (dimmed vs. text cell).
 */
data class MonthCoverRow(
    /** `yyyyMM`, e.g. `202608`. */
    val yearMonth: Int,
    /** Number of non-hidden assets in the month. */
    val itemCount: Int,
    /** `recordName` of the asset chosen as this month's cover, if any. */
    val coverAssetId: String?,
    /** `masterId` of the cover asset, needed for Coil interceptor to mint fresh URLs on miss. */
    val coverMasterId: String? = null,
    /**
     * The cover asset's `thumbUrl`.
     *
     * Deliberately only `thumbUrl`: a video's `medUrl`/`originalUrl` are the `mp4`/`mov` bytes, and
     * handing those to an image decoder produces a permanent blank cell (AGENTS §6.7.5c).
     */
    val coverThumbUrl: String?,
    /** `IMAGE` / `VIDEO` / `LIVE_PHOTO` of the cover asset. */
    val coverMediaType: String?
)
