package app.acloudroid.qwq.feature.drive.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import app.acloudroid.qwq.di.rememberAppContainer
import app.acloudroid.qwq.feature.drive.data.DownloadStatus
import app.acloudroid.qwq.feature.drive.data.DriveDownloadTaskState
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.drive.data.db.DriveUploadEntity
import app.acloudroid.qwq.feature.drive.data.db.DriveUploadStatus
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Entry point that builds the [DriveViewModel] from the app container.
 *
 * Kept separate from [DriveScreen] so the screen itself stays a pure function of its state —
 * which is what makes it reviewable without knowing how the container is wired.
 */
@Composable
fun DriveEntry(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val container = rememberAppContainer()
    val viewModel = remember(container) {
        DriveViewModel(container.driveRepository)
    }
    DriveScreen(
        viewModel = viewModel,
        modifier = modifier,
        contentPadding = contentPadding
    )
}

/**
 * iCloud Drive browser.
 *
 * ## Layout rules that come from earlier bugs
 *
 * - **Single `LazyColumn`.** No nested lazy grid (PITFALLS §10): the photo gallery lost rows to
 *   exactly that, because two independent measure loops desynchronise during a fling.
 * - **Back is handled here, not by the system stack.** This screen is a tab inside `AppShell`,
 *   not a route, so `BackHandler` pops one folder level and only exits the app at the root
 *   (PITFALLS §20 — otherwise a side-swipe exits the app from three folders deep).
 */
