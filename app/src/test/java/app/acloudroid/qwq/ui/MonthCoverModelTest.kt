package app.acloudroid.qwq.ui

import app.acloudroid.qwq.feature.photos.data.db.MonthCoverRow
import app.acloudroid.qwq.feature.photos.ui.MonthCoverCell
import app.acloudroid.qwq.feature.photos.ui.MonthCoverModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the jump card's month-cell rules.
 *
 * The rules being tested are the ones that are invisible until a user hits them: a grid in the wrong
 * order, a video that renders as a permanently blank square, a month with no photos that looks
 * tappable, or — the one that actually shipped broken once — a fully disabled grid because
 * availability was inferred from an empty cover list. None of these throw, so a unit test is the
 * only cheap way to hold them down.
 */
class MonthCoverModelTest {

    private fun row(
        yearMonth: Int,
        coverId: String? = "cover-$yearMonth",
        masterId: String? = "master-$yearMonth",
        thumbUrl: String? = "https://example.test/$yearMonth.jpg",
        mediaType: String? = "IMAGE",
        itemCount: Int = 5
    ) = MonthCoverRow(
        yearMonth = yearMonth,
        itemCount = itemCount,
        coverAssetId = coverId,
        coverMasterId = masterId,
        coverThumbUrl = thumbUrl,
        coverMediaType = mediaType
    )

    /** Availability mask for [year] derived from [rows] — the common case in these tests. */
    private fun maskOf(year: Int, rows: List<MonthCoverRow>): BooleanArray =
        BooleanArray(12) { i -> rows.any { it.yearMonth == year * 100 + (i + 1) } }

    /** Convenience: mask inferred from the same rows, so the tests read like the old signature. */
    private fun cells(year: Int, rows: List<MonthCoverRow>): List<MonthCoverCell> =
        MonthCoverModel.cellsFor(year, maskOf(year, rows), rows)

    // ---- shape ---------------------------------------------------------------------------

    @Test
    fun `always produces twelve cells in calendar order`() {
        val cells = cells(2026, listOf(row(202601), row(202608), row(202612)))

        assertEquals(12, cells.size)
        assertEquals(
            listOf(202601, 202602, 202603, 202604, 202605, 202606,
                202607, 202608, 202609, 202610, 202611, 202612),
            cells.map { it.yearMonth }
        )
        assertEquals(
            listOf("1月", "2月", "3月", "4月", "5月", "6月",
                "7月", "8月", "9月", "10月", "11月", "12月"),
            cells.map { it.label }
        )
    }

    @Test
    fun `input order does not decide output order`() {
        // The query returns months DESC; the grid must still read 1..12.
        val cells = cells(2026, listOf(row(202612), row(202603)))

        assertEquals(202601, cells.first().yearMonth)
        assertEquals(202612, cells.last().yearMonth)
    }

    @Test
    fun `months without photos are unavailable`() {
        val cells = cells(2026, listOf(row(202603)))

        assertTrue(cells[2] is MonthCoverCell.Cover)
        assertTrue(cells[0] is MonthCoverCell.Unavailable)
        assertTrue(cells[11] is MonthCoverCell.Unavailable)
    }

    @Test
    fun `empty rows with an empty mask yield twelve unavailable cells`() {
        val cells = MonthCoverModel.cellsFor(2026, BooleanArray(12), emptyList())

        assertEquals(12, cells.size)
        assertTrue(cells.all { it is MonthCoverCell.Unavailable })
    }

    // ---- availability must come from the index, not the cover rows -----------------------

    @Test
    fun `a month with photos but no cover rows is a placeholder, not unavailable`() {
        // This is the covers-switched-off case: the mask says May has photos, but no cover rows
        // were fetched. May must stay clickable — rendering it Unavailable made the whole grid
        // dead when the user turned the feature off.
        val mask = BooleanArray(12) { it == 4 } // May only
        val cells = MonthCoverModel.cellsFor(2026, mask, emptyList())

        assertTrue(cells[4] is MonthCoverCell.Placeholder)
        assertFalse(cells[4] is MonthCoverCell.Unavailable)
        assertTrue(cells[0] is MonthCoverCell.Unavailable)
    }

    @Test
    fun `all twelve months available with no covers yields twelve clickable placeholders`() {
        val cells = MonthCoverModel.cellsFor(2026, BooleanArray(12) { true }, emptyList())

        assertEquals(12, cells.count { it is MonthCoverCell.Placeholder })
        assertEquals(0, cells.count { it is MonthCoverCell.Unavailable })
    }

