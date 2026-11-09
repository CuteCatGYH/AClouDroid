package app.acloudroid.qwq.feature.update.data

/**
 * Information about a GitHub release.
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val publishedAt: String
)

/**
 * UI state representation for the update check lifecycle.
 */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpdateAvailable(val releaseInfo: ReleaseInfo) : UpdateState()
    data class UpToDate(val currentVersion: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
