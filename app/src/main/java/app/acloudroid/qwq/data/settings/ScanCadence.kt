package app.acloudroid.qwq.data.settings

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Turns a [ScanFrequency] into the concrete numbers WorkManager needs.
 *
 * Kept as pure functions (no Android types, no WorkManager) purely so they can be unit tested:
 * "when does the job run next" is exactly the kind of arithmetic that silently drifts by a day
 * across month boundaries or DST changes.
 */
object ScanCadence {

    /** The quiet hour every scheduled walk aims for. */
    const val PREFERRED_HOUR = 3

    /**
     * Initial delay before the first scheduled walk.
     *
     * The user asked for idle-time runs (03:00). Rather than scheduling a repeating job anchored
     * at install time — which would fire at, say, 14:37 every day — we delay the *first* run until
     * the next 03:00 and then let the interval line up from there.
     *
     * @param now epoch millis "now", injected so tests can pin the clock.
     */
    fun delayUntilPreferredHour(
        now: Long,
        hour: Int = PREFERRED_HOUR,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): Long {
        val next = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If 03:00 has already passed today, aim for tomorrow instead of scheduling a
            // negative delay (which WorkManager would clamp to "run immediately").
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now
    }

    /** Interval between walks, in days; `null` when periodic scanning is off. */
    fun periodDays(frequency: ScanFrequency, customDays: Int): Long? = when (frequency) {
        ScanFrequency.OFF -> null
        ScanFrequency.DAILY -> 1L
        ScanFrequency.WEEKLY -> 7L
        ScanFrequency.MONTHLY -> 30L
        ScanFrequency.CUSTOM -> customDays.coerceAtLeast(1).toLong()
    }

    /** Same as [periodDays], expressed as the millisecond interval WorkManager wants. */
    fun periodMillis(frequency: ScanFrequency, customDays: Int): Long? =
        periodDays(frequency, customDays)?.let { TimeUnit.DAYS.toMillis(it) }
}
