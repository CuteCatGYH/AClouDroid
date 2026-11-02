package com.getup.iClouDroid.feature.drive.data

import com.getup.iClouDroid.feature.drive.data.DriveDeltaPlanner.Action
import com.getup.iClouDroid.feature.drive.data.DriveDeltaPlanner.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner is where D3's correctness lives, so every branch is pinned here.
 *
 * The tests are written as "given a cached row and a probe result, this is what the walk does" —
 * the failure mode being guarded against is a wrong `SKIP`, because a skipped container is a
 * container whose changes are silently never discovered.
 */
class DriveDeltaPlannerTest {

    private val now = 1_700_000_000_000L

    private fun candidate(
        etag: String? = "abc",
        fetched: Boolean = true,
        lastSyncedAt: Long = now - 1000
    ) = Candidate(
        drivewsid = "FOLDER::com.apple.CloudDocs::A",
        parentDrivewsid = "FOLDER::com.apple.CloudDocs::root",
        name = "A",
        cachedEtag = etag,
        childrenFetched = fetched,
        lastSyncedAt = lastSyncedAt
    )

    // ---- LIST_UNKNOWN --------------------------------------------------------------------

    @Test
    fun `never-listed container is always fetched`() {
        assertEquals(
            Action.LIST_UNKNOWN,
            DriveDeltaPlanner.decide(candidate(etag = null, fetched = false), "abc", now)
        )
    }

    /**
     * A cached etag with `childrenFetched == false` happens when a folder row was written as a
     * child of its parent but never expanded. An etag alone cannot vouch for a listing that was
     * never written, so this must not fall through to the etag comparison.
     */
    @Test
    fun `etag known but children never fetched is still unknown`() {
        assertEquals(
            Action.LIST_UNKNOWN,
            DriveDeltaPlanner.decide(candidate(etag = "abc", fetched = false), "abc", now)
        )
    }

    // ---- LIST_CHANGED --------------------------------------------------------------------

    @Test
    fun `moved etag triggers a re-list`() {
        assertEquals(
            Action.LIST_CHANGED,
            DriveDeltaPlanner.decide(candidate(etag = "abc"), "def", now)
        )
    }

    /** Changed etag wins over staleness — both re-list, but the log should say why. */
    @Test
    fun `changed etag and stale reports changed`() {
        val stale = candidate(etag = "abc", lastSyncedAt = now - (48L * 60 * 60 * 1000))
        assertEquals(Action.LIST_CHANGED, DriveDeltaPlanner.decide(stale, "def", now))
    }

    // ---- SKIP ----------------------------------------------------------------------------

    @Test
    fun `matching etag within the age window is skipped`() {
        assertEquals(Action.SKIP, DriveDeltaPlanner.decide(candidate(etag = "abc"), "abc", now))
    }

    @Test
    fun `etag comparison is exact`() {
        // Etags are opaque tokens, not version numbers; "abc" and "ABC" are different values.
        assertEquals(
            Action.LIST_CHANGED,
            DriveDeltaPlanner.decide(candidate(etag = "abc"), "ABC", now)
        )
    }

    // ---- LIST_STALE ----------------------------------------------------------------------

    @Test
    fun `matching etag past the age window re-lists`() {
        val stale = candidate(
            etag = "abc",
            lastSyncedAt = now - DriveDeltaPlanner.DEFAULT_MAX_AGE_MS - 1
        )
        assertEquals(Action.LIST_STALE, DriveDeltaPlanner.decide(stale, "abc", now))
    }

    @Test
    fun `age exactly at the window still skips`() {
        // Boundary: the comparison is `>` not `>=`, so a row exactly at the limit is still fresh.
        val edge = candidate(etag = "abc", lastSyncedAt = now - DriveDeltaPlanner.DEFAULT_MAX_AGE_MS)
        assertEquals(Action.SKIP, DriveDeltaPlanner.decide(edge, "abc", now))
    }

    /**
     * A failed probe reports a null etag. That must **never** be read as "unchanged" — doing so
     * would permanently skip a container whose probe merely errored.
     */
    @Test
    fun `null probe result never skips`() {
        assertEquals(Action.LIST_STALE, DriveDeltaPlanner.decide(candidate(etag = "abc"), null, now))
    }

