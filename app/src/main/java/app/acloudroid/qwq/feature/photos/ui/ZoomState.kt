package app.acloudroid.qwq.feature.photos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import kotlin.math.max

/**
 * Pan/zoom state for one full-screen image.
 *
 * Deliberately a plain mutable holder rather than an animation-backed one: every value is written
 * directly from a pointer event, and running each through an animatable made the image lag one
 * frame behind the finger.
 *
 * All the geometry lives in [computeTransform] as a pure function, so the tricky part — keeping the
 * picture inside its box while zooming about the right pivot — is unit testable without a device.
 */
@Stable
class ZoomState(
    private val minScale: Float = 1f,
    private val maxScale: Float = 6f
) {
    var scale by mutableFloatStateOf(1f)
        private set

    /** Translation in pixels, applied after scaling about the box centre. */
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** Size of the viewport the image is drawn into. Updated on every layout pass. */
    var containerSize by mutableStateOf(Size.Zero)
        private set

    /** Size of the drawn image inside the viewport after `fit`. */
    private var contentSize = Size.Zero

    val isZoomedIn: Boolean get() = scale > minScale + 0.01f

    fun onContainerSized(container: Size, content: Size) {
        if (container == containerSize && content == contentSize) return
        containerSize = container
        contentSize = content
        // A rotation or layout change invalidates the old clamp; re-apply it so the image can't be
        // left parked off-screen.
        clamp()
    }

    /**
     * Animates a double-tap toggle towards its target.
     *
     * Suspends until the animation finishes. Drives scale and offset from ONE `Animatable`, so the
     * two stay in lockstep: animating them separately lets the image drift sideways as the scale
     * settles, because each curve reaches its target at a different moment.
     *
     * The pivot is passed through so the zoom expands about the tapped point — zooming about the
     * centre would make the picture feel like it slid away from the finger.
     */
    suspend fun animateToggle(pivot: Offset) {
        val target = toggleTarget(pivot)
        if (target.scale == scale && target.offset == Offset(offsetX, offsetY)) return

        // A gentle bounce on the way out, but not on the way back: un-zooming with overshoot looks
        // like a mistake, while zooming in with it looks deliberate.
        val spec = if (target.scale > scale) {
            spring<Float>(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        } else {
            spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        }

        val progress = Animatable(0f)
        // Snapshot the start state: reading `scale`/`offsetX` inside the block would read values
        // this very animation is mutating, so every frame would interpolate from the previous frame
        // instead of from the gesture's starting point.
        val startScale = scale
        val startOffset = Offset(offsetX, offsetY)

        progress.animateTo(1f, animationSpec = spec) {
            applyAnimated(
                scale = androidx.compose.ui.util.lerp(startScale, target.scale, this.value),
                offset = lerp(startOffset, target.offset, this.value)
            )
        }
        // Land exactly on the target: the animation's last frame can be a hair off due to float
        // rounding, and a residual 1.7999 scale would leave `isZoomedIn` true forever.
        applyAnimated(target.scale, target.offset)
    }

    /** Double-tap toggle: zoom to a comfortable 2.5x, or all the way back out. */
    fun toggleZoom(pivot: Offset) {
        val target = if (isZoomedIn) minScale else (minScale * 2.5f).coerceAtMost(maxScale)
        apply(pivot = pivot, pan = Offset.Zero, zoomFactor = target / scale)
    }

    /**
     * Computes the state a double-tap toggle should land on, without applying it.
     *
     * The double-tap zoom is animated, so the caller needs the end state up front to drive an
     * `Animatable`. Returns the transform's target scale and offset for the given pivot.
     */
    fun toggleTarget(pivot: Offset): ZoomTransform {
        val target = if (isZoomedIn) minScale else (minScale * 1.8f).coerceAtMost(maxScale)
        return computeTransform(
            currentScale = scale,
            currentOffset = Offset(offsetX, offsetY),
            container = containerSize,
            content = contentSize,
            centroid = pivot,
            pan = Offset.Zero,
            zoomFactor = target / scale,
            minScale = minScale,
            maxScale = maxScale
        )
    }

    /**
     * Applies an animated double-tap state, bypassing the gesture path.
     *
     * Used frame by frame from an animation: each call sets the values the animation has reached.
     */
    fun applyAnimated(scale: Float, offset: Offset) {
        this.scale = scale
        this.offsetX = offset.x
        this.offsetY = offset.y
    }

    /**
     * Applies a pinch/pan gesture delta.
     *
     * [centroid] is the gesture centroid in viewport coordinates; [pan] is the movement of that
     * centroid since the last event; [zoomFactor] is the ratio of the current pinch span to the
     * previous one (1 for a pure drag).
     */
    fun applyGesture(centroid: Offset, pan: Offset, zoomFactor: Float) {
        apply(pivot = centroid, pan = pan, zoomFactor = zoomFactor)
    }

    /**
     * Splits a drag between "pan the zoomed image" and "drag the whole viewer away".
     *
     * When zoomed in, dragging is how you reach the edges of the picture, so it must not dismiss.
     * When at rest, dragging is a dismiss gesture and the caller moves the surface instead.
     *
     * @return true when the drag should move the *viewer* (i.e. a dismiss gesture), false when it
     *   paned the image.
     */
    fun consumeDrag(delta: Offset): Boolean {
        if (isZoomedIn) {
            applyGesture(centroid = Offset.Zero, pan = delta, zoomFactor = 1f)
            return false
        }
        return true
    }

    /** Re-applies the clamp, e.g. after a drag ends without dismissing. */
    fun clamp() {
        apply(pivot = Offset.Zero, pan = Offset.Zero, zoomFactor = 1f)
    }

    fun reset() {
        scale = minScale
        offsetX = 0f
        offsetY = 0f
    }

    private fun apply(pivot: Offset, pan: Offset, zoomFactor: Float) {
        val next = computeTransform(
            currentScale = scale,
            currentOffset = Offset(offsetX, offsetY),
            container = containerSize,
            content = contentSize,
            centroid = pivot,
            pan = pan,
            zoomFactor = zoomFactor,
            minScale = minScale,
            maxScale = maxScale
        )
        scale = next.scale
        offsetX = next.offset.x
        offsetY = next.offset.y
    }

    companion object {
        /**
         * How far the image may travel on each axis before a gap would appear.
         *
         * When the scaled image is larger than the viewport, the pan range is half the overflow —
         * exactly how far it can move before showing a gap. When it is smaller (or zoomed out to
         * fit), the range is 0 and the image stays centred.
         */
        fun panLimit(scale: Float, container: Size, content: Size): Offset {
            if (container == Size.Zero || content == Size.Zero) return Offset.Zero
            val limitX = max(0f, (content.width * scale - container.width) / 2f)
            val limitY = max(0f, (content.height * scale - container.height) / 2f)
            return Offset(limitX, limitY)
        }
    }
}

/** Result of one transform computation. */
data class ZoomTransform(val scale: Float, val offset: Offset)

/**
 * Computes the new scale/offset for one gesture event.
 *
 * ## Why this is a pure function
 *
 * Zoom-about-a-pivot with clamping is the classic place to get the sign of a term wrong: the
 * symptom is an image that drifts away from the finger as you pinch, which is only noticeable on a
 * touch screen. Keeping it pure makes the arithmetic assertable.
 *
 * The model: the image is drawn centred, scaled by [scale], then translated by [offset]. A gesture
 * both zooms (about [centroid]) and pans by [pan]. Zooming about a point that is not the centre
 * changes the effective translation, hence the extra `pivot * (1 - factor)` term.
 */
fun computeTransform(
    currentScale: Float,
    currentOffset: Offset,
    container: Size,
    content: Size,
    centroid: Offset,
    pan: Offset,
    zoomFactor: Float,
    minScale: Float,
    maxScale: Float
): ZoomTransform {
    val newScale = (currentScale * zoomFactor).coerceIn(minScale, maxScale)
    // When the scale is already clamped, a further pinch must not move the image either.
    val effectiveFactor = if (currentScale == 0f) 1f else newScale / currentScale

    val containerCentre = Offset(container.width / 2f, container.height / 2f)
    val pivotFromCentre = centroid - containerCentre

    val rawOffset = currentOffset + pan + pivotFromCentre * (1f - effectiveFactor)

    val limit = ZoomState.panLimit(newScale, container, content)
    val clamped = Offset(
        rawOffset.x.coerceIn(-limit.x, limit.x),
        rawOffset.y.coerceIn(-limit.y, limit.y)
    )

    return ZoomTransform(newScale, clamped)
}

/** Progress of a drag-to-dismiss gesture, in 0..1. Only downward drags count. */
fun dismissProgress(dragY: Float, thresholdPx: Float): Float {
    if (dragY <= 0f || thresholdPx <= 0f) return 0f
    return (dragY / thresholdPx).coerceIn(0f, 1f)
}

/** How much to fade the backdrop as the surface is dragged away. */
fun dismissBackgroundAlpha(progress: Float): Float = 1f - progress.coerceIn(0f, 1f) * 0.7f

/** Scale applied to the image while dragging to dismiss — a subtle shrink, like a native gallery. */
fun dismissScale(progress: Float): Float = 1f - progress.coerceIn(0f, 1f) * 0.25f

/**
 * Decides whether a release commits the dismiss.
 *
 * Both distance and velocity are considered: a short but fast flick should close, and a long slow
 * drag that the user pulls back should not.
 */
fun shouldDismiss(dragY: Float, velocityY: Float, thresholdPx: Float): Boolean {
    if (dragY > thresholdPx) return true
    return dragY > 0f && velocityY > 1200f
}
