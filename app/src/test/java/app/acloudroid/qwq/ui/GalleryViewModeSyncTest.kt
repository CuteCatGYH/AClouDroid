package app.acloudroid.qwq.ui

import app.acloudroid.qwq.feature.photos.data.DayBucketing
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity
import app.acloudroid.qwq.feature.photos.ui.GalleryViewModeSync
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryViewModeSyncTest {

    @Test
    fun testRowsFor() {
        assertEquals(0, GalleryViewModeSync.rowsFor(0, 5))
        assertEquals(1, GalleryViewModeSync.rowsFor(1, 5))
        assertEquals(1, GalleryViewModeSync.rowsFor(5, 5))
        assertEquals(2, GalleryViewModeSync.rowsFor(6, 5))
        assertEquals(3, GalleryViewModeSync.rowsFor(11, 5))

        assertEquals(1, GalleryViewModeSync.rowsFor(1, 3))
        assertEquals(1, GalleryViewModeSync.rowsFor(3, 3))
        assertEquals(2, GalleryViewModeSync.rowsFor(4, 3))
    }

    @Test
    fun testListIndexOfMonthView() {
        // Given 3 months:
        // 202610: 10 items -> 1 header + 2 rows (5 cols) = 3 items total (indices 0..2)
        // 202609: 6 items -> 1 header + 2 rows (5 cols) = 3 items total (indices 3..5)
        // 202608: 15 items -> 1 header + 3 rows (5 cols) = 4 items total (indices 6..9)
        val months = listOf(
            PhotoMonthIndexEntity(yearMonth = 202610, itemCount = 10, firstAssetDate = 1000L, lastAssetDate = 500L),
            PhotoMonthIndexEntity(yearMonth = 202609, itemCount = 6, firstAssetDate = 1000L, lastAssetDate = 500L),
            PhotoMonthIndexEntity(yearMonth = 202608, itemCount = 15, firstAssetDate = 1000L, lastAssetDate = 500L)
        )

        // Target: start of 202610 -> index 0 (header)
        assertEquals(0, GalleryViewModeSync.listIndexOfMonthView(months, 5, 202610, 0))
        // Target: photo #6 in 202610 -> row 1 -> index 0 + 1 + 1 = 2
        assertEquals(2, GalleryViewModeSync.listIndexOfMonthView(months, 5, 202610, 6))

        // Target: start of 202609 -> index 3 (header)
        assertEquals(3, GalleryViewModeSync.listIndexOfMonthView(months, 5, 202609, 0))
        // Target: photo #4 in 202609 -> row 0 -> index 3 + 1 + 0 = 4
        assertEquals(4, GalleryViewModeSync.listIndexOfMonthView(months, 5, 202609, 4))

        // Target: start of 202608 -> index 6 (header)
        assertEquals(6, GalleryViewModeSync.listIndexOfMonthView(months, 5, 202608, 0))
    }

    @Test
    fun testListIndexOfDayView() {
        val months = listOf(
            PhotoMonthIndexEntity(yearMonth = 202610, itemCount = 9, firstAssetDate = 1000L, lastAssetDate = 500L),
            PhotoMonthIndexEntity(yearMonth = 202609, itemCount = 6, firstAssetDate = 1000L, lastAssetDate = 500L)
        )

        fun createPhoto(id: String, ym: Int) = PhotoAssetEntity(
            id = id,
            masterId = "m_$id",
            filename = "$id.jpg",
            assetDate = 1000L,
            addedDate = 1000L,
            isFavorite = false,
            isHidden = false,
            yearMonth = ym
        )

        val p1 = createPhoto("1", 202610)
        val p2 = createPhoto("2", 202610)

        val days202610 = listOf(
            DayBucketing.DayGroup(dayKey = 20261001, label = "10月1日", photos = listOf(p1, p2)), // 1 header + 1 row = 2 items
            DayBucketing.DayGroup(dayKey = 20261002, label = "10月2日", photos = listOf(p1))     // 1 header + 1 row = 2 items
        )

        val days202609 = listOf(
            DayBucketing.DayGroup(dayKey = 20260930, label = "9月30日", photos = listOf(p1, p2, p1, p2)) // 1 header + 2 rows = 3 items
        )

        val monthDaysMap = mapOf(
            202610 to days202610,
            202609 to days202609
        )

        // Start of 202610 -> index 0
        assertEquals(0, GalleryViewModeSync.listIndexOfDayView(months, monthDaysMap, 3, 202610, null))
        // Day 20261002 -> index 2 (after 20261001 header + 1 row)
        assertEquals(2, GalleryViewModeSync.listIndexOfDayView(months, monthDaysMap, 3, 202610, 20261002))

        // Start of 202609 -> index 4 (after 202610's 4 items)
        assertEquals(4, GalleryViewModeSync.listIndexOfDayView(months, monthDaysMap, 3, 202609, null))
        // Day 20260930 -> index 4
        assertEquals(4, GalleryViewModeSync.listIndexOfDayView(months, monthDaysMap, 3, 202609, 20260930))
    }

    @Test
    fun testPinchColumnChangeAnchorPreservation() {
        val months = listOf(
            PhotoMonthIndexEntity(yearMonth = 202512, itemCount = 40, firstAssetDate = 1000L, lastAssetDate = 500L),
            PhotoMonthIndexEntity(yearMonth = 202511, itemCount = 30, firstAssetDate = 1000L, lastAssetDate = 500L),
            PhotoMonthIndexEntity(yearMonth = 202510, itemCount = 50, firstAssetDate = 1000L, lastAssetDate = 500L)
        )

        // Scenario: User is viewing photo #15 in month 202511
        // With 3 columns:
        // 202512: 1 header + ceil(40/3)=14 rows = 15 items (indices 0..14)
        // 202511: header = index 15. photo #15 is in row 15/3 = 5 -> index 15 + 1 + 5 = 21
        val idx3Cols = GalleryViewModeSync.listIndexOfMonthView(months, 3, 202511, 15)
        assertEquals(21, idx3Cols)

        // When pinching to 6 columns:
        // 202512: 1 header + ceil(40/6)=7 rows = 8 items (indices 0..7)
        // 202511: header = index 8. photo #15 is in row 15/6 = 2 -> index 8 + 1 + 2 = 11
        val idx6Cols = GalleryViewModeSync.listIndexOfMonthView(months, 6, 202511, 15)
        assertEquals(11, idx6Cols)

        // Verify that in both column layouts, the target index still maps to the exact same month (202511)
        // rather than jumping to 202512 or 202510!
        val monthAt3Cols = if (idx3Cols in 0..14) 202512 else if (idx3Cols in 15..25) 202511 else 202510
        val monthAt6Cols = if (idx6Cols in 0..7) 202512 else if (idx6Cols in 8..13) 202511 else 202510

        assertEquals(202511, monthAt3Cols)
        assertEquals(202511, monthAt6Cols)
    }
}
