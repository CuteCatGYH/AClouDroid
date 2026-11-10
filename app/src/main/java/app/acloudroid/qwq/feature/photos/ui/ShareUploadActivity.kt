package app.acloudroid.qwq.feature.photos.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.MainActivity
import app.acloudroid.qwq.R
import app.acloudroid.qwq.feature.drive.data.DriveRepository
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.photos.data.ShareUploadHandler
import app.acloudroid.qwq.ui.theme.IClouDroidTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.util.Locale

class ShareUploadActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()

        val incomingUris = ShareUploadHandler.extractUrisFromIntent(intent)
        if (incomingUris.isEmpty()) {
            Toast.makeText(this, R.string.share_upload_no_items, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val app = applicationContext as IClouDroidApp
        val container = app.container
        val sessionManager = container.sessionManager
        val appSettings = container.appSettings
        val driveRepository = container.driveRepository

        val dsid = sessionManager.getDsid()
        val cookie = sessionManager.getCookieString()
        val isLoggedIn = !dsid.isNullOrBlank() && !cookie.isNullOrBlank()

        setContent {
            val themeMode by appSettings.themeMode.collectAsState()
            val useDynamicColor by appSettings.useDynamicColor.collectAsState()
            val coroutineScope = rememberCoroutineScope()

            IClouDroidTheme(
                themeMode = themeMode,
                dynamicColor = useDynamicColor
            ) {
                if (!isLoggedIn) {
                    NotLoggedInBottomSheet(
                        onLoginClick = {
                            val mainIntent = Intent(this@ShareUploadActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            startActivity(mainIntent)
                            finish()
                        },
                        onDismiss = { finish() }
                    )
                } else {
                    ShareUploadBottomSheet(
                        uris = incomingUris,
                        driveRepository = driveRepository,
                        onConfirmUploadPhoto = { items ->
                            coroutineScope.launch {
                                val result = ShareUploadHandler.enqueueShareItems(
                                    context = applicationContext,
                                    uploadQueueDao = container.photoDatabase.uploadQueueDao(),
                                    items = items
                                )
                                val msg = when {
                                    result.unreadable.isNotEmpty() && result.enqueued == 0 ->
                                        getString(
                                            R.string.share_upload_unreadable,
                                            result.unreadable.first()
                                        )
                                    result.unreadable.isNotEmpty() ->
                                        getString(R.string.share_upload_enqueued_photo) +
                                            " (${result.enqueued})\n" +
                                            getString(
                                                R.string.share_upload_unreadable,
                                                result.unreadable.first()
                                            )
                                    else ->
                                        getString(R.string.share_upload_enqueued_photo) +
                                            if (result.enqueued > 0) " (${result.enqueued})" else ""
                                }
                                Toast.makeText(
                                    applicationContext,
                                    msg,
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        },
                        onConfirmUploadDrive = { items, targetFolderId, targetFolderPath, folderName ->
                            coroutineScope.launch {
                                val result = ShareUploadHandler.enqueueDriveShareItems(
                                    driveRepository = driveRepository,
                                    items = items,
                                    targetFolderDrivewsId = targetFolderId,
                                    targetFolderPath = targetFolderPath
                                )
                                val totalProcessed = result.enqueuedCount + result.dedupedCount +
                                        result.revivedCount + result.renamedCount
                                val folderText = if (folderName.isNotBlank()) " [$folderName]" else ""
                                val msg = when {
                                    result.unreadable.isNotEmpty() && totalProcessed == 0 ->
                                        getString(
                                            R.string.share_upload_unreadable,
                                            result.unreadable.first()
                                        )
                                    result.unreadable.isNotEmpty() ->
                                        getString(R.string.share_upload_enqueued_drive) +
                                            "$folderText (${totalProcessed})\n" +
                                            getString(
                                                R.string.share_upload_unreadable,
                                                result.unreadable.first()
                                            )
                                    else ->
                                        getString(R.string.share_upload_enqueued_drive) +
                                            "$folderText (${totalProcessed})"
                                }
                                Toast.makeText(
                                    applicationContext,
                                    msg,
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotLoggedInBottomSheet(
    onLoginClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.share_to_icloud_label),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.share_upload_not_logged_in),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.share_upload_cancel))
                }

                Button(
                    onClick = onLoginClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.share_upload_login_now))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareUploadBottomSheet(
    uris: List<Uri>,
    driveRepository: DriveRepository,
    onConfirmUploadPhoto: (List<ShareUploadHandler.ShareItem>) -> Unit,
    onConfirmUploadDrive: (items: List<ShareUploadHandler.ShareItem>, targetFolderId: String, targetFolderPath: String, folderName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var resolvedItems by remember { mutableStateOf<List<ShareUploadHandler.ShareItem>>(emptyList()) }

    var showDriveFolderPicker by remember { mutableStateOf(false) }
    var containers by remember { mutableStateOf<List<DriveItemEntity>>(emptyList()) }
    var isLoadingContainers by remember { mutableStateOf(false) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_MEDIA_LOCATION] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(uris, hasLocationPermission) {
        isLoading = true
        resolvedItems = ShareUploadHandler.resolveShareItems(context, uris)
        isLoading = false
    }

    val isAllMedia = remember(resolvedItems) {
        resolvedItems.isNotEmpty() && resolvedItems.all { item ->
            item.mimeType.startsWith("image/") || item.mimeType.startsWith("video/")
        }
    }

    if (showDriveFolderPicker) {
        DriveFolderPickerDialog(
            containers = containers,
            isLoading = isLoadingContainers,
            onDismiss = { showDriveFolderPicker = false },
            onConfirm = { folderId, folderPath, folderName ->
                showDriveFolderPicker = false
                onConfirmUploadDrive(resolvedItems, folderId, folderPath, folderName)
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.share_to_icloud_label),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (!isLoading) {
                        val totalSize = resolvedItems.sumOf { it.fileSize }
                        val sizeText = if (totalSize > 0) " (${formatFileSize(totalSize)})" else ""
                        val itemTypeHint = if (isAllMedia) "照片/视频" else "文件"
                        Text(
                            text = "准备发送 ${resolvedItems.size} 项$itemTypeHint$sizeText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.share_upload_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                }
            } else {
                // Horizontal list of thumbnails/cards
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(resolvedItems) { item ->
                        ShareItemThumbnailCard(item = item)
                    }
                }
            }

            // Location permission guidance for Photos (if all media and location permission missing)
            if (isAllMedia && !hasLocationPermission) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "授予『照片和视频』权限",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.share_upload_location_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
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
                                locationPermissionLauncher.launch(permissions)
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.share_upload_grant_location),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // WeChat Action Sheet Style Options Menu
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column {
                    if (isAllMedia) {
                        ShareOptionRow(
                            icon = Icons.Default.Photo,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.share_option_icloud_photo),
                            subtitle = "保存至 iCloud 照片图库",
                            enabled = !isLoading && resolvedItems.isNotEmpty(),
                            onClick = { onConfirmUploadPhoto(resolvedItems) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }

                    ShareOptionRow(
                        icon = Icons.Default.CloudUpload,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.share_option_icloud_drive),
                        subtitle = "选择目录并保存至 iCloud 云盘",
                        enabled = !isLoading && resolvedItems.isNotEmpty(),
                        onClick = {
                            coroutineScope.launch {
                                isLoadingContainers = true
                                showDriveFolderPicker = true
                                containers = driveRepository.getAllContainers()
                                isLoadingContainers = false
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cancel Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onDismiss),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.share_upload_cancel),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareOptionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
    }
}

private data class PickerCrumb(
    val drivewsid: String,
    val name: String
)

@Composable
private fun DriveFolderPickerDialog(
    containers: List<DriveItemEntity>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (folderId: String, folderPath: String, folderName: String) -> Unit
) {
    HierarchicalFolderPickerDialog(
        title = stringResource(R.string.share_drive_select_target_folder),
        confirmButtonText = stringResource(R.string.share_upload_confirm),
        allContainers = containers,
        isLoading = isLoading,
        initialFolderId = DriveItemEntity.ROOT_DRIVEWSID,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun HierarchicalFolderPickerDialog(
    title: String,
    confirmButtonText: String,
    allContainers: List<DriveItemEntity>,
    isLoading: Boolean = false,
    initialFolderId: String = DriveItemEntity.ROOT_DRIVEWSID,
    excludedFolderId: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (folderId: String, folderPath: String, folderName: String) -> Unit
) {
    val defaultRootName = stringResource(R.string.share_drive_root_folder)

    var trail by remember(allContainers, initialFolderId) {
        val containerMap = allContainers.associateBy { it.drivewsid }
        val chain = mutableListOf<PickerCrumb>()
        var curr: String? = initialFolderId
        while (curr != null) {
            if (curr == DriveItemEntity.ROOT_DRIVEWSID) {
                chain.add(0, PickerCrumb(DriveItemEntity.ROOT_DRIVEWSID, defaultRootName))
                break
            }
            val c = containerMap[curr]
            if (c != null) {
                chain.add(0, PickerCrumb(c.drivewsid, c.name))
                curr = c.parentDrivewsid
            } else {
                chain.add(0, PickerCrumb(DriveItemEntity.ROOT_DRIVEWSID, defaultRootName))
                break
            }
        }
        if (chain.isEmpty()) {
            chain.add(PickerCrumb(DriveItemEntity.ROOT_DRIVEWSID, defaultRootName))
        }
        mutableStateOf<List<PickerCrumb>>(chain)
    }

    val currentCrumb = trail.last()
    val currentFolderId = currentCrumb.drivewsid
    val currentFolderName = currentCrumb.name

    val currentFolderPath = remember(trail) {
        if (trail.size <= 1) "/" else "/" + trail.drop(1).joinToString("/") { it.name }
    }

    val childFolders = remember(allContainers, currentFolderId, excludedFolderId) {
        val containerMap = allContainers.associateBy { it.drivewsid }
        fun isExcluded(id: String): Boolean {
            if (excludedFolderId == null) return false
            var curr: String? = id
            while (curr != null) {
                if (curr == excludedFolderId) return true
                if (curr == DriveItemEntity.ROOT_DRIVEWSID) break
                curr = containerMap[curr]?.parentDrivewsid
            }
            return false
        }

        allContainers.filter { container ->
            container.parentDrivewsid == currentFolderId && !isExcluded(container.drivewsid)
        }.sortedBy { it.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (trail.size > 1) {
                        IconButton(
                            onClick = { if (trail.size > 1) trail = trail.dropLast(1) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回上级",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = currentFolderPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Active Target Banner
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "当前选定: $currentFolderName",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (childFolders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "此目录下无子文件夹",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                        ) {
                            items(childFolders, key = { it.drivewsid }) { subfolder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            trail = trail + PickerCrumb(subfolder.drivewsid, subfolder.name)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = subfolder.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "进入子目录",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(currentFolderId, currentFolderPath, currentFolderName) },
                enabled = !isLoading
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.share_upload_cancel))
            }
        }
    )
}

@Composable
private fun ShareItemThumbnailCard(
    item: ShareUploadHandler.ShareItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isMedia = item.mimeType.startsWith("image/") || item.mimeType.startsWith("video/")

    Box(
        modifier = modifier
            .size(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        if (isMedia) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.uri)
                    .crossfade(true)
                    .build(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )

            // Video Indicator Overlay
            if (item.mimeType.startsWith("video/")) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Motion Photo Badge Overlay
            if (item.isMotionPhoto) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            // General File Icon Display
            val icon = getFileIcon(item.mimeType, item.displayName)
            val ext = item.displayName.substringAfterLast('.', "").uppercase()

            Column(
                modifier = Modifier
                    .matchParentSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (ext.isNotBlank() && ext.length <= 5) ext else "FILE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun getFileIcon(mimeType: String, filename: String): ImageVector {
    if (mimeType.startsWith("audio/")) return Icons.Default.AudioFile
    if (mimeType.startsWith("video/")) return Icons.Default.Movie
    if (mimeType == "application/pdf") return Icons.Default.PictureAsPdf
    if (mimeType.contains("zip") || mimeType.contains("compressed") || mimeType.contains("tar")) return Icons.Default.FolderZip
    if (mimeType.contains("text") || mimeType.contains("json") || mimeType.contains("xml")) return Icons.Default.Description

    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> Icons.Default.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Default.FolderZip
        "txt", "md", "json", "xml", "html" -> Icons.Default.Description
        "doc", "docx", "pages" -> Icons.Default.Description
        else -> Icons.Default.Description
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val k = 1024.0
    val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = (Math.log(bytes.toDouble()) / Math.log(k)).toInt().coerceIn(0, sizes.size - 1)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(k, i.toDouble()), sizes[i])
}
