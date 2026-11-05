package app.acloudroid.qwq.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.acloudroid.qwq.feature.photos.ui.ViewerTransitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry of the open/close transition.
 *
 * The animation itself needs a device, but the interpolation that decides *where* the photo is
 * drawn at each step is pure arithmetic — and getting it wrong is what produces the classic
 * "photo flies in from the wrong corner" bug.
 */
class ViewerTransitionTest {

    private val window = Size(1080f, 2400f)
    private val cell = Rect(left = 100f, top = 500f, right = 400f, bottom = 800f)

    @Test
    fun `collapsed progress maps to the thumbnail rectangle`() {
        val state = ViewerTransitionState()
        state.setAnchor(cell)

        // progress is 0 right after construction (nothing animated yet) — the cell box.
        val rect = state.contentRect(window)

        assertEquals(cell.left, rect.left, 0.01f)
        assertEquals(cell.top, rect.top, 0.01f)
        assertEquals(cell.right, rect.right, 0.01f)
        assertEquals(cell.bottom, rect.bottom, 0.01f)
    }

    @Test
    fun `without an anchor the content is always full window`() {
        // This is the "grid scrolled away" case: no rectangle to fly back to, so the viewer must
        // stay put rather than jumping to a stale or off-screen box.
        val state = ViewerTransitionState()
        state.setAnchor(null)

        val rect = state.contentRect(window)

        assertEquals(0f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(window.width, rect.right, 0.01f)
        assertEquals(window.height, rect.bottom, 0.01f)
    }

    @Test
    fun `scrim is transparent when collapsed and opaque when expanded`() {
        val state = ViewerTransitionState()
        state.setAnchor(cell)

        assertEquals(0f, state.scrimAlpha(), 0.01f)

        state.livePreview(0f) // back-progress 0 => fully expanded
        assertEquals(1f, state.scrimAlpha(), 0.01f)
    }

    @Test
    fun `corner radius is zero when collapsed`() {
        val state = ViewerTransitionState()
        state.setAnchor(cell)

        // At progress 0 the card has fully merged into the square thumbnail.
        assertEquals(0f, state.cornerRadiusPx(24f), 0.01f)
    }

    @Test
    fun `predictive back progress is inverted`() {
        val state = ViewerTransitionState()
        state.setAnchor(cell)

        // A back gesture at 25% drag should collapse the viewer to 75% — not expand it.
        state.livePreview(0.25f)

        val rect = state.contentRect(window)
        val expected = Rect(
            left = cell.left + (0f - cell.left) * 0.75f,
            top = cell.top + (0f - cell.top) * 0.75f,
            right = cell.right + (window.width - cell.right) * 0.75f,
            bottom = cell.bottom + (window.height - cell.bottom) * 0.75f
        )
        assertEquals(expected.left, rect.left, 0.5f)
        assertEquals(expected.top, rect.top, 0.5f)
        assertEquals(expected.right, rect.right, 0.5f)
        assertEquals(expected.bottom, rect.bottom, 0.5f)
    }

    @Test
    fun `fully dragged back collapses to the thumbnail`() {
        val state = ViewerTransitionState()
        state.setAnchor(cell)

        state.livePreview(1f) // fully dragged

        val rect = state.contentRect(window)
        assertEquals(cell.left, rect.left, 0.5f)
        assertEquals(cell.top, rect.top, 0.5f)
    }

    @Test
    fun `ending the live preview releases the override`() {
        val state = ViewerTransitionState()
        state.setAnchor(cell)

        state.livePreview(0.5f)
        assertTrue("override should be active", state.gestureProgress != null)

        state.endLivePreview()
        assertEquals(null, state.gestureProgress)
    }

    @Test
    fun `a swept anchor does not produce an inverted rectangle`() {
        // Guards against a sign error making `right < left`, which would draw nothing at all.
        val state = ViewerTransitionState()
        state.setAnchor(Rect(900f, 2000f, 1050f, 2150f)) // bottom-right corner cell

        state.livePreview(0.5f)

        val rect = state.contentRect(window)
        assertTrue("left must precede right", rect.left < rect.right)
        assertTrue("top must precede bottom", rect.top < rect.bottom)
    }
}
