package app.acloudroid.qwq.feature.photos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Remembers where each thumbnail currently sits in the window, so the viewer can open from that
 * exact rectangle and close back into it.
 *
 * ## Why the grid publishes its own geometry
 *
 * The alternative is a shared-element transition. Compose's `SharedTransitionLayout` would do this
 * declaratively, but it is still experimental on this Compose version and, more importantly, it
 * cannot cross the `Dialog`/`AndroidView` boundary — a constraint this viewer already had to work
 * around once. Publishing rectangles keeps the animation fully under our control: the grid reports
 * where a cell is, the viewer interpolates from there to full screen.
 *
 * The map is keyed by asset id and only holds cells that are currently laid out, so it stays small
 * (a screenful of thumbnails) even for a 15k-item library.
 */
@Stable
class ThumbnailBoundsTracker {
    private val bounds = HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>()

    /** Called from each grid cell as it lays out. */
    fun update(assetId: String, coords: androidx.compose.ui.layout.LayoutCoordinates) {
        bounds[assetId] = coords
    }

    /** Called when a cell leaves composition, so stale entries cannot accumulate. */
    fun remove(assetId: String) {
        bounds.remove(assetId)
    }

    fun boundsOf(assetId: String): Rect? {
        val coords = bounds[assetId] ?: return null
        if (!coords.isAttached) return null
        val rect = coords.boundsInWindow()
        return if (rect.isEmpty) null else rect
    }
}

@Composable
fun rememberThumbnailBoundsTracker(): ThumbnailBoundsTracker =
    remember { ThumbnailBoundsTracker() }

/**
 * Drives the open/close animation between a grid cell and the full-screen viewer.
 *
 * ## What "from where it came" means here
 *
 * Opening: the viewer's content starts as a rectangle matching the tapped thumbnail and grows to
 * fill the screen, while its own chrome fades in afterwards.
 *
 * Closing: the reverse — the content shrinks back into the thumbnail's rectangle.
 *
 * When the thumbnail is no longer on screen (the user swiped to a photo further down the month, or
 * scrolled the grid), there is nothing to return to. In that case the animation degrades to a
 * plain fade rather than flying to a wrong or off-screen rectangle, which would look like a glitch.
 */
@Stable
class ViewerTransitionState {
    /** 0 = fully collapsed into the grid cell, 1 = fully expanded to full screen. */
    val progress = Animatable(0f)

    /**
     * Live override while a predictive-back gesture is in progress.
     *
     * Null when no gesture is active, in which case [effectiveProgress] falls back to the
     * animation's value. Kept separate from [progress] because that one can only be `snapTo`n from
     * a coroutine, and gesture callbacks are not suspending.
     */
    var gestureProgress by mutableStateOf<Float?>(null)
        private set

    /** The value the UI should draw with: the gesture override if any, else the animation. */
    val effectiveProgress: Float
        get() = gestureProgress ?: progress.value

    private var job: Job? = null

    /** The rectangle the animation starts from / returns to. Null means "no anchor": use a fade. */
    var anchor: Rect? = null
        private set

    /** True once the opening animation has finished; used to defer chrome and gestures. */
    var isSettledOpen by mutableStateOf(false)
        private set

    fun setAnchor(rect: Rect?) {
        anchor = rect
    }

    /**
     * Sets the progress directly, for the predictive-back gesture.
     *
     * The gesture reports continuous progress, so the viewer must follow it frame by frame rather
     * than run its own animation. `snapTo` is suspend, which cannot be called from a pointer
     * callback, so a live override value is used instead: [effectiveProgress] prefers it whenever a
     * gesture is active.
     */
    fun livePreview(progress: Float) {
        job?.cancel()
        job = null
        // 1 = expanded. A back gesture value of 1 means "fully dragged away", so invert it.
        gestureProgress = (1f - progress).coerceIn(0f, 1f)
    }

    /** Releases the gesture override so the animation value takes over again. */
    fun endLivePreview() {
        gestureProgress = null
    }

    suspend fun snapTo(value: Float) {
        job?.cancel()
        job = null
        gestureProgress = null
        progress.snapTo(value.coerceIn(0f, 1f))
    }

    /** Cancels any in-flight animation. Used before taking manual control. */
    fun cancelAnimation() {
        job?.cancel()
        job = null
    }

