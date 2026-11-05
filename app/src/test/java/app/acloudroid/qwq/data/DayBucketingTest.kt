package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.DayBucketing
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DayBucketingTest {

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
        zone: TimeZone = shanghai
    ): Long {
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        return cal.timeInMillis
    }

    @Test
    fun `formats as yyyyMMdd`() {
        val result = DayBucketing.dayKeyOf(millis(2026, 3, 28, 14, 30), shanghai)
        assertEquals(20260328, result)
        assertEquals(2026, DayBucketing.yearOf(result))
        assertEquals(3, DayBucketing.monthOf(result))
        assertEquals(28, DayBucketing.dayOfMonth(result))
    }

    @Test
    fun `handles unbucketed timestamps`() {
        assertEquals(DayBucketing.UNBUCKETED, DayBucketing.dayKeyOf(0L, shanghai))
        assertEquals(DayBucketing.UNBUCKETED, DayBucketing.dayKeyOf(-100L, shanghai))
        assertEquals("未分组", DayBucketing.labelOf(DayBucketing.UNBUCKETED))
    }

    @Test
    fun `relative labels today yesterday day before yesterday`() {
        val now = millis(2026, 3, 28, 18, 0, shanghai)
        val todayKey = DayBucketing.dayKeyOf(now, shanghai)
        val yesterdayKey = DayBucketing.dayKeyOf(millis(2026, 3, 27, 9, 0, shanghai), shanghai)
        val dayBeforeKey = DayBucketing.dayKeyOf(millis(2026, 3, 26, 22, 0, shanghai), shanghai)
        val olderKeySameYear = DayBucketing.dayKeyOf(millis(2026, 3, 20, 10, 0, shanghai), shanghai)
        val olderKeyPrevYear = DayBucketing.dayKeyOf(millis(2025, 12, 15, 10, 0, shanghai), shanghai)

        assertEquals("今天", DayBucketing.labelOf(todayKey, now, shanghai))
        assertEquals("昨天", DayBucketing.labelOf(yesterdayKey, now, shanghai))
        assertEquals("前天", DayBucketing.labelOf(dayBeforeKey, now, shanghai))
        assertEquals("3月20日", DayBucketing.labelOf(olderKeySameYear, now, shanghai))
        assertEquals("2025年12月15日", DayBucketing.labelOf(olderKeyPrevYear, now, shanghai))
    }

    @Test
    fun `groups photos correctly by day`() {
        val now = millis(2026, 3, 28, 18, 0, shanghai)
        val p1 = PhotoAssetEntity(id = "1", masterId = "m1", filename = "1.jpg", assetDate = millis(2026, 3, 28, 10, 0), addedDate = 0, isFavorite = false, isHidden = false)
        val p2 = PhotoAssetEntity(id = "2", masterId = "m2", filename = "2.jpg", assetDate = millis(2026, 3, 28, 9, 0), addedDate = 0, isFavorite = false, isHidden = false)
        val p3 = PhotoAssetEntity(id = "3", masterId = "m3", filename = "3.jpg", assetDate = millis(2026, 3, 27, 14, 0), addedDate = 0, isFavorite = false, isHidden = false)

        val groups = DayBucketing.groupPhotosByDay(listOf(p1, p2, p3), now, shanghai)
        assertEquals(2, groups.size)
        assertEquals(20260328, groups[0].dayKey)
        assertEquals("今天", groups[0].label)
        assertEquals(2, groups[0].photos.size)

        assertEquals(20260327, groups[1].dayKey)
        assertEquals("昨天", groups[1].label)
        assertEquals(1, groups[1].photos.size)
    }
}
