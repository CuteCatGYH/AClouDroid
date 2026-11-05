package app.acloudroid.qwq.di

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.acloudroid.qwq.IClouDroidApp

/**
 * Walks up the context chain to find the [AppContainer] owned by [IClouDroidApp].
 *
 * Prefers `applicationContext` when available: a Compose `LocalContext` is usually an Activity
 * (a ContextThemeWrapper), and the Activity's own base chain is not guaranteed to expose the
 * Application instance — but `applicationContext` always is.
 */
fun Context.appContainer(): AppContainer {
    var current: Context? = this
    while (current != null) {
        if (current is IClouDroidApp) return current.container
        val next = when {
            current.applicationContext !== current -> current.applicationContext
            current is ContextWrapper && current.baseContext !== current -> current.baseContext
            else -> null
        }
        if (next == null) {
            error("Could not find IClouDroidApp in the context chain (started from ${this::class.java.name}); is it registered in AndroidManifest?")
        }
        current = next
    }
    error("Could not find IClouDroidApp in the context chain; is it registered in AndroidManifest?")
}

/** Compose-friendly accessor for the app container. */
@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { context.appContainer() }
}