@Composable
fun DriveScreen(
    viewModel: DriveViewModel,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val allContainers by viewModel.allContainers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = rememberAppContainer()
    val imageLoader = ICloudImageLoaderFactory.get(context, container)
    var showChinaUploadLimitDialog by remember { mutableStateOf(false) }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showTrashWebDialog by remember { mutableStateOf(false) }
    var renameTargetItem by remember { mutableStateOf<DriveItemEntity?>(null) }
    var deleteTargetItem by remember { mutableStateOf<DriveItemEntity?>(null) }
    var moveTargetItem by remember { mutableStateOf<DriveItemEntity?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris, context)
        }
    }

    if (showChinaUploadLimitDialog) {
        AlertDialog(
            onDismissRequest = {
                showChinaUploadLimitDialog = false
            },
            title = {
                Text("云上贵州账号上传提示")
            },
            text = {
                Text("由于 Apple 服务端区域限制，云上贵州（中国大陆）账号暂不支持超过 1 GB 的单文件上传。超过 1 GB 的文件上传将在服务端被拒绝。\n\n国区特有限制，外区账号不受此限制。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showChinaUploadLimitDialog = false
                        container.sessionManager.setHasShownChinaUploadNotice(true)
                        filePickerLauncher.launch("*/*")
                    }
                ) {
                    Text("我知道了")
                }
            }
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { folderName ->
                showCreateFolderDialog = false
                viewModel.createFolder(folderName)
            }
        )
    }

    renameTargetItem?.let { item ->
        RenameDialog(
            item = item,
            onDismiss = { renameTargetItem = null },
            onConfirm = { newName ->
                renameTargetItem = null
                viewModel.renameItem(item, newName)
            }
        )
    }

    deleteTargetItem?.let { item ->
        DeleteConfirmDialog(
            item = item,
            onDismiss = { deleteTargetItem = null },
            onConfirm = {
                deleteTargetItem = null
                viewModel.deleteItems(listOf(item))
            }
        )
    }

    if (showTrashWebDialog) {
        ICloudDriveTrashWebDialog(
            onDismiss = { showTrashWebDialog = false }
        )
    }

    moveTargetItem?.let { item ->
        MoveTargetPickerModal(
            item = item,
            viewModel = viewModel,
            onDismiss = { moveTargetItem = null },
            onConfirm = { targetFolderId ->
                moveTargetItem = null
                viewModel.moveItems(listOf(item), targetFolderId)
            }
        )
    }

    val listState = rememberLazyListState()

    LaunchedEffect(state.currentDrivewsid) {
        val pos = viewModel.getScrollPosition(state.currentDrivewsid)
        if (pos != null) {
            listState.scrollToItem(pos.first, pos.second)
        } else {
            listState.scrollToItem(0, 0)
        }
    }

    // Intercept back while inside a folder. At the root we let the event through so the shell
    // behaves normally.
    BackHandler(enabled = state.canGoUp) {
        viewModel.saveScrollPosition(
            state.currentDrivewsid,
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset
        )
        viewModel.goUp()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            DriveHeader(
                state = state,
                onCrumbClick = { index ->
                    viewModel.saveScrollPosition(
                        state.currentDrivewsid,
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset
                    )
                    viewModel.navigateTo(index)
                },
                onRefresh = viewModel::refresh,
                onSync = viewModel::syncIncremental,
                onCreateFolderClick = { showCreateFolderDialog = true },
                onOpenTrashWebClick = { showTrashWebDialog = true }
            )

            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.activeUploads.isNotEmpty()) {
                DriveUploadBanner(
                    activeUploads = state.activeUploads,
                    onCancelUpload = viewModel::cancelUpload,
                    onRetryUpload = viewModel::retryUpload
                )
            }

            state.lastSyncSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp)
                )
            }

            state.errorMessage?.let { message ->
                DriveErrorBanner(message = message, onDismiss = viewModel::dismissError)
            }

            state.downloadMessage?.let { message ->
                DriveNoticeBanner(message = message, onDismiss = viewModel::dismissDownloadMessage)
            }

            when {
                state.isInitialLoading && state.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "此文件夹为空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.isRefreshing) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "正在重新检查…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.items, key = { it.drivewsid }) { item ->
                            val taskState = state.downloadTasks[item.drivewsid]
                            DriveItemRow(
                                item = item,
                                imageLoader = imageLoader,
                                onClick = {
                                    if (item.isContainer) {
                                        viewModel.saveScrollPosition(
                                            state.currentDrivewsid,
                                            listState.firstVisibleItemIndex,
                                            listState.firstVisibleItemScrollOffset
                                        )
                                        viewModel.open(item)
                                    } else {
                                        viewModel.onFileClick(item, context)
                                    }
                                },
                                onCancelClick = {
                                    viewModel.cancelDownload(item.drivewsid)
                                },
                                onRenameClick = { renameTargetItem = item },
                                onMoveClick = {
                                    viewModel.loadContainersForMove()
                                    moveTargetItem = item
                                },
                                onDeleteClick = { deleteTargetItem = item },
                                taskState = taskState
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                val sessionManager = container.sessionManager
                val isChina = sessionManager.isChinaMainland()
                val hasShownNotice = sessionManager.hasShownChinaUploadNotice()
                if (isChina && !hasShownNotice) {
                    showChinaUploadLimitDialog = true
                } else {
                    filePickerLauncher.launch("*/*")
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(contentPadding)
                .padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "上传文件"
            )
        }
    }
}

@Composable
private fun DriveHeader(
    state: DriveUiState,
    onCrumbClick: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onCreateFolderClick: () -> Unit,
    onOpenTrashWebClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.currentName,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCreateFolderClick) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
            }
            IconButton(onClick = onOpenTrashWebClick) {
                Icon(Icons.Default.Delete, contentDescription = "最近删除 (网页端)")
            }
            IconButton(onClick = onSync, enabled = !state.isSyncing) {
                Icon(Icons.Default.Sync, contentDescription = "增量同步")
            }
            IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        if (state.trail.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(state.trail.size) { index ->
                    val crumb = state.trail[index]
                    val isLast = index == state.trail.lastIndex
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = crumb.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isLast) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = !isLast) { onCrumbClick(index) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        if (!isLast) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveItemRow(
    item: DriveItemEntity,
    imageLoader: coil.ImageLoader,
    onClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    taskState: DriveDownloadTaskState? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DriveItemIcon(item = item, imageLoader = imageLoader)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = subtitleFor(item)
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (taskState?.status == DownloadStatus.DOWNLOADING) {
                Spacer(Modifier.height(4.dp))
                val progress = (taskState.progressPercent / 100f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }

        if (taskState?.status == DownloadStatus.DOWNLOADING || taskState?.status == DownloadStatus.QUEUED) {
            IconButton(onClick = onCancelClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "取消下载",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRenameClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("移动") },
                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onMoveClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("移入最近删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDeleteClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DriveErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭提示")
            }
        }
    }
}

