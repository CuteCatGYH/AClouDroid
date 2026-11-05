package app.acloudroid.qwq.feature.photos.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.cancellation.CancellationException

/**
 * Wires the Android predictive-back gesture to the viewer's shrink-back animation.
 *
 * ## Why this is separate from a plain `BackHandler`
 *
 * A normal `BackHandler` only fires when the gesture *completes* — the system draws its own
 * preview and the app reacts at the very end. `PredictiveBackHandler` reports the gesture's
 * progress continuously (0..1), so the viewer can shrink in step with the user's finger and then
 * either finish closing or spring back, exactly like a system screen.
 *
 * ## The progress mapping
 *
 * The gesture's progress is inverted: at 0 the user has not moved, at 1 they have dragged all the
 * way. The viewer's own transition uses 1 = expanded, 0 = collapsed, so the back progress is
 * subtracted from 1 as it arrives.
 *
 * @param isEnabled when false (e.g. an info card is open) the gesture is left to the system, and
 *   the caller's own back handling runs instead.
 * @param onProgress called continuously with the collapse fraction while the gesture is live. The
 *   caller should apply it directly to the transition, without animating.
 * @param onCancel called if the user abandons the gesture; the caller should animate back open.
 * @param onCommit called when the gesture completes; the caller should finish closing.
 */
@Composable
fun ViewerPredictiveBack(
    isEnabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit
) {
    if (!isEnabled) return

    PredictiveBackHandler(enabled = true) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                // `touchY` is deliberately unused: this viewer recedes uniformly rather than
                // following the finger vertically, because its content is a full-screen photo.
                onProgress(backEvent.progress)
            }
            // The flow completed without throwing: the user finished the gesture.
            onCommit()
        } catch (e: CancellationException) {
            // Abandoned mid-gesture — the system will animate nothing, so we restore the viewer.
            onCancel()
            throw e
        }
    }
}
