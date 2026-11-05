package app.acloudroid.qwq.feature.local.data

import android.net.Uri

/**
 * Model representing a local media directory/album on the Android device.
 *
 * Prepares the metadata and toggles for Phase 3 (automatic folder-level incremental upload/backup).
 */
data class LocalAlbum(
    val bucketId: String,
    val name: String,
    val coverUri: Uri?,
    val itemCount: Int,
    val relativePath: String = "",
    val isBackupEnabled: Boolean = false
)

data class LocalMediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateTaken: Long,
    val size: Long,
    val bucketId: String
)