@Composable
private fun DriveNoticeBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭提示")
            }
        }
    }
}

@Composable
private fun DriveItemIcon(
    item: DriveItemEntity,
    imageLoader: coil.ImageLoader
) {
    val remoteIcon = item.iconUrl
    val boxModifier = Modifier.size(36.dp)

    val fallbackIcon = if (item.isContainer) {
        Icons.Default.Folder
    } else {
        Icons.AutoMirrored.Filled.InsertDriveFile
    }

    when {
        !remoteIcon.isNullOrBlank() -> {
            Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = remoteIcon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    imageLoader = imageLoader,
                    error = rememberVectorPainter(fallbackIcon),
                    fallback = rememberVectorPainter(fallbackIcon),
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        item.isContainer -> {
            Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        else -> {
            Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("文件夹名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("新建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun RenameDialog(
    item: DriveItemEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(item.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("新名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text != item.displayName
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    item: DriveItemEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移入最近删除") },
        text = { Text("确定要将“${item.displayName}”移入 iCloud Drive 最近删除吗？") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("移入最近删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ICloudDriveTrashWebDialog(
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "最近删除 (iCloud 网页版)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "https://www.icloud.com.cn/iclouddrive/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                app.acloudroid.qwq.ui.FeatureWebViewScreen(url = "https://www.icloud.com.cn/iclouddrive/")
            }
        }
    }
}

private data class FolderNavNode(val drivewsid: String, val name: String)

@Composable
private fun MoveTargetPickerModal(
    item: DriveItemEntity,
    viewModel: DriveViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pathStack by remember {
        mutableStateOf(listOf(FolderNavNode(DriveItemEntity.ROOT_DRIVEWSID, "iCloud Drive")))
    }
    val currentFolder = pathStack.last()

    val subFolders by remember(currentFolder.drivewsid) {
        viewModel.observeFolderContainers(currentFolder.drivewsid)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val validFolders = remember(subFolders, item) {
        subFolders.filter { it.drivewsid != item.drivewsid }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "移动“${item.displayName}”至",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.size > 1) {
                        IconButton(
                            onClick = { pathStack = pathStack.dropLast(1) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回上一级",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = pathStack.joinToString(" > ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            if (validFolders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "该目录下无可用子文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(validFolders, key = { it.drivewsid }) { containerItem ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    pathStack = pathStack + FolderNavNode(containerItem.drivewsid, containerItem.displayName)
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = containerItem.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (containerItem.directChildrenCount > 0) {
                                    Text(
                                        text = "${containerItem.directChildrenCount} 项",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "进入",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(currentFolder.drivewsid) },
                enabled = currentFolder.drivewsid != item.parentDrivewsid
            ) {
                Text("移动至此 (${currentFolder.name})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun subtitleFor(item: DriveItemEntity): String {
    val parts = mutableListOf<String>()

    if (item.type == DriveItemEntity.TYPE_FILE && item.size > 0) {
        parts += formatSize(item.size)
    }

    if (item.isContainer && item.directChildrenCount > 0) {
        parts += "${item.directChildrenCount} 项"
    } else if (item.isContainer && !item.childrenFetched) {
        // Count not yet known to be accurate — say nothing rather than "0 项".
    }

    val stamp = item.dateModified ?: item.dateCreated
    formatIsoDate(stamp)?.let { parts += it }

    return parts.joinToString(" · ")
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private val ISO_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatIsoDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val instant = Instant.parse(raw)
        ISO_FORMATTER.withZone(ZoneId.systemDefault()).format(instant)
    }.getOrNull()
}

@Composable
private fun DriveUploadBanner(
    activeUploads: List<DriveUploadEntity>,
    onCancelUpload: (Long) -> Unit,
    onRetryUpload: (Long) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val count = activeUploads.size
    val current = activeUploads.firstOrNull()

    // A banner made only of failures is an error report, not progress. Treat the two states
    // differently: spinner + "正在上传" is actively misleading once everything has failed.
    val failedCount = activeUploads.count { it.status == DriveUploadStatus.FAILED }
    val activeCount = activeUploads.count {
        it.status == DriveUploadStatus.QUEUED || it.status == DriveUploadStatus.UPLOADING
    }
    val hasActive = activeCount > 0
    val containerColor = if (hasActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (hasActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasActive) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = contentColor
                    )
                } else {
                    // Uses the already-imported Close glyph rather than pulling in the material-icons
                    // extended artifact just for a warning triangle.
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = contentColor
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            hasActive -> "正在上传 ($activeCount 个文件)"
                            failedCount > 0 -> "上传失败 ($failedCount 个文件)"
                            else -> "上传"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    current?.let { upload ->
                        Text(
                            text = "${upload.filename} · ${uploadStatusLabel(upload)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = contentColor
                )
            }

            if (isExpanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = contentColor.copy(alpha = 0.2f))
                Spacer(Modifier.height(4.dp))

                for (upload in activeUploads) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = contentColor
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = upload.filename,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatSize(upload.filesize)} · ${uploadStatusLabel(upload)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.7f),
                                // The reason a large file was rejected can be a full sentence;
                                // clamping it to one line is how the cause got hidden before.
                                maxLines = if (upload.status == DriveUploadStatus.FAILED) 4 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (upload.status == DriveUploadStatus.UPLOADING) {
                                Spacer(Modifier.height(2.dp))
                                val progress = if (upload.filesize > 0) {
                                    (upload.bytesUploaded.toFloat() / upload.filesize.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                // Indeterminate until the phase actually has a number to show.
                                // A determinate bar pinned at 0% is indistinguishable from a
                                // hung transfer, and that is precisely what the hash phase used
                                // to render for the first several minutes.
                                val hasMeasurableProgress = upload.speedText?.contains('%') == true
                                if (hasMeasurableProgress) {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(3.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(3.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (upload.status == DriveUploadStatus.FAILED) {
                            IconButton(
                                onClick = { onRetryUpload(upload.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "重试上传",
                                    modifier = Modifier.size(18.dp),
                                    tint = contentColor
                                )
                            }
                        }
                        IconButton(
                            onClick = { onCancelUpload(upload.id) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                // A finished (failed) row is dismissed, not cancelled.
                                contentDescription = if (upload.status == DriveUploadStatus.FAILED) {
                                    "移除记录"
                                } else {
                                    "取消上传"
                                },
                                modifier = Modifier.size(18.dp),
                                tint = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One label source for every place an upload's state is rendered.
 *
 * The two call sites (collapsed header and expanded row) used to carry identical `when` blocks,
 * which is how "传输中..." ended up as the shared fallback for both the queued and the hashing
 * state — the latter being a multi-minute window on large files where the text never changed.
 * The Go core now reports an explicit phase, so [DriveUploadEntity.speedText] is authoritative
 * whenever it is non-blank.
 */
private fun uploadStatusLabel(upload: DriveUploadEntity): String {
    return when (upload.status) {
        DriveUploadStatus.QUEUED -> "排队中"
        DriveUploadStatus.SUCCESS -> "已完成"
        DriveUploadStatus.FAILED -> upload.errorMessage?.takeIf { it.isNotBlank() } ?: "上传失败"
        else -> upload.speedText?.takeIf { it.isNotBlank() } ?: "准备中..."
    }
}
