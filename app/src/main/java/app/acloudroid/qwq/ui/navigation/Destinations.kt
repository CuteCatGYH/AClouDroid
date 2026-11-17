package app.acloudroid.qwq.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level sections of the app.
 *
 * Adding an iCloud feature means adding an entry here plus a branch in
 * [app.acloudroid.qwq.ui.AppShell] — no changes to authentication or to the other features.
 */
import androidx.annotation.StringRes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import app.acloudroid.qwq.R

enum class AppDestination(
    val label: String,
    val icon: ImageVector,
    @get:StringRes val labelRes: Int
) {
    Photos("照片", Icons.Default.Photo, R.string.nav_photos),
    Albums("备份", Icons.Default.PhotoAlbum, R.string.nav_albums),
    Drive("云盘", Icons.Default.Folder, R.string.nav_drive),
    Notes("备忘录", Icons.AutoMirrored.Filled.Note, R.string.nav_notes),
    Reminders("提醒", Icons.Default.CheckCircle, R.string.nav_reminders),
    FindMy("查找", Icons.Default.LocationOn, R.string.nav_find_my)
}
