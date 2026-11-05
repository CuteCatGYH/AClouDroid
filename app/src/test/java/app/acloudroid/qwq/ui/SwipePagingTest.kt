package app.acloudroid.qwq.ui

import app.acloudroid.qwq.feature.photos.ui.pagesFromSwipe
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The commit rule for a finished horizontal swipe.
 *
 * ## Sign convention — measured, not assumed
 *
 * This was got wrong several times, so the convention is pinned down here and verified against a
 * device. Logging `calculatePan().x` per frame during a leftward swipe reported about **-11.5
 * every frame**, totalling `-544` for a full-width drag. So:
 *
 * ```
 * negative totalDragX = swiped left  = NEXT photo
 * positive totalDragX = swiped right = PREVIOUS photo
 * ```
 *
 * ## Direction vs. velocity
 *
 * `totalDragX` alone decides direction. `velocityMagnitude` is a non-negative "how fast was it",
 * and is never allowed to pick the direction: `calculatePan` and `VelocityTracker` report opposite
 * signs, so letting them both contribute a sign is how the direction kept flipping. A decisive
 * flick (magnitude above the threshold) commits without needing the distance; a slow drag needs to
 * cover a quarter of the screen.
 */
class SwipePagingTest {

    private val screen = 1080f

    // ---------------------------------------------------------------- sign convention

    @Test
    fun `swiping left goes to the next photo`() {
        assertEquals(
            1,
            pagesFromSwipe(totalDragX = -500f, velocityMagnitude = 0f, screenWidthPx = screen)
        )
    }

    @Test
    fun `swiping right goes to the previous photo`() {
        assertEquals(
            -1,
            pagesFromSwipe(totalDragX = 500f, velocityMagnitude = 0f, screenWidthPx = screen)
        )
    }

    // ---------------------------------------------------------------- velocity path

    @Test
    fun `a fast leftward flick advances without covering a quarter of the screen`() {
        // 100px is well under the 270px distance threshold, but the flick is decisive.
        assertEquals(
            1,
            pagesFromSwipe(totalDragX = -100f, velocityMagnitude = 2000f, screenWidthPx = screen)
        )
    }

    @Test
    fun `velocity magnitude just under the threshold does not commit`() {
        assertEquals(
            0,
            pagesFromSwipe(totalDragX = -50f, velocityMagnitude = 599f, screenWidthPx = screen)
        )
    }

    @Test
    fun `velocity exactly at the threshold does not commit`() {
        // The comparison is strict (`>`), so exactly 600 with too little distance snaps back.
        assertEquals(
            0,
            pagesFromSwipe(totalDragX = -50f, velocityMagnitude = 600f, screenWidthPx = screen)
        )
    }

    @Test
    fun `velocity just above the threshold commits`() {
        assertEquals(
            1,
            pagesFromSwipe(totalDragX = -50f, velocityMagnitude = 601f, screenWidthPx = screen)
        )
    }

    // ---------------------------------------------------------------- direction is authoritative

    @Test
    fun `a fast flick never reverses the direction of travel`() {
        // Travelled left, but the release velocity was enormous (the viewer only passes its
        // magnitude anyway). Direction is distance's job, so it advances.
        assertEquals(
            1,
            pagesFromSwipe(totalDragX = -100f, velocityMagnitude = 3000f, screenWidthPx = screen)
        )
        // Same idea backwards.
        assertEquals(
            -1,
            pagesFromSwipe(totalDragX = 100f, velocityMagnitude = 3000f, screenWidthPx = screen)
        )
    }

    // ---------------------------------------------------------------- snap back

    @Test
    fun `a short slow drag snaps back`() {
        assertEquals(
            0,
            pagesFromSwipe(totalDragX = -100f, velocityMagnitude = 200f, screenWidthPx = screen)
        )
    }

    @Test
    fun `a stationary release snaps back`() {
        assertEquals(
            0,
            pagesFromSwipe(totalDragX = 0f, velocityMagnitude = 0f, screenWidthPx = screen)
        )
    }

    // ---------------------------------------------------------------- edge cases

    @Test
    fun `a zero drag does not advance no matter how fast`() {
        // No travel direction at all: even a huge magnitude cannot invent one.
        assertEquals(
            0,
            pagesFromSwipe(totalDragX = 0f, velocityMagnitude = 5000f, screenWidthPx = screen)
        )
    }

    @Test
    fun `a zero width screen does not crash`() {
        // Degenerate viewport (first composition frame). Width is clamped to 1px, so the only
        // thing that matters is that it does not divide by zero.
        val result = pagesFromSwipe(totalDragX = -10f, velocityMagnitude = 0f, screenWidthPx = 0f)
        assertEquals("width must be clamped, not divided by", 1, result)
    }

    @Test
    fun `velocity magnitude is never negative at the decision point`() {
        // The caller should pass a magnitude, but if a sign slips through the `abs`-free guard it
        // must not flip the direction. This documents the contract, not a user-facing case.
        assertEquals(
            1,
            pagesFromSwipe(totalDragX = -400f, velocityMagnitude = -900f, screenWidthPx = screen)
        )
    }
}
