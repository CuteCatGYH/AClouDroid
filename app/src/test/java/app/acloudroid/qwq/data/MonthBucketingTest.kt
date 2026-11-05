package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Guards the month bucketing used by the gallery sections (P2-016).
 *
 * A wrong bucket is visible to the user as "my March photo is in February", and it is easy to get
 * wrong in two ways: timezone handling and the missing-date sentinel.
 */
class MonthBucketingTest {

    /** Builds an epoch-millis value for a wall-clock time in an explicit timezone. */
    private fun millis(
        year: Int,
        month: Int, // 1-based, matching the bucket format
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
        zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    ): Long {
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        return cal.timeInMillis
    }

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `formats as yyyyMM`() {
        val result = MonthBucketing.yearMonthOf(millis(2026, 8, 15), shanghai)
        assertEquals(202608, result)
    }

    @Test
    fun `single digit months are zero padded`() {
        assertEquals(202501, MonthBucketing.yearMonthOf(millis(2025, 1, 5), shanghai))
        assertEquals(202509, MonthBucketing.yearMonthOf(millis(2025, 9, 5), shanghai))
    }

    @Test
    fun `december buckets to the same year`() {
        assertEquals(202512, MonthBucketing.yearMonthOf(millis(2025, 12, 31), shanghai))
    }

    @Test
    fun `january first buckets to the new year`() {
        assertEquals(202601, MonthBucketing.yearMonthOf(millis(2026, 1, 1), shanghai))
    }

    @Test
    fun `late evening stays in the local month`() {
        // 23:30 on 31 January must be January, not February. Using the device zone is what makes
        // this line up with the iCloud web gallery the same user sees in their browser.
        val result = MonthBucketing.yearMonthOf(millis(2026, 1, 31, hour = 23, minute = 30), shanghai)
        assertEquals(202601, result)
    }

    @Test
    fun `just after midnight moves to the next month and year`() {
        val result = MonthBucketing.yearMonthOf(millis(2026, 1, 1, hour = 0, minute = 5), shanghai)
        assertEquals(202601, result)
    }

    @Test
    fun `timezone changes which month the same instant falls in`() {
        // 2026-02-01 00:30 Shanghai is 2026-01-31 16:30 UTC — the same instant belongs to a
        // different month depending on the zone. We bucket by local time, so this is expected.
        val instant = millis(2026, 2, 1, hour = 0, minute = 30, zone = shanghai)
        assertEquals(202602, MonthBucketing.yearMonthOf(instant, shanghai))
        assertEquals(202601, MonthBucketing.yearMonthOf(instant, utc))
    }

    @Test
    fun `zero and negative timestamps are unbucketed`() {
        // ISS-024: an orphaned master has no assetDate. Stamping it with "now" made it drift
        // through the list on every fetch, so it is deliberately left in a dedicated bucket.
        assertEquals(MonthBucketing.UNBUCKETED, MonthBucketing.yearMonthOf(0L, shanghai))
        assertEquals(MonthBucketing.UNBUCKETED, MonthBucketing.yearMonthOf(-1L, shanghai))
        assertEquals(MonthBucketing.UNBUCKETED, MonthBucketing.yearMonthOf(-8_640_000_000L, shanghai))
    }

    @Test
    fun `year and month accessors`() {
        assertEquals(2026, MonthBucketing.yearOf(202608))
        assertEquals(8, MonthBucketing.monthOf(202608))
        assertEquals(0, MonthBucketing.yearOf(0))
        assertEquals(0, MonthBucketing.monthOf(0))
    }

    @Test
    fun `labels`() {
        assertEquals("2026年8月", MonthBucketing.labelOf(202608))
        assertEquals("2025年12月", MonthBucketing.labelOf(202512))
        assertEquals("2026", MonthBucketing.yearLabelOf(202608))
        assertEquals("未分组", MonthBucketing.labelOf(0))
        assertEquals("未分组", MonthBucketing.yearLabelOf(0))
    }
}
