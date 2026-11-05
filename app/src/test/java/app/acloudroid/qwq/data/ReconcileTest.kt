package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.Reconcile
import app.acloudroid.qwq.feature.photos.data.db.PhotoIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rules that decide whether a cached photo is deleted (ISS-031).
 *
 * This is the riskiest logic in the app: it is the only place a photo the user can still see on
 * another device may disappear locally. Two properties matter and both are asserted here:
 *
 *  - a row whose *master* is still returned is kept, even if its own asset id is gone (the
 *    conservative rule — several `CPLAsset` records can share one `CPLMaster`);
 *  - a walk that returned implausibly little is refused outright rather than trusted.
 */
class ReconcileTest {

    private fun row(id: String, masterId: String) = PhotoIdentity(id = id, masterId = masterId)

    @Test
    fun `asset still present is kept`() {
        val local = listOf(row("a1", "m1"))
        val decision = Reconcile.decide(local, setOf("a1", "m1"))

        assertTrue(decision.toDelete.isEmpty())
        assertEquals(1, decision.keptBySeen)
        assertEquals(0, decision.keptByMaster)
    }

    /**
     * The conservative rule. `a2` itself is gone, but `m1` is still returned, which means another
     * asset still points at the same media. Deleting `a2` would be a guess, and a wrong guess
     * removes a real photo.
     */
    @Test
    fun `asset gone but master alive is kept`() {
        val local = listOf(row("a2", "m1"))
        val decision = Reconcile.decide(local, setOf("a3", "m1"))

        assertTrue(decision.toDelete.isEmpty())
        assertEquals(1, decision.keptByMaster)
        assertEquals(0, decision.keptBySeen)
    }

    @Test
    fun `asset and master both gone is purged`() {
        val local = listOf(row("a9", "m9"))
        val decision = Reconcile.decide(local, setOf("a1", "m1"))

        assertEquals(listOf("a9"), decision.toDelete)
        assertEquals(0, decision.keptByMaster)
        assertEquals(0, decision.keptBySeen)
    }

    /** Every row of a shared master survives together, which is the whole point of the rule. */
    @Test
    fun `all rows sharing a live master are kept`() {
        val local = listOf(
            row("a1", "shared"),
            row("a2", "shared"),
            row("a3", "shared")
        )
        val decision = Reconcile.decide(local, setOf("a1", "shared"))

        assertTrue(decision.toDelete.isEmpty())
        assertEquals(2, decision.keptByMaster)
        assertEquals(1, decision.keptBySeen)
    }

    /** Mixed page: nothing about one group may affect the verdict on another. */
    @Test
    fun `mixed library splits correctly`() {
        val local = listOf(
            row("live", "m-live"),
            row("orphan", "m-live"),
            row("gone", "m-gone")
        )
        val decision = Reconcile.decide(local, setOf("live", "m-live"))

        assertEquals(listOf("gone"), decision.toDelete)
        assertEquals(1, decision.keptByMaster)
        assertEquals(1, decision.keptBySeen)
    }

    /** A blank masterId must never accidentally match anything. */
    @Test
    fun `blank master id does not keep a row alive`() {
        val local = listOf(row("a1", ""))
        val decision = Reconcile.decide(local, setOf(""))

        assertEquals(listOf("a1"), decision.toDelete)
    }

    @Test
    fun `safety gate accepts a complete walk`() {
        assertTrue(Reconcile.isSafeToApply(localCount = 1000, seenIdCount = 1000))
    }

    /** The exact shape of "server answered 200 with nothing": must be refused. */
    @Test
    fun `safety gate refuses an empty walk`() {
        assertFalse(Reconcile.isSafeToApply(localCount = 1000, seenIdCount = 0))
    }

    /**
     * A resumed walk whose earlier pages were lost leaves `seen` as a suffix of the library.
     * This is not hypothetical — it purged 700 real photos on device before the gate was strict.
     */
    @Test
    fun `safety gate refuses a partial resumed walk`() {
        assertFalse(Reconcile.isSafeToApply(localCount = 1000, seenIdCount = 600))
    }

    /** Nothing cached means nothing to protect; an empty walk is harmless. */
    @Test
    fun `safety gate allows an empty cache`() {
        assertTrue(Reconcile.isSafeToApply(localCount = 0, seenIdCount = 0))
    }
}
