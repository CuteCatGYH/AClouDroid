package app.acloudroid.qwq.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.acloudroid.qwq.data.settings.AppSettings
import app.acloudroid.qwq.data.settings.ScanFrequency
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hosts [SettingsScreen] and wires the system back gesture to it.
 *
 * ## Why this exists as a wrapper
 *
 * Settings is an **overlay**, not an Activity or a nav destination: opening it flips a boolean in
 * `AppShell`. Compose has no idea that means "a screen is on top", so without this the edge-swipe
 * gesture is delivered to `MainActivity` and the app simply closes.
 *
 * ## Predictive back
 *
 * A plain `BackHandler` would fix the "app closes" part but still be wrong: the system would show
 * its own preview (a shrinking snapshot) while our panel sat perfectly still until the finger
 * lifted. `PredictiveBackHandler` reports the gesture's progress continuously, so the panel can
 * slide and fade in step with the finger — the same treatment `PhotoViewerScreen` gives the photo
 * viewer.
 *
 * Only a translation + alpha is animated. Both are applied through `graphicsLayer`, so the panel
 * behind it never re-measures per frame — animating layout offsets here would re-lay-out the entire
 * settings list on every drag frame.
 *
 * ## Degradation
 *
 * If the platform does not support predictive back, `PredictiveBackHandler` never emits and the
 * `BackHandler` below still cancels the gesture correctly; the panel simply closes without a
 * finger-tracked preview. Nothing breaks either way.
 */
@Composable
fun SettingsContainer(
    onClose: () -> Unit,
    settings: AppSettings,
    fullScanMessage: String?,
    isFullScanRunning: Boolean,
    onStartFullScan: () -> Unit,
    onFrequencyChanged: (ScanFrequency) -> Unit,
    onCustomIntervalChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // 0 = at rest (fully on-screen), 1 = dragged fully away.
    // The panel is drawn at `1 - progress` of its travel, so both the live gesture and the
    // closing animation can drive the same value.
    val exitProgress = remember { Animatable(0f) }

    /**
     * Live override while the gesture is in flight.
     *
     * Separate from [exitProgress] because `Animatable` can only be `snapTo`n from a coroutine and
     * the gesture callback is not suspendable — same reason `ViewerTransitionState` keeps a
     * gesture override alongside its animation.
     */
    var gestureProgress by remember { mutableStateOf<Float?>(null) }
    val drawnProgress = gestureProgress ?: exitProgress.value

    // Slide distance for a full swipe. Deliberately a fraction of the width rather than the whole
    // screen: the panel also fades out, so travelling the full width makes it look like it is
    // crawling away long after it has become invisible.
    val travelPx = with(LocalDensity.current) { 96.dp.toPx() }

    val closeWithAnimation: () -> Unit = {
        scope.launch {
            val from = drawnProgress
            exitProgress.snapTo(from)
            gestureProgress = null
            exitProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(EXIT_DURATION_MS, easing = FastOutSlowInEasing)
            )
            onClose()
        }
    }

    PredictiveBackHandler(enabled = true) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                // Gesture progress is 0 -> 1 as the user drags away; same direction as our exit.
                gestureProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            // Completed: hand over to the closing animation from wherever the finger left it.
            closeWithAnimation()
        } catch (e: CancellationException) {
            // Abandoned: spring back to fully visible. No animation on the system side either.
            val from = gestureProgress ?: 0f
            gestureProgress = null
            scope.launch {
                exitProgress.snapTo(from)
                exitProgress.animateTo(0f, tween(RESTORE_DURATION_MS, easing = FastOutSlowInEasing))
            }
            throw e
        }
    }

    // Fallback for platforms/builds where the predictive flow never fires. Disabled while the
    // predictive handler owns the gesture would be wrong (they are mutually exclusive per gesture),
    // so both are enabled: whichever receives the event handles it.
    BackHandler(enabled = true) {
        closeWithAnimation()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = drawnProgress * travelPx
                alpha = 1f - (drawnProgress * 0.6f)
                // Sliding from the left edge, so shrinking toward that edge reads correctly.
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsScreen(
            onBack = closeWithAnimation,
            settings = settings,
            fullScanMessage = fullScanMessage,
            isFullScanRunning = isFullScanRunning,
            onStartFullScan = onStartFullScan,
            onFrequencyChanged = onFrequencyChanged,
            onCustomIntervalChanged = onCustomIntervalChanged
        )
    }
}

private const val EXIT_DURATION_MS = 180
private const val RESTORE_DURATION_MS = 140
