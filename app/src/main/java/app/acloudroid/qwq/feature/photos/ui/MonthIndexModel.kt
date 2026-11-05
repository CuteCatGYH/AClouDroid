package app.acloudroid.qwq.feature.photos.ui

import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity

/**
 * One year in the gallery jump panel, with a 12-slot availability mask.
 *
 * The panel shows every year that has at least one photo (never an empty year), and under the
 * selected year a fixed 3x4 grid of months. Months without photos stay in the grid — greyed out
 * and unclickable — because a grid whose cells move around as the library fills in is impossible
 * to aim at.
 *
 * Kept as plain data + pure functions (no Compose, no Room) so the grouping and stepping rules can
 * be unit tested; they are the part most likely to be silently wrong.
 */
data class YearSection(
    val year: Int,
    /** Length 12. Index `i` corresponds to month `i + 1`; `false` means "no photos that month". */
    val available: BooleanArray
) {
    /** `yyyyMM` of the newest month in this year that has photos; 0 when the year is empty. */
    val newestYearMonth: Int
        get() = available.indices
            .lastOrNull { available[it] }
            ?.let { year * 100 + (it + 1) }
            ?: 0

    /** `yyyyMM` of this year's month [month] (1-based) when it has photos, else null. */
    fun monthKeyOrNull(month: Int): Int? =
        if (month in 1..12 && available[month - 1]) year * 100 + month else null

    // Arrays need explicit equals/hashCode for `data class` semantics to be meaningful.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YearSection) return false
        return year == other.year && available.contentEquals(other.available)
    }

    override fun hashCode(): Int = 31 * year + available.contentHashCode()
}

/**
 * Derivations for the right-edge year/month jump panel.
 *
 * ## Ordering
 *
 * [yearSections] **preserves the input order**. The caller feeds
 * `PhotoMonthIndexDao.observeAll()`, which is `ORDER BY yearMonth DESC`, so the result is years
 * from the highest down to the lowest — the same direction the gallery scrolls. The panel renders
 * them top-to-bottom in that order, so scrolling down moves down the year list too.
 */
object MonthIndexModel {

    /**
     * Groups a month index (newest first) into per-year availability masks.
     *
     * [MonthBucketing.UNBUCKETED] (`0`) is dropped: it is not a real month — it is the bucket for
     * orphaned master records that carry no `assetDate` (ISS-024) — and rendering it as a "year"
     * would produce a tile that can never be jumped to.
     */
    fun yearSections(months: List<PhotoMonthIndexEntity>): List<YearSection> {
        if (months.isEmpty()) return emptyList()

        val order = ArrayList<Int>()
        val masks = HashMap<Int, BooleanArray>()

        for (month in months) {
            if (month.yearMonth <= MonthBucketing.UNBUCKETED) continue
            val year = MonthBucketing.yearOf(month.yearMonth)
            val monthOfYear = MonthBucketing.monthOf(month.yearMonth)
            if (monthOfYear !in 1..12) continue

            val mask = masks.getOrPut(year) {
                order.add(year)
                BooleanArray(12)
            }
            mask[monthOfYear - 1] = true
        }

        return order.map { year -> YearSection(year = year, available = masks.getValue(year)) }
    }

    /**
     * Steps [delta] positions from [current] through the months that actually contain photos.
     *
     * `delta = -1` moves to the *newer* neighbouring month, `+1` to the older one, matching the
     * `months` list order (newest first). Returns null at either end of the library, which the UI
     * turns into a disabled arrow.
     *
     * Note this deliberately steps over gaps: a library with photos in January and March but not
     * February must move `202601 -> 202603`, not to a month that renders nothing.
     */
    fun stepMonth(months: List<PhotoMonthIndexEntity>, current: Int, delta: Int): Int? {
        if (months.isEmpty()) return null
        val index = months.indexOfFirst { it.yearMonth == current }
        if (index < 0) return null
        val target = index + delta
        if (target !in months.indices) return null
        return months[target].yearMonth
    }

    /** Resolves `year` + [month] (1-based) to a `yyyyMM`, or null when that month has no photos. */
    fun resolveMonth(sections: List<YearSection>, year: Int, month: Int): Int? =
        sections.firstOrNull { it.year == year }?.monthKeyOrNull(month)
}
