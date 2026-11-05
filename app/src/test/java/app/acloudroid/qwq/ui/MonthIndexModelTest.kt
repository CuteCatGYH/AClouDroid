package app.acloudroid.qwq.ui

import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity
import app.acloudroid.qwq.feature.photos.ui.MonthIndexModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the gallery jump panel's grouping and stepping rules.
 *
 * These are the two things a user notices immediately when they are wrong: a year list in the
 * wrong direction ("2026 at the bottom"), and an arrow that jumps to a month that renders nothing.
 */
class MonthIndexModelTest {

    private fun month(yearMonth: Int, count: Int = 3) = PhotoMonthIndexEntity(
        yearMonth = yearMonth,
        itemCount = count,
        firstAssetDate = 0L,
        lastAssetDate = 0L,
        isComplete = true
    )

    // ---- yearSections ---------------------------------------------------------------------

    @Test
    fun `groups months into years preserving the descending input order`() {
        val sections = MonthIndexModel.yearSections(
            listOf(month(202608), month(202605), month(202512), month(201901))
        )

        // Highest year first — the whole point of the panel matching the scroll direction.
        assertEquals(listOf(2026, 2025, 2019), sections.map { it.year })
    }

    @Test
    fun `marks exactly the months that have photos`() {
        val sections = MonthIndexModel.yearSections(
            listOf(month(202608), month(202605), month(202601))
        )

        val y2026 = sections.single()
        assertEquals(12, y2026.available.size)
        assertTrue(y2026.available[0]) // 1月
        assertFalse(y2026.available[1])
        assertTrue(y2026.available[4]) // 5月
        assertFalse(y2026.available[5])
        assertTrue(y2026.available[7]) // 8月
        assertFalse(y2026.available[8])
        assertFalse(y2026.available[11]) // 12月
    }

    @Test
    fun `years do not bleed into each other`() {
        val sections = MonthIndexModel.yearSections(listOf(month(202601), month(202512)))

        assertEquals(listOf(2026, 2025), sections.map { it.year })
        assertEquals(listOf(true) + List(11) { false }, sections[0].available.toList())
        assertEquals(List(11) { false } + listOf(true), sections[1].available.toList())
    }

    @Test
    fun `unbucketed rows are dropped entirely`() {
        // 0 is the sentinel for orphaned masters with no assetDate (ISS-024). It is not a year and
        // must never appear as a jump target.
        val sections = MonthIndexModel.yearSections(
            listOf(month(MonthBucketing.UNBUCKETED), month(202608))
        )

        assertEquals(listOf(2026), sections.map { it.year })
    }

    @Test
    fun `empty input yields no sections`() {
        assertTrue(MonthIndexModel.yearSections(emptyList()).isEmpty())
    }

    @Test
    fun `single month library`() {
        val sections = MonthIndexModel.yearSections(listOf(month(202603)))

        val only = sections.single()
        assertEquals(2026, only.year)
        assertEquals(202603, only.newestYearMonth)
        assertEquals(1, only.available.count { it })
    }

    @Test
    fun `newestYearMonth takes the highest month, not the insertion order`() {
        // Input happens to be DESC, but the derivation must not depend on that.
        val sections = MonthIndexModel.yearSections(listOf(month(202601), month(202609)))

        assertEquals(202609, sections.single().newestYearMonth)
    }

    // ---- stepMonth ------------------------------------------------------------------------

    private val months = listOf(month(202608), month(202605), month(202512), month(202501))

    @Test
    fun `steps backwards in time with positive delta`() {
        assertEquals(202605, MonthIndexModel.stepMonth(months, 202608, 1))
        assertEquals(202512, MonthIndexModel.stepMonth(months, 202605, 1))
    }

    @Test
    fun `steps forwards in time with negative delta`() {
        assertEquals(202608, MonthIndexModel.stepMonth(months, 202605, -1))
    }

    @Test
    fun `returns null at both ends of the library`() {
        assertNull(MonthIndexModel.stepMonth(months, 202608, -1)) // newest month
        assertNull(MonthIndexModel.stepMonth(months, 202501, 1)) // oldest month
    }

    @Test
    fun `skips months without photos`() {
        // January -> March: February is absent and must not be a target.
        val sparse = listOf(month(202603), month(202601))
        assertEquals(202601, MonthIndexModel.stepMonth(sparse, 202603, 1))
        assertEquals(202603, MonthIndexModel.stepMonth(sparse, 202601, -1))
    }

    @Test
    fun `unknown current month or empty library returns null`() {
        assertNull(MonthIndexModel.stepMonth(months, 199901, 1))
        assertNull(MonthIndexModel.stepMonth(emptyList(), 202608, 1))
    }

    // ---- resolveMonth ---------------------------------------------------------------------

    @Test
    fun `resolveMonth hits an available month`() {
        val sections = MonthIndexModel.yearSections(listOf(month(202608), month(202603)))

        assertEquals(202603, MonthIndexModel.resolveMonth(sections, 2026, 3))
        assertEquals(202608, MonthIndexModel.resolveMonth(sections, 2026, 8))
    }

    @Test
    fun `resolveMonth rejects a month without photos`() {
        val sections = MonthIndexModel.yearSections(listOf(month(202608)))

        assertNull(MonthIndexModel.resolveMonth(sections, 2026, 4))
    }

    @Test
    fun `resolveMonth rejects an unknown year and out-of-range months`() {
        val sections = MonthIndexModel.yearSections(listOf(month(202608)))

        assertNull(MonthIndexModel.resolveMonth(sections, 1999, 8))
        assertNull(MonthIndexModel.resolveMonth(sections, 2026, 0))
        assertNull(MonthIndexModel.resolveMonth(sections, 2026, 13))
    }

    // ---- labels ---------------------------------------------------------------------------

    @Test
    fun `month labels`() {
        assertEquals("8月", MonthBucketing.monthLabelOf(202608))
        assertEquals("12月", MonthBucketing.monthLabelOf(202512))
        assertEquals("1月", MonthBucketing.monthNumberLabelOf(1))
        assertEquals("未分组", MonthBucketing.monthLabelOf(0))
    }

    @Test
    fun `equality compares the availability mask, not its identity`() {
        val a = MonthIndexModel.yearSections(listOf(month(202608), month(202603)))
        val b = MonthIndexModel.yearSections(listOf(month(202608), month(202603)))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