    /** A clock change (or a row written with a bogus timestamp) must re-list, not skip. */
    @Test
    fun `negative age is treated as stale`() {
        val future = candidate(etag = "abc", lastSyncedAt = now + 60_000)
        assertEquals(Action.LIST_STALE, DriveDeltaPlanner.decide(future, "abc", now))
    }

    @Test
    fun `custom max age is honoured`() {
        val c = candidate(etag = "abc", lastSyncedAt = now - 5000)
        assertEquals(Action.SKIP, DriveDeltaPlanner.decide(c, "abc", now, maxAgeMs = 10_000))
        assertEquals(Action.LIST_STALE, DriveDeltaPlanner.decide(c, "abc", now, maxAgeMs = 1000))
    }

    // ---- orderParentsFirst ---------------------------------------------------------------

    @Test
    fun `parents are visited before children`() {
        val root = Candidate("FOLDER::z::root", "", "root", "e", true, now)
        val mid = Candidate("FOLDER::z::mid", "FOLDER::z::root", "mid", "e", true, now)
        val leaf = Candidate("FOLDER::z::leaf", "FOLDER::z::mid", "leaf", "e", true, now)

        val ordered = DriveDeltaPlanner.orderParentsFirst(listOf(leaf, mid, root))

        assertEquals(
            listOf("FOLDER::z::root", "FOLDER::z::mid", "FOLDER::z::leaf"),
            ordered.map { it.drivewsid }
        )
    }

    @Test
    fun `orphaned candidate sorts before nothing and is not dropped`() {
        val orphan = Candidate("FOLDER::z::orphan", "FOLDER::z::missing", "orphan", "e", true, now)
        val root = Candidate("FOLDER::z::root", "", "root", "e", true, now)
        val child = Candidate("FOLDER::z::c", "FOLDER::z::root", "c", "e", true, now)

        val ordered = DriveDeltaPlanner.orderParentsFirst(listOf(child, orphan, root))

        assertEquals(3, ordered.size)
        // Both roots (depth 0) come before the depth-1 child.
        assertEquals("FOLDER::z::c", ordered.last().drivewsid)
    }

    /** A malformed parent link must not spin. The cycle guard returns depth 0 rather than looping. */
    @Test
    fun `cycle does not hang`() {
        val a = Candidate("FOLDER::z::a", "FOLDER::z::b", "a", "e", true, now)
        val b = Candidate("FOLDER::z::b", "FOLDER::z::a", "b", "e", true, now)

        val ordered = DriveDeltaPlanner.orderParentsFirst(listOf(a, b))

        assertEquals(2, ordered.size)
    }

    @Test
    fun `single candidate is returned unchanged`() {
        val only = candidate()
        assertEquals(listOf(only), DriveDeltaPlanner.orderParentsFirst(listOf(only)))
    }

    @Test
    fun `empty input is safe`() {
        assertTrue(DriveDeltaPlanner.orderParentsFirst(emptyList()).isEmpty())
    }

    // ---- removedChildren -----------------------------------------------------------------

    @Test
    fun `children missing from the fresh listing are reported as removed`() {
        val removed = DriveDeltaPlanner.removedChildren(
            localChildren = listOf("a", "b", "c"),
            freshChildren = setOf("a", "c")
        )
        assertEquals(listOf("b"), removed)
    }

    @Test
    fun `everything removed when the folder is now empty`() {
        val removed = DriveDeltaPlanner.removedChildren(
            localChildren = listOf("a", "b"),
            freshChildren = emptySet()
        )
        assertEquals(listOf("a", "b"), removed)
    }

    @Test
    fun `nothing removed when listings agree`() {
        assertTrue(
            DriveDeltaPlanner.removedChildren(listOf("a", "b"), setOf("a", "b")).isEmpty()
        )
    }

    /** New children are inserted by the replace, never reported as removed. */
    @Test
    fun `new children are not reported as removed`() {
        assertTrue(
            DriveDeltaPlanner.removedChildren(listOf("a"), setOf("a", "b", "c")).isEmpty()
        )
    }
}
