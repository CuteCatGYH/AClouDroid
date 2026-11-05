package app.acloudroid.qwq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.acloudroid.qwq.feature.photos.ui.ZoomState
import app.acloudroid.qwq.feature.photos.ui.computeTransform
import app.acloudroid.qwq.feature.photos.ui.dismissBackgroundAlpha
import app.acloudroid.qwq.feature.photos.ui.dismissProgress
import app.acloudroid.qwq.feature.photos.ui.dismissScale
import app.acloudroid.qwq.feature.photos.ui.shouldDismiss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pan/zoom arithmetic and drag-to-dismiss thresholds.
 *
 * The pivot maths is the piece most likely to be subtly wrong (an image that drifts from the
 * finger during a pinch), and it is pure — so it belongs in a unit test rather than in a manual
 * poke-and-see.
 */
class ZoomStateTest {

    private val container = Size(1000f, 2000f)

    // ---------------------------------------------------------------- scaling

    @Test
    fun `pinching at centre scales about the centre and stays put`() {
        val t = computeTransform(
            currentScale = 1f,
            currentOffset = Offset.Zero,
            container = container,
            content = container, // content == container, so it is clamped to fit
            centroid = Offset(500f, 1000f), // exactly the centre
            pan = Offset.Zero,
            zoomFactor = 2f,
            minScale = 1f,
            maxScale = 6f
        )
        assertEquals(2f, t.scale, 0.001f)
        // Zooming about the centre of a fit image must not translate it.
        assertEquals(0f, t.offset.x, 0.001f)
        assertEquals(0f, t.offset.y, 0.001f)
    }

    @Test
    fun `scale is clamped to the allowed range`() {
        val tooBig = computeTransform(
            currentScale = 5f, currentOffset = Offset.Zero, container = container,
            content = container, centroid = Offset(500f, 1000f), pan = Offset.Zero,
            zoomFactor = 10f, minScale = 1f, maxScale = 6f
        )
        assertEquals(6f, tooBig.scale, 0.001f)

        val tooSmall = computeTransform(
            currentScale = 1.2f, currentOffset = Offset.Zero, container = container,
            content = container, centroid = Offset(500f, 1000f), pan = Offset.Zero,
            zoomFactor = 0.1f, minScale = 1f, maxScale = 6f
        )
        assertEquals(1f, tooSmall.scale, 0.001f)
    }

    @Test
    fun `pinching off-centre moves the content the other way`() {
        // Pinching with the centroid to the right of centre should push the image left, so the
        // point under the fingers stays under the fingers.
        val t = computeTransform(
            currentScale = 1f, currentOffset = Offset.Zero, container = container,
            content = container, centroid = Offset(900f, 1000f), pan = Offset.Zero,
            zoomFactor = 2f, minScale = 1f, maxScale = 6f
        )
        assertTrue("expected negative x offset, got ${t.offset.x}", t.offset.x < 0f)
        assertEquals(0f, t.offset.y, 0.001f)
    }

    // ---------------------------------------------------------------- pan clamping

    @Test
    fun `pan limit is zero when the content fits`() {
        val limit = ZoomState.panLimit(1f, container, Size(500f, 500f))
        assertEquals(Offset.Zero, limit)
    }

    @Test
    fun `pan limit is half the overflow when zoomed`() {
        // content 1000 wide at scale 2 -> 2000 drawn; container 1000 -> overflow 1000 -> limit 500.
        val limit = ZoomState.panLimit(2f, container, Size(1000f, 2000f))
        assertEquals(500f, limit.x, 0.001f)
        // Height: 2000 * 2 = 4000 drawn, container 2000 -> overflow 2000 -> limit 1000.
        assertEquals(1000f, limit.y, 0.001f)
    }

    @Test
    fun `panning at rest is clamped to zero`() {
        // At scale 1 with a fitting image there is nowhere to pan; the offset must stay at 0.
        val t = computeTransform(
            currentScale = 1f, currentOffset = Offset.Zero, container = container,
            content = container, centroid = Offset(500f, 1000f), pan = Offset(300f, 300f),
            zoomFactor = 1f, minScale = 1f, maxScale = 6f
        )
        assertEquals(0f, t.offset.x, 0.001f)
        assertEquals(0f, t.offset.y, 0.001f)
    }

    @Test
    fun `panning while zoomed is clamped to the overflow`() {
        val t = computeTransform(
            currentScale = 2f, currentOffset = Offset.Zero, container = container,
            content = container, centroid = Offset(500f, 1000f), pan = Offset(9999f, 0f),
            zoomFactor = 1f, minScale = 1f, maxScale = 6f
        )
        // limit.x at scale 2 with content 1000 == container 1000 -> overflow 1000 -> 500.
        assertEquals(500f, t.offset.x, 0.001f)
    }

    // ---------------------------------------------------------------- drag routing

    @Test
    fun `drag dismisses only when not zoomed`() {
        val state = ZoomState()
        assertTrue("at rest a drag should move the viewer", state.consumeDrag(Offset(0f, 40f)))
    }

    @Test
    fun `drag pans the image when zoomed in`() {
        val state = ZoomState()
        state.onContainerSized(container, Size(1000f, 2000f))
        state.applyGesture(centroid = Offset(500f, 1000f), pan = Offset.Zero, zoomFactor = 2f)
        assertTrue(state.isZoomedIn)

        // A drag while zoomed must pan, not dismiss.
        assertFalse(state.consumeDrag(Offset(0f, -40f)))
    }

    @Test
    fun `double tap toggles between fit and zoomed`() {
        val state = ZoomState()
        state.onContainerSized(container, Size(1000f, 2000f))

        state.toggleZoom(Offset(500f, 1000f))
        assertTrue(state.isZoomedIn)

        state.toggleZoom(Offset(500f, 1000f))
        assertFalse(state.isZoomedIn)
        assertEquals(0f, state.offsetX, 0.001f)
    }

    // ---------------------------------------------------------------- dismiss thresholds

    @Test
    fun `dismiss progress is monotonic and clamped`() {
        assertEquals(0f, dismissProgress(-100f, 500f), 0.001f) // upward drag never dismisses
        assertEquals(0f, dismissProgress(0f, 500f), 0.001f)
        assertEquals(0.5f, dismissProgress(250f, 500f), 0.001f)
        assertEquals(1f, dismissProgress(900f, 500f), 0.001f) // clamped past the threshold
    }

    @Test
    fun `background fades and image shrinks as the drag progresses`() {
        assertEquals(1f, dismissBackgroundAlpha(0f), 0.001f)
        assertTrue(dismissBackgroundAlpha(1f) < 0.4f)
        assertEquals(1f, dismissScale(0f), 0.001f)
        assertTrue(dismissScale(1f) < 0.8f)
    }

    @Test
    fun `dismiss commits on distance or on a fast downward flick`() {
        val threshold = 500f
        assertTrue("long slow drag", shouldDismiss(dragY = 600f, velocityY = 0f, thresholdPx = threshold))
        assertTrue("short but fast flick", shouldDismiss(dragY = 120f, velocityY = 2000f, thresholdPx = threshold))
        assertFalse("short slow drag", shouldDismiss(dragY = 120f, velocityY = 100f, thresholdPx = threshold))
        assertFalse("upward flick never dismisses", shouldDismiss(dragY = -400f, velocityY = -3000f, thresholdPx = threshold))
    }
}
