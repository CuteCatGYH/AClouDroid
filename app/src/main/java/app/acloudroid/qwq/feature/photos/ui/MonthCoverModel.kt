package app.acloudroid.qwq.feature.photos.ui

import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import app.acloudroid.qwq.feature.photos.data.db.MonthCoverRow

/**
 * What one cell of the jump card's month grid should render.
 *
 * Three states rather than a nullable image URL, because "no picture" has two very different
 * meanings that must not be collapsed:
 *
 *  - [Unavailable] — the month has no photos at all. Not a failure: a library simply starts at some
 *    point, and a dimmed unclickable cell is the honest way to say so.
 *  - [Placeholder] — the month *does* have photos, but the chosen cover cannot be drawn as an image
 *    (a video whose `thumbUrl` is missing, or a row whose URLs have not been minted yet). Rendered
 *    as a text cell; tapping still works.
 */
sealed interface MonthCoverCell {
    val yearMonth: Int
    val label: String

    /** A cover image is available. */
    data class Cover(
        override val yearMonth: Int,
        override val label: String,
        /** `recordName` of the asset to draw; feeds Coil's `icloud_{id}_thumb` cache key. */
        val coverId: String,
        /** `masterId` of the cover asset, needed for Coil interceptor to mint fresh URLs on miss. */
        val coverMasterId: String = "",
        /**
         * The asset's `thumbUrl`.
         *
         * Carried alongside [coverId] because Coil needs a real data source to resolve a cache
         * key against: an empty or null `data` has no fetcher, so the request fails before the
         * disk cache is ever consulted. The URL itself may well be expired — that does not matter,
         * because the card only ever reads caches and never touches the network.
         */
        val coverUrl: String
    ) : MonthCoverCell

    /** The month has photos but no drawable cover — render text only, still clickable. */
    data class Placeholder(
        override val yearMonth: Int,
        override val label: String
    ) : MonthCoverCell

    /** The month has no photos — dimmed, never clickable. */
    data class Unavailable(
        override val yearMonth: Int,
        override val label: String
    ) : MonthCoverCell
}

/**
 * Builds the twelve cells of the month grid for one year.
 *
 * Kept pure (no Compose, no Room) because the rules below are exactly the ones that are easy to get
 * subtly wrong and impossible to notice on a device:
 *
 * 1. **Calendar order, not newest-first.** The grid reads `1月 … 12月`; the query returns months
 *    `DESC`, so the two disagree by design and the reordering has to happen somewhere explicit.
 * 2. **A video without `thumbUrl` must never become a [MonthCoverCell.Cover].** Falling back to
 *    `medUrl`/`originalUrl` hands Coil an `mp4` while claiming it is a JPEG; the request fails once
 *    and the cell is permanently blank (AGENTS §6.7.5c). A text cell is strictly better.
 * 3. **A month absent from [rows] is [MonthCoverCell.Unavailable], never an empty Cover.** The join
 *    is `LEFT JOIN`, so a missing month and a month with a null URL both arrive as "no URL"; only
 *    the presence of the row itself distinguishes them.
 */
object MonthCoverModel {

    /**
     * @param year the four-digit year whose twelve months are being rendered.
     * @param availableMonths the 12-slot availability mask for [year], from the month index.
     *   **This is the authority on "does this month have photos"**, not [coverRows]: the index is
     *   populated whether or not the user has cover previews switched on, whereas [coverRows] is
     *   empty in that case. Deriving availability from the rows would then mark every month
     *   unavailable and render the whole grid unclickable.
     * @param coverRows cover rows for [year]. Rows belonging to other years are ignored, so a
     *   caller cannot accidentally bleed two years together. Empty is legitimate and simply means
     *   no pictures are available.
     */
    fun cellsFor(
        year: Int,
        availableMonths: BooleanArray?,
        coverRows: List<MonthCoverRow>
    ): List<MonthCoverCell> {
        // Index by month number so each of the twelve lookups is O(1) and the output order is
        // decided by the calendar, not by the query's ORDER BY.
        val byMonth = coverRows
            .filter { MonthBucketing.yearOf(it.yearMonth) == year }
            .associateBy { MonthBucketing.monthOf(it.yearMonth) }

        return (1..12).map { month ->
            val label = MonthBucketing.monthNumberLabelOf(month)
            val yearMonth = if (year > 0) year * 100 + month else month
            // No mask means no year was resolved; treat everything as unavailable, matching the
            // previous behaviour for the `year <= 0` sentinel.
            val hasPhotos = year > 0 && availableMonths?.getOrNull(month - 1) == true
            val row = byMonth[month]

            val isVideo = row?.coverMediaType == "VIDEO" || row?.coverMediaType == "LIVE_PHOTO"

            when {
                !hasPhotos -> MonthCoverCell.Unavailable(yearMonth, label)
                row?.coverAssetId == null -> MonthCoverCell.Placeholder(yearMonth, label)
                isVideo && row.coverThumbUrl.isNullOrBlank() -> MonthCoverCell.Placeholder(yearMonth, label)
                else -> MonthCoverCell.Cover(
                    yearMonth = yearMonth,
                    label = label,
                    coverId = row.coverAssetId,
                    coverMasterId = row.coverMasterId.orEmpty(),
                    coverUrl = if (row.coverThumbUrl.isNullOrBlank()) "" else row.coverThumbUrl
                )
            }
        }
    }
}
