package app.acloudroid.qwq.feature.photos.data

import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure functions for day-level bucketing inside month sections.
 *
 * ## Rationale (ISS-027 & AGENTS §6.4)
 *
 * The cloud gallery stores and paginates assets using [MonthBucketing] (yyyyMM) as the stable
 * root partition. This object handles the sub-division of a single month's photo stream into
 * day groups (yyyyMMdd) for the Day View, ensuring:
 *  1. Zero alteration to Room schema, scanning cursor, or reconciliation logic.
 *  2. Pure calculation in the device's local timezone (preventing midnight drifting).
 *  3. Human-friendly labels ("今天", "昨天", "前天", "3月28日", "2025年11月14日").
 */
object DayBucketing {

    const val UNBUCKETED = 0

    /**
     * Group of photos belonging to a single calendar day.
     */
    data class DayGroup(
        val dayKey: Int,
        val label: String,
        val photos: List<PhotoAssetEntity>
    )

    /**
     * Converts an epoch-millis timestamp to an integer day key: `yyyyMMdd`.
     *
     * Example: 1774684800000 -> 20260328
     */
    fun dayKeyOf(assetDateMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
        if (assetDateMs <= 0L) return UNBUCKETED
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = assetDateMs
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return year * 10000 + month * 100 + day
    }

    /** Extracts year from `yyyyMMdd`. */
    fun yearOf(dayKey: Int): Int = if (dayKey <= 0) 0 else dayKey / 10000

    /** Extracts month from `yyyyMMdd` (1..12). */
    fun monthOf(dayKey: Int): Int = if (dayKey <= 0) 0 else (dayKey % 10000) / 100

    /** Extracts day from `yyyyMMdd` (1..31). */
    fun dayOfMonth(dayKey: Int): Int = if (dayKey <= 0) 0 else dayKey % 100

    /**
     * Human-friendly label for a day key relative to `nowMs`.
     *
     *  - Same day as now -> "今天"
     *  - 1 day ago      -> "昨天"
     *  - 2 days ago     -> "前天"
     *  - Same year      -> "M月d日" (e.g. "3月28日")
     *  - Different year -> "yyyy年M月d日" (e.g. "2025年11月14日")
     */
    fun labelOf(
        dayKey: Int,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        if (dayKey <= 0) return "未分组"

        val todayKey = dayKeyOf(nowMs, timeZone)
        if (dayKey == todayKey) return "今天"

        // Check yesterday and day before yesterday
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = nowMs

        cal.add(Calendar.DAY_OF_MONTH, -1)
        val yesterdayKey = dayKeyOf(cal.timeInMillis, timeZone)
        if (dayKey == yesterdayKey) return "昨天"

        cal.add(Calendar.DAY_OF_MONTH, -1)
        val dayBeforeYesterdayKey = dayKeyOf(cal.timeInMillis, timeZone)
        if (dayKey == dayBeforeYesterdayKey) return "前天"

        val currentYear = cal.get(Calendar.YEAR)
        val targetYear = yearOf(dayKey)
        val targetMonth = monthOf(dayKey)
        val targetDay = dayOfMonth(dayKey)

        return if (targetYear == currentYear) {
            "${targetMonth}月${targetDay}日"
        } else {
            "${targetYear}年${targetMonth}月${targetDay}日"
        }
    }

    /**
     * Splits an ordered list of month photos (ordered by assetDate DESC) into day groups.
     */
    fun groupPhotosByDay(
        photos: List<PhotoAssetEntity>,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<DayGroup> {
        if (photos.isEmpty()) return emptyList()

        val result = mutableListOf<DayGroup>()
        var currentDayKey = -1
        var currentPhotos = mutableListOf<PhotoAssetEntity>()

        for (photo in photos) {
            val key = dayKeyOf(photo.assetDate, timeZone)
            if (key != currentDayKey) {
                if (currentPhotos.isNotEmpty()) {
                    result.add(
                        DayGroup(
                            dayKey = currentDayKey,
                            label = labelOf(currentDayKey, nowMs, timeZone),
                            photos = currentPhotos
                        )
                    )
                }
                currentDayKey = key
                currentPhotos = mutableListOf(photo)
            } else {
                currentPhotos.add(photo)
            }
        }

        if (currentPhotos.isNotEmpty()) {
            result.add(
                DayGroup(
                    dayKey = currentDayKey,
                    label = labelOf(currentDayKey, nowMs, timeZone),
                    photos = currentPhotos
                )
            )
        }

        return result
    }
}
