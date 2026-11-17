package app.acloudroid.qwq.feature.photos.data

import android.content.Context
import app.acloudroid.qwq.R
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure functions for day-level bucketing inside month sections.
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
     */
    fun labelOf(
        dayKey: Int,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        context: Context? = null,
        locale: Locale = context?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
    ): String {
        if (dayKey <= 0) return context?.getString(R.string.month_unorganized) ?: if (locale.language.equals("zh", ignoreCase = true)) "未分组" else "Unorganized"

        val todayKey = dayKeyOf(nowMs, timeZone)
        if (dayKey == todayKey) {
            return context?.getString(R.string.day_today) ?: if (locale.language.equals("zh", ignoreCase = true)) "今天" else "Today"
        }

        // Check yesterday and day before yesterday
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = nowMs

        cal.add(Calendar.DAY_OF_MONTH, -1)
        val yesterdayKey = dayKeyOf(cal.timeInMillis, timeZone)
        if (dayKey == yesterdayKey) {
            return context?.getString(R.string.day_yesterday) ?: if (locale.language.equals("zh", ignoreCase = true)) "昨天" else "Yesterday"
        }

        cal.add(Calendar.DAY_OF_MONTH, -1)
        val dayBeforeYesterdayKey = dayKeyOf(cal.timeInMillis, timeZone)
        if (dayKey == dayBeforeYesterdayKey) {
            return context?.getString(R.string.day_before_yesterday) ?: if (locale.language.equals("zh", ignoreCase = true)) "前天" else "2 days ago"
        }

        val targetCal = Calendar.getInstance(timeZone)
        val targetYear = yearOf(dayKey)
        val targetMonth = monthOf(dayKey)
        val targetDay = dayOfMonth(dayKey)
        targetCal.set(targetYear, targetMonth - 1, targetDay)

        val currentYear = cal.get(Calendar.YEAR)

        if (locale.language.equals("zh", ignoreCase = true)) {
            return if (targetYear == currentYear) {
                "${targetMonth}月${targetDay}日"
            } else {
                "${targetYear}年${targetMonth}月${targetDay}日"
            }
        } else {
            val df = java.text.DateFormat.getDateInstance(
                if (targetYear == currentYear) java.text.DateFormat.MEDIUM else java.text.DateFormat.MEDIUM,
                locale
            )
            return df.format(targetCal.time)
        }
    }

    /**
     * Splits an ordered list of month photos (ordered by assetDate DESC) into day groups.
     */
    fun groupPhotosByDay(
        photos: List<PhotoAssetEntity>,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        context: Context? = null,
        locale: Locale = context?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
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
                            label = labelOf(currentDayKey, nowMs, timeZone, context, locale),
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
                    label = labelOf(currentDayKey, nowMs, timeZone, context, locale),
                    photos = currentPhotos
                )
            )
        }

        return result
    }
}
