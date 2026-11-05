package app.acloudroid.qwq.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the keyset pagination the system photo picker relies on (ISS-035).
 *
 * The provider used to page with `LIMIT :limit OFFSET :offset` over
 * `ORDER BY assetDate DESC, id ASC`. That is a row-number cursor, and `photo_assets` is written to
 * while the picker is open — incremental sync upserts new photos and the month scanner deletes
 * removed ones. Every such change shifts all later pages by one row, which both **skips** an asset
 * and **duplicates** another.
 *
 * The drift compounds per page, so it surfaces where the picker is furthest into the library: the
 * last pages, i.e. the earliest years. That is exactly the reported symptom — "scrolling down is
 * fine, scrolling up into early years leaves blank tiles".
 *
 * These tests model the query semantics in memory so the regression is caught without Room.
 */
class MediaPageKeysetTest {

    private data class Row(val assetDate: Long, val id: String)

    /** Same total order as `ORDER BY assetDate DESC, id ASC`. */
    private val order = compareByDescending<Row> { it.assetDate }.thenBy { it.id }

    /** Mirrors `PhotoDao.getMediaPageFirst` + `getMediaPageAfter`. */
    private fun keysetPage(
        rows: List<Row>,
        limit: Int,
        after: Row? = null
    ): List<Row> {
        val sorted = rows.sortedWith(order)
        val eligible = if (after == null) {
            sorted
        } else {
            sorted.filter {
                it.assetDate < after.assetDate ||
                    (it.assetDate == after.assetDate && it.id > after.id)
            }
        }
        return eligible.take(limit)
    }

    /** Mirrors the old `PhotoDao.getMediaPage` (the behaviour being replaced). */
    private fun offsetPage(rows: List<Row>, limit: Int, offset: Int): List<Row> =
        rows.sortedWith(order).drop(offset).take(limit)

    /**
     * Walks the whole library the way the provider does: keep asking while a page came back full,
     * carrying the last row as the cursor.
     */
    private fun keysetWalk(rows: List<Row>, pageSize: Int): List<Row> {
        val out = mutableListOf<Row>()
        var cursor: Row? = null
        do {
            val page = keysetPage(rows, pageSize, cursor)
            out += page
            cursor = page.lastOrNull()
        } while (page.size == pageSize)
        return out
    }

    private fun library(count: Int): List<Row> =
        (0 until count).map { Row(assetDate = 1_700_000_000L - it * 60L, id = "rec%03d".format(it)) }

    @Test
    fun `keyset walk returns the whole library exactly once`() {
        val rows = library(25)
        val walked = keysetWalk(rows, pageSize = 7)

        assertEquals(rows.size, walked.size)
        assertEquals(rows.size, walked.toSet().size)
        assertEquals(rows.sortedWith(order), walked)
    }

    @Test
    fun `keyset pagination survives an insert at the head mid-walk`() {
        val rows = library(30).toMutableList()
        val pageSize = 8

        val firstPage = keysetPage(rows, pageSize)
        val cursor = firstPage.last()

        // A newer photo syncs in. Under OFFSET this shifts every remaining page by one row.
        rows += Row(assetDate = 1_700_100_000L, id = "recNEW")

        val rest = mutableListOf<Row>()
        var next: Row? = cursor
        do {
            val page = keysetPage(rows, pageSize, next)
            rest += page
            next = page.lastOrNull()
        } while (page.size == pageSize)

        val seen = firstPage + rest
        assertEquals("no asset may be skipped or duplicated", seen.size, seen.toSet().size)
        assertEquals(30, seen.size) // the inserted row sorts above the cursor, so it is out of scope
    }

    @Test
    fun `keyset pagination survives a delete mid-walk`() {
        val rows = library(30).toMutableList()
        val pageSize = 8

        val firstPage = keysetPage(rows, pageSize)
        val cursor = firstPage.last()

        // The month scanner removes an already-seen asset (a cloud-side delete propagating in).
        rows.removeAll { it.id == firstPage.first().id }

        val rest = mutableListOf<Row>()
        var next: Row? = cursor
        do {
            val page = keysetPage(rows, pageSize, next)
            rest += page
            next = page.lastOrNull()
        } while (page.size == pageSize)

        val all = firstPage + rest
        assertEquals("no asset may be duplicated", all.size, all.toSet().size)
        // The deleted row was already handed out in `firstPage`, so the walk legitimately reports
        // 30 distinct assets — the point is that its removal did NOT shift the cursor and cause a
        // later asset to be skipped. Under OFFSET, `rest` would start one row late and an asset
        // after the deletion point would never be served.
        assertEquals(30, all.size)
        // Everything below the cursor at deletion time must still be delivered.
        val survivorsBelowCursor = rows.filter { r ->
            r.assetDate < cursor.assetDate ||
                (r.assetDate == cursor.assetDate && r.id > cursor.id)
        }.map { it.id }.toSet()
        val deliveredBelowCursor = rest.map { it.id }.toSet()
        assertEquals(survivorsBelowCursor, deliveredBelowCursor)
    }

    @Test
    fun `offset pagination demonstrably duplicates rows after a head insert`() {
        // This is the regression being fixed: the same insert that keyset tolerates makes OFFSET
        // repeat a row. If this ever stops failing, the reasoning in the fix comment on
        // PhotoDao.getMediaPageFirst is stale.
        val rows = library(30).toMutableList()
        val pageSize = 8

        val first = offsetPage(rows, pageSize, offset = 0)
        rows += Row(assetDate = 1_700_100_000L, id = "recNEW")

        val second = offsetPage(rows, pageSize, offset = pageSize)

        val seen = first + second
        val duplicated = seen.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(
            "OFFSET must duplicate a row after a head insert (that is the bug)",
            duplicated.isNotEmpty()
        )
    }

    @Test
    fun `ties on assetDate are broken deterministically by id`() {
        // Many iCloud records share one second; without the id tiebreaker the page boundary is
        // non-deterministic and rows can repeat or vanish across pages.
        val rows = (0 until 12).map { Row(assetDate = 1_700_000_000L, id = "rec%02d".format(it)) }
        val walked = keysetWalk(rows, pageSize = 5)

        assertEquals(12, walked.size)
        assertEquals(12, walked.toSet().size)
        assertEquals(rows.sortedBy { it.id }, walked)
    }

    @Test
    fun `empty library yields a single empty page`() {
        assertEquals(emptyList<Row>(), keysetPage(emptyList(), 10))
    }

    @Test
    fun `page size larger than the library returns everything and ends`() {
        val rows = library(4)
        val page = keysetPage(rows, limit = 50)
        assertEquals(4, page.size)
        assertTrue(keysetPage(rows, 50, after = page.last()).isEmpty())
    }

    @Test
    fun `exact multiple of page size terminates without an extra query`() {
        // 24 rows / 8 per page: the last full page still carries a token, so the provider must
        // handle a trailing empty page rather than assuming a short page means "done".
        val rows = library(24)
        val walked = keysetWalk(rows, pageSize = 8)
        assertEquals(24, walked.size)
        assertTrue(keysetPage(rows, 8, after = walked.last()).isEmpty())
    }
}
