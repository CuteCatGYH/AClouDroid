package app.acloudroid.qwq.data

import app.acloudroid.qwq.data.settings.ScanCadence
import app.acloudroid.qwq.data.settings.ScanFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Guards the "when does the next automatic scan run" arithmetic.
 *
 * Two things go wrong silently here if untested: a negative initial delay (WorkManager clamps it to
 * "run right now", so a daily scan would fire mid-afternoon) and an off-by-one day at the boundary.
 */
class ScanCadenceTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `before 3am schedules the same day`() {
        val now = at(2026, 5, 10, 1, 30)
        val delay = ScanCadence.delayUntilPreferredHour(now, 3, utc)

        // 01:30 -> 03:00 is 90 minutes.
        assertEquals(TimeUnit.MINUTES.toMillis(90), delay)
    }

    @Test
    fun `after 3am schedules the next day`() {
        val now = at(2026, 5, 10, 14, 0)
        val delay = ScanCadence.delayUntilPreferredHour(now, 3, utc)

        // 14:00 -> next 03:00 is 13 hours.
        assertEquals(TimeUnit.HOURS.toMillis(13), delay)
    }

    /** Exactly at the target hour must roll over, never produce a zero/negative delay. */
    @Test
    fun `exactly at 3am rolls to tomorrow`() {
        val now = at(2026, 5, 10, 3, 0)
        val delay = ScanCadence.delayUntilPreferredHour(now, 3, utc)

        assertEquals(TimeUnit.DAYS.toMillis(1), delay)
    }

    @Test
    fun `delay is never negative`() {
        listOf(0, 1, 2, 3, 4, 12, 23).forEach { hour ->
            val now = at(2026, 5, 10, hour, 59)
            assert(ScanCadence.delayUntilPreferredHour(now, 3, utc) > 0) {
                "delay must be positive at hour=$hour"
            }
        }
    }

    @Test
    fun `fixed frequencies map to day counts`() {
        assertEquals(1L, ScanCadence.periodDays(ScanFrequency.DAILY, 14))
        assertEquals(7L, ScanCadence.periodDays(ScanFrequency.WEEKLY, 14))
        assertEquals(30L, ScanCadence.periodDays(ScanFrequency.MONTHLY, 14))
        assertEquals(14L, ScanCadence.periodDays(ScanFrequency.CUSTOM, 14))
    }

    @Test
    fun `off has no period`() {
        assertNull(ScanCadence.periodDays(ScanFrequency.OFF, 14))
        assertNull(ScanCadence.periodMillis(ScanFrequency.OFF, 14))
    }

    /** A zero interval would create a self-retriggering work request. */
    @Test
    fun `custom period floors at one day`() {
        assertEquals(1L, ScanCadence.periodDays(ScanFrequency.CUSTOM, 0))
        assertEquals(1L, ScanCadence.periodDays(ScanFrequency.CUSTOM, -5))
    }

    @Test
    fun `period millis matches days`() {
        assertEquals(
            TimeUnit.DAYS.toMillis(7),
            ScanCadence.periodMillis(ScanFrequency.WEEKLY, 14)
        )
    }
}
