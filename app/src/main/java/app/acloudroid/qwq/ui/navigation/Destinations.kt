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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn

enum class AppDestination(
    val label: String,
    val icon: ImageVector
) {
    Photos("照片", Icons.Default.Photo),
    Albums("备份", Icons.Default.PhotoAlbum),
    Drive("云盘", Icons.Default.Folder),
    Notes("备忘录", Icons.AutoMirrored.Filled.Note),
    Reminders("提醒", Icons.Default.CheckCircle),
    FindMy("查找", Icons.Default.LocationOn)
}