    @Test
    fun `a null mask falls back to everything unavailable`() {
        val cells = MonthCoverModel.cellsFor(2026, null, listOf(row(202608)))

        assertTrue(cells.all { it is MonthCoverCell.Unavailable })
    }

    // ---- covers --------------------------------------------------------------------------

    @Test
    fun `a month with a thumb url is a cover`() {
        val cells = cells(2026, listOf(row(202608, coverId = "ABC")))
        val august = cells[7] as MonthCoverCell.Cover

        assertEquals("ABC", august.coverId)
        assertEquals(202608, august.yearMonth)
        assertEquals("8月", august.label)
    }

    @Test
    fun `a month with no cover id is a placeholder, not a cover`() {
        // Happens when covers were switched off during the scan that wrote this month's index row.
        val cells = cells(2026, listOf(row(202608, coverId = null, thumbUrl = null)))

        assertTrue(cells[7] is MonthCoverCell.Placeholder)
    }

    @Test
    fun `a non-video cover without a thumb url still resolves to a Cover cell for interceptor refresh`() {
        // URLs are minted lazily; a row can exist before its thumbUrl is populated.
        // Non-video covers have coverAssetId, so Coil interceptor can refresh the URL using masterId.
        val cells = cells(2026, listOf(row(202608, thumbUrl = null)))
        val august = cells[7] as MonthCoverCell.Cover

        assertEquals("cover-202608", august.coverId)
        assertEquals("master-202608", august.coverMasterId)
        assertEquals("", august.coverUrl)
    }

    @Test
    fun `a blank thumb url for non-video asset resolves to Cover with empty coverUrl`() {
        val cells = cells(2026, listOf(row(202608, thumbUrl = "   ")))
        val august = cells[7] as MonthCoverCell.Cover

        assertEquals("cover-202608", august.coverId)
        assertEquals("", august.coverUrl)
    }

    @Test
    fun `a video cover never resolves to a Cover cell`() {
        // AGENTS §6.7.5c: a video's medUrl/originalUrl are mp4 bytes. If this ever starts producing
        // a Cover, the card will show a permanently blank square that never retries.
        val cells = cells(
            2026,
            listOf(row(202608, coverId = "vid", thumbUrl = null, mediaType = "VIDEO"))
        )

        // Exhaustive: no cell in the grid may be a Cover without a usable thumb url.
        assertTrue(cells.none { it is MonthCoverCell.Cover })
        assertTrue(cells[7] is MonthCoverCell.Placeholder)
    }

    @Test
    fun `every Cover cell has a non-blank cover id`() {
        // Guard against a future edit that constructs Cover from a nullable id.
        val cells = cells(
            2026,
            listOf(row(202601), row(202602, coverId = null), row(202603, coverId = null, thumbUrl = null))
        )

        val covers = cells.filterIsInstance<MonthCoverCell.Cover>()
        assertTrue(covers.isNotEmpty())
        assertTrue(covers.all { it.coverId.isNotBlank() })
    }

    // ---- isolation -----------------------------------------------------------------------

    @Test
    fun `rows from other years are ignored`() {
        val rows = listOf(row(202608), row(202508), row(202408))
        val cells = MonthCoverModel.cellsFor(2026, maskOf(2026, rows), rows)

        val covers = cells.filterIsInstance<MonthCoverCell.Cover>()
        assertEquals(1, covers.size)
        assertEquals(202608, covers.single().yearMonth)
        assertTrue(cells[7] is MonthCoverCell.Cover)
        // The two rows from other years (202508, 202408) must not fill 2026's grid.
        assertEquals(11, cells.count { it is MonthCoverCell.Unavailable })
    }

    @Test
    fun `an invalid year yields twelve unavailable cells`() {
        // Year 0 is the UNBUCKETED sentinel; it must not produce a grid full of "0年X月" cells.
        val cells = MonthCoverModel.cellsFor(0, null, listOf(row(202608)))

        assertEquals(12, cells.size)
        assertTrue(cells.all { it is MonthCoverCell.Unavailable })
    }

    @Test
    fun `unavailable and placeholder cells carry the month label`() {
        val cells = cells(2026, listOf(row(202602, coverId = null)))

        assertEquals("2月", cells[1].label)
        assertEquals("3月", cells[2].label)
        assertTrue(cells[1] is MonthCoverCell.Placeholder)
    }
}