    suspend fun animateOpen(onFinished: () -> Unit = {}) {
        job?.cancel()
        isSettledOpen = false
        kotlinx.coroutines.coroutineScope {
            val j = launch {
                // A tween (not a spring) on purpose.
                //
                // The rectangle is applied as a `graphicsLayer` transform, so it costs no measure
                // pass per frame; but a spring still leaves a long settle tail whose last few
                // pixels of movement are imperceptible while still holding the transition "open"
                // — chrome stays hidden and the grid stays faded for hundreds of milliseconds
                // after the photo looks like it has landed. A fixed-length eased tween ends
                // exactly when the motion ends.
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = OPEN_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
                isSettledOpen = true
                onFinished()
            }
            job = j
            j.join()
        }
    }

    /**
     * Animates the viewer away, then invokes [onDone] once it is fully collapsed.
     *
     * [onDone] is what actually closes the viewer (clearing the VM state). Delaying it until the
     * animation ends is what makes the closing motion visible at all — nulling the state first
     * would remove the composable mid-animation.
     */
    suspend fun animateClose(onDone: () -> Unit) {
        job?.cancel()
        isSettledOpen = false
        kotlinx.coroutines.coroutineScope {
            val j = launch {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = CLOSE_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
                onDone()
            }
            job = j
            j.join()
        }
    }

    /**
     * Interpolates the current content rectangle.
     *
     * At progress 0 this is the grid cell; at 1 it is the full window. Returning the full window
     * when there is no anchor keeps the viewer usable even if the animation cannot run.
     */
    fun contentRect(windowSize: Size): Rect {
        if (windowSize.width <= 0f || windowSize.height <= 0f) {
            return anchor ?: Rect.Zero
        }
        val full = Rect(0f, 0f, windowSize.width, windowSize.height)
        val from = anchor ?: return full
        val p = effectiveProgress.coerceIn(0f, 1f)
        return Rect(
            left = from.left + (full.left - from.left) * p,
            top = from.top + (full.top - from.top) * p,
            right = from.right + (full.right - from.right) * p,
            bottom = from.bottom + (full.bottom - from.bottom) * p
        )
    }

    /** Corner radius shrinks towards the thumbnail's small radius as the card collapses. */
    fun cornerRadiusPx(maxRadiusPx: Float): Float {
        val p = effectiveProgress.coerceIn(0f, 1f)
        return maxRadiusPx * p
    }

    /** The backdrop fades in slightly ahead of the content, so the grid does not flash through. */
    fun scrimAlpha(): Float {
        val p = effectiveProgress.coerceIn(0f, 1f)
        return (p * 1.4f).coerceIn(0f, 1f)
    }

    /**
     * Opacity of the viewer's black backdrop *behind the photo*.
     *
     * Rises quickly but never exceeds [PHOTO_SCRIM_MAX], because the grid behind is still fading
     * out on the same progress. If this backdrop went opaque the grid's fade would be invisible: the
     * user would see the photo zoom over a black plate, which is the "flash to black" this whole
     * rewrite exists to remove. It reaches [PHOTO_SCRIM_SETTLED] once the motion has landed, so the
     * photo is finally shown on a proper gallery-black background.
     */
    fun photoScrimAlpha(): Float {
        val p = effectiveProgress.coerceIn(0f, 1f)
        return PHOTO_SCRIM_MAX + (PHOTO_SCRIM_SETTLED - PHOTO_SCRIM_MAX) * p
    }

    private companion object {
        const val OPEN_DURATION_MS = 320
        const val CLOSE_DURATION_MS = 300

        /**
         * How dark the backdrop may get while the open/close motion is still running.
         *
         * Deliberately short of 1f: the screen never goes fully black during the transition, so
         * the grid's own fade-out is always perceptible through it.
         */
        const val PHOTO_SCRIM_MAX = 0.55f

        /**
         * The backdrop the viewer settles on.
         *
         * Not 1f either: a photo viewer's background is conventionally near-black rather than
         * true black, and keeping a little light behind the image stops a bright photo from
         * looking like it is floating in a void.
         */
        const val PHOTO_SCRIM_SETTLED = 0.92f
    }
}

@Composable
fun rememberViewerTransitionState(): ViewerTransitionState =
    remember { ViewerTransitionState() }
