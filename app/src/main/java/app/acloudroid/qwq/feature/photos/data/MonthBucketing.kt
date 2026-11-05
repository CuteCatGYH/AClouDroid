package app.acloudroid.qwq.feature.photos.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Month bucketing for the gallery sections (P2-016).
 *
 * Kept as top-level pure functions so they can be unit tested without Room, the Go core or a
 * device — the timezone edge cases are the part most likely to be wrong.
 */
internal object MonthBucketing {

    /** Sentinel for "no date, do not group". Sorts last and never moves (ISS-024). */
    const val UNBUCKETED = 0

    /**
     * Converts an epoch-millis asset date into a `yyyyMM` bucket key.
     *
     * Uses the **device's local timezone**, matching what the iCloud web gallery shows the same
     * user: a photo taken at 23:30 on 31 January shows under January, not February, because the
     * phone and the browser share a zone.
     *
     * Non-positive timestamps return [UNBUCKETED] rather than "today". A photos library can
     * contain orphaned masters with no `CPLAsset` date at all; stamping them with the current
     * time gives the same row a different sort key on every fetch, which made photos drift
     * through the list on each refresh (ISS-024).
     */
    fun yearMonthOf(assetDateMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
        if (assetDateMs <= 0L) return UNBUCKETED
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = assetDateMs
        return cal.get(Calendar.YEAR) * 100 + (cal.get(Calendar.MONTH) + 1)
    }

    /** Extracts the year (`yyyyMM` → `yyyy`); 0 for [UNBUCKETED]. */
    fun yearOf(yearMonth: Int): Int = if (yearMonth <= 0) 0 else yearMonth / 100

    /** Extracts the month (`yyyyMM` → `MM`); 0 for [UNBUCKETED]. */
    fun monthOf(yearMonth: Int): Int = if (yearMonth <= 0) 0 else yearMonth % 100

    /** Label shown in the sticky header, e.g. `2026年8月`. */
    fun labelOf(yearMonth: Int): String {
        if (yearMonth <= 0) return "未分组"
        return "${yearOf(yearMonth)}年${monthOf(yearMonth)}月"
    }

    /** Label shown in the year jump bar, e.g. `2026`. */
    fun yearLabelOf(yearMonth: Int): String {
        if (yearMonth <= 0) return "未分组"
        return yearOf(yearMonth).toString()
    }

    /** Label shown in the month grid of the jump panel, e.g. `8月`. */
    fun monthLabelOf(yearMonth: Int): String {
        if (yearMonth <= 0) return "未分组"
        return "${monthOf(yearMonth)}月"
    }

    /** Label for a bare month number (1..12), used by the grid's placeholder cells. */
    fun monthNumberLabelOf(month: Int): String = "${month}月"
}
