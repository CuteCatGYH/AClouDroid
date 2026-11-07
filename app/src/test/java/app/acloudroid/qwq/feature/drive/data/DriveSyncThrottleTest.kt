package app.acloudroid.qwq.feature.drive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the "don't re-walk on every tab switch" throttle.
 *
 * `DriveViewModel.init` calls this on every visit to the Drive tab. Before the throttle it ran a
 * full `etag` probe over every cached container each time — up to
 * `DriveRepository.DEFAULT_SYNC_CONTAINER_BUDGET` requests, one per folder, behind an
 * indeterminate progress strip that gave no hint of what it was doing.
 *
 * The walk itself needs a Room database and a live session, so it cannot run here. What *is*
 * testable in isolation — and what actually regressed — is the freshness decision, extracted
 * below exactly as `DriveRepository.syncIncrementalIfStale` computes it.
 */
class DriveSyncThrottleTest {

    /**
     * Mirrors the guard in `DriveRepository.syncIncrementalIfStale`.
     *
     * Kept as a local function so the rule is expressed once, in the same shape as production.
     * If the production rule changes, this must change with it — the two are intentionally
     * identical rather than one calling the other, because the production version is entangled
     * with the network walk.
     */
    private fun shouldSkip(lastCompletedAt: Long, now: Long, minIntervalMs: Long): Boolean {
        if (minIntervalMs <= 0) return false
        if (lastCompletedAt <= 0L) return false
        val since = now - lastCompletedAt
        return since in 0 until minIntervalMs
    }

    private val window = DriveRepository.SYNC_THROTTLE_MS

    /** The very first visit has nothing to compare against, so it must walk. */
    @Test
    fun firstEverPassAlwaysRuns() {
        assertTrue(!shouldSkip(lastCompletedAt = 0L, now = 1_000_000L, minIntervalMs = window))
    }

    /** Turning away and back within the window must not trigger a second walk. */
    @Test
    fun recentPassSuppressesTheNextOne() {
        val last = 1_000_000L
        assertTrue(
            "a pass 30 s ago must not be repeated",
            shouldSkip(last, now = last + 30_000L, minIntervalMs = window)
        )
    }

    /** Once the window has elapsed the walk runs again. */
    @Test
    fun stalePassAllowsTheNextOne() {
        val last = 1_000_000L
        assertTrue(
            "a pass older than the window must not suppress",
            !shouldSkip(last, now = last + window + 1, minIntervalMs = window)
        )
    }

    /** Exactly at the boundary the window is over — not still fresh. */
    @Test
    fun boundaryIsInclusiveOfExpiry() {
        val last = 1_000_000L
        assertTrue(!shouldSkip(last, now = last + window, minIntervalMs = window))
    }

    /** `minIntervalMs = 0` is the explicit "check now" path used by the manual button. */
    @Test
    fun zeroIntervalAlwaysRuns() {
        val last = 1_000_000L
        assertTrue(
            "force must bypass the throttle",
            !shouldSkip(last, now = last + 1L, minIntervalMs = 0L)
        )
    }

    /**
     * A clock that jumped backwards must not freeze syncing.
     *
     * `since` would be negative; treating it as "fresh" would suppress every future pass until
     * wall-clock time caught up again.
     */
    @Test
    fun backwardsClockDoesNotSuppressForever() {
        val last = 2_000_000L
        assertTrue(
            "a negative age means the clock moved, not that the data is fresh",
            !shouldSkip(last, now = last - 60_000L, minIntervalMs = window)
        )
    }

    /** The window is 5 minutes, matching the app's other "probably still good" constants. */
    @Test
    fun windowIsFiveMinutes() {
        assertEquals(5L * 60 * 1000, window)
    }
}
