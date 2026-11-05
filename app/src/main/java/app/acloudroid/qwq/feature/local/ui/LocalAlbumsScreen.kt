package app.acloudroid.qwq.feature.local.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.acloudroid.qwq.feature.local.data.LocalAlbum

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAlbumsScreen(
    viewModel: LocalAlbumsViewModel,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val queueTasks by viewModel.queueTasks.collectAsStateWithLifecycle()
    val forceUploadUntil by viewModel.forceUploadUntilTimestamp.collectAsStateWithLifecycle()

    var hasMediaPermission by remember {
        mutableStateOf(checkMediaPermissions(context))
    }
    // Tracked separately from [hasMediaPermission]: losing this one must not block the album
    // list, it only means location is redacted from the files we read.
    var hasLocationPermission by remember {
        mutableStateOf(hasMediaLocationPermission(context))
    }
    var showQueueSheet by remember { mutableStateOf(false) }

    // Set when the user switches a folder on while location permission is missing. Holds the
    // bucketId so the decision can be applied once they answer.
    var pendingLocationBucket by remember { mutableStateOf<String?>(null) }
    // Carried across the system dialog. Kept apart from [pendingLocationBucket] so our explanation
    // closes before the platform one opens, instead of stacking two dialogs.
    var awaitingLocationBucket by remember { mutableStateOf<String?>(null) }
    // Once a request has come back refused, the system will not show the dialog again and the
    // permission is not listed in Settings (it belongs to the undefined permission group), so the
    // only recovery is a reinstall. Tracked to say so instead of offering a dead button.
    var locationPermanentlyDenied by remember { mutableStateOf(false) }
    // Whether the "you cannot grant this any more" explanation is on screen.
    var locationDeniedDialog by remember { mutableStateOf(false) }

    // Battery optimization prompt state
    var showBatteryOptimizationPrompt by remember { mutableStateOf(false) }
    var pendingBatteryBucket by remember { mutableStateOf<String?>(null) }
    var hasPromptedBattery by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = checkMediaPermissions(context)
        hasMediaPermission = granted
        hasLocationPermission = hasMediaLocationPermission(context)
        if (granted) {
            viewModel.loadAlbums()
        }
    }

    // Separate launcher: location is requested on its own so a refusal cannot be confused with a
    // media-read refusal, and so the dialog copy can explain just this one.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
        val bucket = awaitingLocationBucket
        awaitingLocationBucket = null
        if (!granted) {
            locationPermanentlyDenied = true
            // Say so immediately. On a second refusal the platform returns denied without
            // showing anything at all - no dialog, no error - so without this the user taps
            // 允许 and simply nothing happens.
            locationDeniedDialog = true
        }

        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (!hasPromptedBattery && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            pendingBatteryBucket = bucket
            showBatteryOptimizationPrompt = true
            hasPromptedBattery = true
        } else {
            bucket?.let { viewModel.toggleAlbumBackup(it, true) }
        }
    }

    LaunchedEffect(hasMediaPermission) {
        if (hasMediaPermission) {
            viewModel.loadAlbums()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "本地相册备份",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = if (hasMediaPermission) "共 ${albums.size} 个本地相册文件夹" else "等待授予本地媒体权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        // Asked at the moment backup is enabled rather than at app start: the reason is only
        // meaningful once the user is actually about to upload something.
        pendingLocationBucket?.let { bucket ->
            LocationPermissionDialog(
                onAllow = {
                    // Close our explanation first, then let the platform dialog take the screen.
                    pendingLocationBucket = null
                    awaitingLocationBucket = bucket
                    locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                },
                onAllowWithout = {
                    pendingLocationBucket = null
                    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    if (!hasPromptedBattery && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                        pendingBatteryBucket = bucket
                        showBatteryOptimizationPrompt = true
                        hasPromptedBattery = true
                    } else {
                        viewModel.toggleAlbumBackup(bucket, true)
                    }
                },
                onDismiss = { pendingLocationBucket = null }
            )
        }

        if (locationDeniedDialog) {
            LocationPermanentlyDeniedDialog(onDismiss = { locationDeniedDialog = false })
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
        ) {
            if (!hasMediaPermission) {
                PermissionRequestBanner(
                    onRequestPermission = {
                        // `ACCESS_MEDIA_LOCATION` is requested alongside the media reads.
                        //
                        // It is what makes `MediaStore.setRequireOriginal()` usable and what lets
                        // MediaProvider return the LATITUDE/LONGITUDE columns. Without it the
                        // platform hands back a redacted stream and blanks the location columns,
                        // so photos upload with no `{GPS}` and videos with no ISO-6709 - while
                        // everything else looks fine, which is why the loss went unnoticed.
                        //
                        // Declared in the manifest since the beginning but never *requested*:
                        // a runtime permission that is only declared is never granted.
                        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                                Manifest.permission.ACCESS_MEDIA_LOCATION
                            )
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.ACCESS_MEDIA_LOCATION
                            )
                        } else {
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.ACCESS_MEDIA_LOCATION
                            )
                        }
                        permissionLauncher.launch(permissions)
                    },
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                )
            } else if (isLoading && albums.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (albums.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "未发现本地相册或媒体文件夹",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Non-blocking: losing location permission does not stop a backup, it only
                    // strips GPS from what gets uploaded. Surfaced anyway, because otherwise the
                    // loss is invisible until someone opens the photo on another device and
                    // notices the map is empty.
                    if (!hasLocationPermission) {
                        LocationPermissionHint(
                            onRequest = {
                                if (locationPermanentlyDenied) {
                                    // No system dialog and no Settings entry exist any more.
                                    locationDeniedDialog = true
                                } else {
                                    locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    if (queueTasks.isNotEmpty() || albums.any { it.isBackupEnabled }) {
                        BackupStatusCard(
                            tasks = queueTasks,
                            forceUploadUntil = forceUploadUntil,
                            onOpenQueue = { showQueueSheet = true },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(albums, key = { it.bucketId }) { album ->
                            AlbumCard(
                                album = album,
                                onToggleBackup = { enabled ->
                                    // First time an album is switched on, ask for location before
                                    // anything is queued. Asking here rather than at app start means
                                    // the user sees the reason at the moment it matters, and a
                                    // refusal costs nothing yet.
                                    if (enabled && !hasLocationPermission && !locationPermanentlyDenied) {
                                        pendingLocationBucket = album.bucketId
                                    } else {
                                        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                                        if (enabled && !hasPromptedBattery && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                                            pendingBatteryBucket = album.bucketId
                                            showBatteryOptimizationPrompt = true
                                            hasPromptedBattery = true
                                        } else {
                                            viewModel.toggleAlbumBackup(album.bucketId, enabled)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (showQueueSheet) {
                BackupQueueSheet(
                    tasks = queueTasks,
                    onDismiss = { showQueueSheet = false },
                    onRetryFailed = { viewModel.retryFailedTasks() },
                    onClearCompleted = { viewModel.clearCompletedTasks() },
                    onRemoveTask = { id -> viewModel.removeTask(id) },
                    onClearAll = { viewModel.clearAllTasks() },
                    forceUploadUntil = forceUploadUntil,
                    onForceUpload = { viewModel.triggerForceUploadForOneHour() }
                )
            }

            if (showBatteryOptimizationPrompt) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        showBatteryOptimizationPrompt = false
                        pendingBatteryBucket?.let { viewModel.toggleAlbumBackup(it, true) }
                        pendingBatteryBucket = null
                    },
                    title = { Text("提示: 开启常驻后台更稳定") },
                    text = {
                        Text(
                            "我们检测到您正在开启自动备份。\n\n" +
                            "为了确保照片能及时、稳定地在后台实时上传，是否允许 iClouDroid 忽略电池优化，保持常驻后台运行？\n\n" +
                            "如果不开启，系统可能会在息屏后暂停备份，需要重新打开应用才会继续。"
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.e("LocalAlbumsScreen", "Failed to launch battery optimization intent", e)
                                    android.widget.Toast.makeText(context, "设备不支持直接跳转，请到系统设置中手动开启", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                showBatteryOptimizationPrompt = false
                                pendingBatteryBucket?.let { viewModel.toggleAlbumBackup(it, true) }
                                pendingBatteryBucket = null
                            }
                        ) { Text("去开启") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showBatteryOptimizationPrompt = false
                                pendingBatteryBucket?.let { viewModel.toggleAlbumBackup(it, true) }
                                pendingBatteryBucket = null
                            }
                        ) { Text("暂不需要") }
                    }
                )
            }
        }
    }
}

@Composable
private fun BackupStatusCard(
    tasks: List<app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity>,
    forceUploadUntil: Long,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pendingCount = tasks.count { it.status == "PENDING" }
    val uploadingCount = tasks.count { it.status == "UPLOADING" }
    val failedCount = tasks.count { it.status == "FAILED" }
    val activeCount = pendingCount + uploadingCount
    val isForceActive = forceUploadUntil > System.currentTimeMillis()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (uploadingCount > 0) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (isForceActive) "⚡ 立即上传（持续1小时）" else if (activeCount > 0) "自动备份进行中" else "相册云端备份已同步",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = if (activeCount > 0) {
                            "剩余 $activeCount 项待上传" + if (failedCount > 0) " · $failedCount 项失败" else ""
                        } else {
                            "所有已勾选相册中的新照片已备份"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onOpenQueue,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("管理队列")
            }
        }
    }
}

@Composable
private fun AlbumCard(
    album: LocalAlbum,
    onToggleBackup: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onToggleBackup(!album.isBackupEnabled) }
    ) {
        Box {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                if (album.coverUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(album.coverUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(bottomStart = 10.dp, topEnd = 14.dp),
                color = if (album.isBackupEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (album.isBackupEnabled) Icons.Default.CloudUpload else Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (album.isBackupEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (album.isBackupEnabled) "已开启备份" else "未备份",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (album.isBackupEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${album.itemCount} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            androidx.compose.material3.Switch(
                checked = album.isBackupEnabled,
                onCheckedChange = onToggleBackup,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun PermissionRequestBanner(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "管理本地媒体文件夹",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "浏览各相册（如相机、微信、截屏、下载等）需要获取设备媒体文件的读取权限。后续可为您开启特定文件夹的自动增量云备份。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRequestPermission) {
                        Text("允许媒体访问")
                    }
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("权限设置")
                    }
                }
            }
        }
    }
}

/**
 * Explains `ACCESS_MEDIA_LOCATION` before it is requested, when the user first enables backup.
 *
 * Two things make an explanation necessary rather than optional:
 *
 *  - the permission has a misleading name. It cannot locate the device; it only lets an app read
 *    a photo or video's **already-recorded** position. Users reasonably assume otherwise.
 *  - it belongs to the undefined permission group, so it never appears in the system Settings
 *    list. A refusal is therefore close to irreversible, and must be presented as a real choice
 *    with a stated cost rather than a checkbox to click past.
 */
@Composable
private fun LocationPermissionDialog(
    onAllow: () -> Unit,
    onAllowWithout: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocationOff, contentDescription = null) },
        title = { Text("备份时包含照片位置？") },
        text = {
            Column {
                Text(
                    "开启备份需要「读取媒体位置」权限。它不会获取你的实时位置，" +
                        "只能读出照片/视频拍摄时已记录的坐标。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "允许后：云端照片和视频会带有拍摄地点，在 iPhone、Mac 上能看到地图。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "不允许：备份照常进行，只是不包含位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) { Text("允许") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAllowWithout) { Text("不带位置备份") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * Shown instead of the system dialog once the permission has been refused.
 *
 * `ACCESS_MEDIA_LOCATION` lives in `android.permission-group.UNDEFINED`, which Android's Settings
 * app does not render, so there is no "go and turn it on" path. Requesting again returns denied
 * without any UI. Saying that plainly is more useful than a button that silently does nothing.
 */
@Composable
private fun LocationPermanentlyDeniedDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("位置权限已被拒绝") },
        text = {
            Text(
                "这个权限不会出现在系统设置里，App 也无法再次弹窗申请。" +
                    "如果确实需要备份位置，只能卸载重装后再授权。\n\n" +
                    "备份本身不受影响，目前只缺少照片和视频的拍摄地点。",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

/**
 * A compact, non-blocking prompt for `ACCESS_MEDIA_LOCATION`.
 *
 * Deliberately not a full banner: the album grid must stay usable without it, because media
 * reading works fine on its own. This only affects whether GPS travels with the upload.
 */
@Composable
private fun LocationPermissionHint(
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "未授予定位信息权限，备份将丢失 GPS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRequest) { Text("授权") }
        }
    }
}

private fun checkMediaPermissions(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Whether GPS can be read out of MediaStore and the original file stream.
 *
 * Without this permission the platform redacts location: `setRequireOriginal()` throws
 * `UnsupportedOperationException` and the LATITUDE / LONGITUDE columns come back null, so backed-up
 * photos upload with no `{GPS}` block and videos with no ISO-6709 atom. Everything else still
 * works, which is exactly why the loss is easy to miss.
 */
internal fun hasMediaLocationPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
