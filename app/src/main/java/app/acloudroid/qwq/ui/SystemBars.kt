package app.acloudroid.qwq.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Controls the system bars for the full-screen viewer.
 *
 * ## Two distinct states, deliberately different
 *
 *  - **Full screen** ([immersive] = false): the status bar stays visible. The user still wants the
 *    clock, battery and notifications while browsing photos. Only the *app's* chrome (top bar,
 *    bottom navigation) goes away — that is handled by the callers, not here.
 *  - **Immersive** ([immersive] = true): the photo is the only thing on screen, so the status bar
 *    and navigation bar are hidden too.
 *
 * Hiding the status bar in the plain full-screen state (which an earlier version did) leaves a
 * blank strip where the clock should be and no way to check anything without leaving the viewer.
 *
 * ## Why the bars must be managed at all
 *
 * The viewer is rendered *inside* `PhotosScreen` → `AppShell`'s `Scaffold`. A `Box` that fills the
 * screen still only fills the area its ancestors gave it, so the system bars would overlap or inset
 * it. The app's own bars are removed by the callers; the system ones are removed here.
 *
 * Bars are restored on dispose, so leaving the viewer returns the app to a normal window. Restoring
 * explicitly matters because the activity is shared: if we merely stopped asking, the last
 * requested flags would stick.
 */
@Composable
fun HideSystemBars(immersive: Boolean) {
    val view = LocalView.current
    val activity = view.context as? Activity ?: return

    DisposableEffect(immersive) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)

        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // Plain full screen: show the status bar, but let the app draw behind it so the photo
            // still reaches the top edge.
            controller.show(WindowInsetsCompat.Type.statusBars())
        }

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }
}

/**
 * True while the viewer chrome should be considered visible.
 *
 * Kept as a tiny helper so the "tap toggles chrome" rule lives in one place rather than being
 * re-derived in each overlay.
 */
fun shouldShowChrome(chromeVisible: Boolean, immersive: Boolean): Boolean =
    chromeVisible && !immersive
