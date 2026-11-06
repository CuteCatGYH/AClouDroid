package app.acloudroid.qwq.feature.drive.ui

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.acloudroid.qwq.data.settings.DriveDisplayMode
import app.acloudroid.qwq.data.settings.DriveSortField
import app.acloudroid.qwq.data.settings.DriveSortOrder
import app.acloudroid.qwq.di.rememberAppContainer
import app.acloudroid.qwq.feature.drive.data.DownloadStatus
import app.acloudroid.qwq.feature.drive.data.DriveDownloadTaskState
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.drive.data.db.DriveUploadEntity
import app.acloudroid.qwq.feature.drive.data.db.DriveUploadStatus
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory

@Composable
fun DriveEntry(
    viewModel: DriveViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    DriveScreen(viewModel = viewModel, contentPadding = contentPadding)
}

@Composable
fun DriveScreen(
    viewModel: DriveViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val repository = container.driveRepository
    val state by viewModel.uiState.collectAsState()
    val imageLoader = remember(context) {
        ICloudImageLoaderFactory.get(context, container)
    }

    var createFolderTarget by remember { mutableStateOf(false) }
    var renameTargetItem by remember { mutableStateOf<DriveItemEntity?>(null) }
    var moveTargetItem by remember { mutableStateOf<DriveItemEntity?>(null) }
    var deleteTargetItem by remember { mutableStateOf<DriveItemEntity?>(null) }

    var showTrashWebDialog by remember { mutableStateOf(false) }
    var showChinaUploadLimitDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris, context)
        }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(state.currentDrivewsid) {
        val pos = viewModel.getScrollPosition(state.currentDrivewsid)
        if (pos != null) {
            listState.scrollToItem(pos.first, pos.second)
            gridState.scrollToItem(pos.first, pos.second)
        } else {
            listState.scrollToItem(0, 0)
            gridState.scrollToItem(0, 0)
        }
    }

    DisposableEffect(state.currentDrivewsid) {
        onDispose {
            viewModel.saveScrollPosition(
                state.currentDrivewsid,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    val isPreviewOpen = state.previewingItem != null || state.webIWorkItem != null
    BackHandler(enabled = isPreviewOpen || state.canGoUp) {
        if (isPreviewOpen) {
            viewModel.closePreview()
        } else {
            viewModel.goUp()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = contentPadding.calculateBottomPadding())
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            DriveHeader(
                state = state,
                onCrumbClick = viewModel::navigateTo,
                onRefresh = viewModel::refresh,
                onSync = viewModel::syncIncremental,
                onCreateFolderClick = { createFolderTarget = true },
                onOpenTrashWebClick = { showTrashWebDialog = true },
                onSetDisplayMode = viewModel::setDisplayMode,
                onSetSortField = viewModel::setSortField,
                onToggleSortOrder = viewModel::toggleSortOrder
            )

            if (state.isSyncing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
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

                state.displayMode == DriveDisplayMode.GRID -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.items, key = { it.drivewsid }) { item ->
                            val taskState = state.downloadTasks[item.drivewsid]
                            val isIWork = isIWorkFile(item)
                            DriveGridItem(
                                item = item,
                                imageLoader = imageLoader,
                                onClick = {
                                    if (item.isContainer) {
                                        viewModel.enter(item)
                                    } else {
                                        viewModel.previewItem(item, context)
                                    }
                                },
                                onDownloadClick = { viewModel.downloadItemToDownloads(item) },
                                onCancelClick = { viewModel.cancelDownload(item.drivewsid) },
                                onRenameClick = { renameTargetItem = item },
                                onMoveClick = {
                                    viewModel.loadContainersForMove()
                                    moveTargetItem = item
                                },
                                onDeleteClick = { deleteTargetItem = item },
                                onShareClick = { viewModel.shareItem(item, context) },
                                onIWorkWebClick = if (isIWork) { { viewModel.openIWorkWebSuite(item) } } else null,
                                taskState = taskState
                            )
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
                            val isIWork = isIWorkFile(item)
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
                                        viewModel.enter(item)
                                    } else {
                                        viewModel.previewItem(item, context)
                                    }
                                },
                                onDownloadClick = { viewModel.downloadItemToDownloads(item) },
                                onCancelClick = { viewModel.cancelDownload(item.drivewsid) },
                                onRenameClick = { renameTargetItem = item },
                                onMoveClick = {
                                    viewModel.loadContainersForMove()
                                    moveTargetItem = item
                                },
                                onDeleteClick = { deleteTargetItem = item },
                                onShareClick = { viewModel.shareItem(item, context) },
                                onIWorkWebClick = if (isIWork) { { viewModel.openIWorkWebSuite(item) } } else null,
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

        // Mount D7 Overlays
        state.previewingItem?.let { previewItem ->
            DriveMediaViewer(
                item = previewItem,
                repository = repository,
                onDismiss = viewModel::closePreview
            )
        }

        state.webIWorkItem?.let { webItem ->
            val sessionManager = container.sessionManager
            DriveIWorkWebDialog(
                item = webItem,
                isChinaMainland = sessionManager.isChinaMainland(),
                onDismiss = viewModel::closePreview
            )
        }
    }

    if (createFolderTarget) {
        CreateFolderDialog(
            onDismiss = { createFolderTarget = false },
            onConfirm = { folderName ->
                createFolderTarget = false
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

    moveTargetItem?.let { item ->
        val containers by viewModel.allContainers.collectAsState()
        MoveDialog(
            item = item,
            allContainers = containers,
            currentFolderId = state.currentDrivewsid,
            onDismiss = { moveTargetItem = null },
            onConfirm = { targetDrivewsId ->
                moveTargetItem = null
                viewModel.moveItems(listOf(item), targetDrivewsId)
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
        val sessionManager = container.sessionManager
        DriveTrashWebDialog(
            isChinaMainland = sessionManager.isChinaMainland(),
            onDismiss = { showTrashWebDialog = false }
        )
    }

    if (showChinaUploadLimitDialog) {
        ChinaUploadNoticeDialog(
            onDismiss = { showChinaUploadLimitDialog = false },
            onContinue = {
                container.sessionManager.setHasShownChinaUploadNotice(true)
                showChinaUploadLimitDialog = false
                filePickerLauncher.launch("*/*")
            }
        )
    }
}

private fun isIWorkFile(item: DriveItemEntity): Boolean {
    val ext = item.extension?.lowercase()?.trim() ?: ""
    val name = item.name.lowercase()
    return ext == "pages" || ext == "numbers" || ext == "key" || ext == "keynote" ||
        name.contains("pages") || name.contains("numbers") || name.contains("keynote")
}

@Composable
private fun DriveHeader(
    state: DriveUiState,
    onCrumbClick: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onCreateFolderClick: () -> Unit,
    onOpenTrashWebClick: () -> Unit,
    onSetDisplayMode: (DriveDisplayMode) -> Unit,
    onSetSortField: (DriveSortField) -> Unit,
    onToggleSortOrder: () -> Unit
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

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

            // Display Mode Switcher
            IconButton(
                onClick = {
                    val newMode = if (state.displayMode == DriveDisplayMode.LIST) {
                        DriveDisplayMode.GRID
                    } else {
                        DriveDisplayMode.LIST
                    }
                    onSetDisplayMode(newMode)
                }
            ) {
                Icon(
                    imageVector = if (state.displayMode == DriveDisplayMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                    contentDescription = "切换显示视图"
                )
            }

            // Sort Menu Button
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(Icons.Default.Sort, contentDescription = "排序方式")
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    Text(
                        text = "排序维度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    DriveSortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = field.label,
                                    color = if (state.sortField == field) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                sortMenuExpanded = false
                                onSetSortField(field)
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "顺序: ${if (state.sortOrder == DriveSortOrder.ASCENDING) "正序 (A-Z)" else "倒序 (Z-A)"}"
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (state.sortOrder == DriveSortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            sortMenuExpanded = false
                            onToggleSortOrder()
                        }
                    )
                }
            }

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
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    onIWorkWebClick: (() -> Unit)? = null,
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (taskState?.status == DownloadStatus.DOWNLOADING || taskState?.status == DownloadStatus.QUEUED) {
                IconButton(onClick = onCancelClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消下载",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                if (item.isDownloadable) {
                    // Download button placed directly to the LEFT of the ⁝ button
                    IconButton(onClick = onDownloadClick) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "下载文件",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
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
                        if (item.isDownloadable) {
                            DropdownMenuItem(
                                text = { Text("预览") },
                                leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("下载落盘") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDownloadClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("分享") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onShareClick()
                                }
                            )
                            if (onIWorkWebClick != null) {
                                DropdownMenuItem(
                                    text = { Text("网页版 iWork 打开") },
                                    leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onIWorkWebClick()
                                    }
                                )
                            }
                            HorizontalDivider()
                        }
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
}

@Composable
private fun DriveGridItem(
    item: DriveItemEntity,
    imageLoader: coil.ImageLoader,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    onIWorkWebClick: (() -> Unit)? = null,
    taskState: DriveDownloadTaskState? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center
            ) {
                DriveItemLargeIcon(item = item, imageLoader = imageLoader)
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            val sub = subtitleFor(item)
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (taskState?.status == DownloadStatus.DOWNLOADING || taskState?.status == DownloadStatus.QUEUED) {
                    IconButton(onClick = onCancelClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "取消下载",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    if (item.isDownloadable) {
                        // Download button to the LEFT of the ⁝ button
                        IconButton(onClick = onDownloadClick, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "下载文件",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (item.isDownloadable) {
                                DropdownMenuItem(
                                    text = { Text("预览") },
                                    leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("下载落盘") },
                                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onDownloadClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("分享") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onShareClick()
                                    }
                                )
                                if (onIWorkWebClick != null) {
                                    DropdownMenuItem(
                                        text = { Text("网页版 iWork 打开") },
                                        leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            onIWorkWebClick()
                                        }
                                    )
                                }
                                HorizontalDivider()
                            }
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
    }
}

@Composable
private fun DriveItemLargeIcon(
    item: DriveItemEntity,
    imageLoader: coil.ImageLoader
) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val repository = container.driveRepository
    val ext = item.extension?.lowercase()?.trim() ?: ""

    val cachedFile = remember(item.drivewsid) {
        repository.previewCache.findPreviewFile(item.drivewsid)
    }

    var cloudThumbUrl by remember(item.drivewsid) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.drivewsid) {
        if (cachedFile == null && isMediaDriveItem(ext)) {
            cloudThumbUrl = repository.getOrFetchThumbnailUrl(item.drivewsid, item.size)
        }
    }

    val thumbData: Any? = cachedFile ?: cloudThumbUrl ?: item.iconUrl

    when {
        item.isContainer -> {
            DriveItemIcon(item = item, imageLoader = imageLoader, modifier = Modifier.size(56.dp))
        }
        thumbData != null && isMediaDriveItem(ext) -> {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbData)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        else -> {
            DriveItemIcon(item = item, imageLoader = imageLoader, modifier = Modifier.size(56.dp))
        }
    }
}

@Composable
private fun DriveItemIcon(
    item: DriveItemEntity,
    imageLoader: coil.ImageLoader,
    modifier: Modifier = Modifier.size(40.dp)
) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val repository = container.driveRepository
    val iconUrl = item.iconUrl
    val ext = item.extension?.lowercase()?.trim() ?: ""

    val cachedFile = remember(item.drivewsid) {
        repository.previewCache.findPreviewFile(item.drivewsid)
    }

    var cloudThumbUrl by remember(item.drivewsid) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.drivewsid) {
        if (cachedFile == null && isMediaDriveItem(ext)) {
            cloudThumbUrl = repository.getOrFetchThumbnailUrl(item.drivewsid, item.size)
        }
    }

    val thumbData: Any? = cachedFile ?: cloudThumbUrl ?: iconUrl

    when {
        item.type == DriveItemEntity.TYPE_APP_LIBRARY && !iconUrl.isNullOrBlank() -> {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(iconUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = modifier
            )
        }

        item.isContainer -> {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "文件夹",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier
            )
        }

        thumbData != null && isMediaDriveItem(ext) -> {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbData)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = modifier.clip(RoundedCornerShape(4.dp))
            )
        }

        ext in setOf("jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "bmp") -> {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = "图片",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier
            )
        }

        ext in setOf("mp4", "mov", "m4v", "avi", "mkv") -> {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = "视频",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = modifier
            )
        }

        ext in setOf("mp3", "m4a", "wav", "flac", "aac") -> {
            Icon(
                imageVector = Icons.Default.AudioFile,
                contentDescription = "音频",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = modifier
            )
        }

        ext == "pdf" -> {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = "PDF",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier
            )
        }

        ext in setOf("zip", "tar", "gz", "rar", "7z") -> {
            Icon(
                imageVector = Icons.Default.FolderZip,
                contentDescription = "压缩包",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = modifier
            )
        }

        ext in setOf("pages", "numbers", "key", "keynote", "doc", "docx", "xls", "xlsx", "ppt", "pptx") -> {
            Icon(
                imageVector = Icons.Default.Article,
                contentDescription = "文档",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier
            )
        }

        ext in setOf("txt", "json", "xml", "html", "kt", "java", "py", "md", "c", "cpp") -> {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = "文本",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier
            )
        }

        else -> {
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = "文件",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = modifier
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    bytes > 0 -> "$bytes B"
    else -> ""
}

@Composable
private fun subtitleFor(item: DriveItemEntity): String {
    if (item.isContainer) {
        val count = item.directChildrenCount
        val countText = if (count >= 0) "$count 项" else ""
        val modText = item.dateModified ?: ""
        return listOf(countText, modText).filter { it.isNotBlank() }.joinToString(" · ")
    }

    val sizeText = formatSize(item.size)
    val modText = item.dateModified ?: ""
    return listOf(sizeText, modText).filter { it.isNotBlank() }.joinToString(" · ")
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
private fun DriveUploadBanner(
    activeUploads: List<DriveUploadEntity>,
    onCancelUpload: (Long) -> Unit,
    onRetryUpload: (Long) -> Unit
) {
    if (activeUploads.isEmpty()) return

    var isExpanded by remember { mutableStateOf(false) }

    val queuedCount = activeUploads.count { it.status == DriveUploadStatus.QUEUED }
    val uploadingList = activeUploads.filter { it.status == DriveUploadStatus.UPLOADING }
    val failedList = activeUploads.filter { it.status == DriveUploadStatus.FAILED }

    val activeCount = queuedCount + uploadingList.size
    val headline = when {
        failedList.isNotEmpty() && activeCount > 0 ->
            "正在上传 $activeCount 个文件 · ${failedList.size} 个失败"
        failedList.isNotEmpty() ->
            "${failedList.size} 个上传失败"
        uploadingList.isNotEmpty() ->
            "正在上传 ${uploadingList.size} 个文件" + if (queuedCount > 0) "（$queuedCount 排队中）" else ""
        queuedCount > 0 ->
            "$queuedCount 个文件排队上传中…"
        else -> ""
    }

    if (headline.isBlank()) return

    val containerColor = if (failedList.isNotEmpty()) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (failedList.isNotEmpty()) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "折叠详情" else "展开详情"
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                ) {
                    HorizontalDivider(
                        color = contentColor.copy(alpha = 0.2f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    activeUploads.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.filename,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val statusText = when (item.status) {
                                    DriveUploadStatus.QUEUED -> "排队中…"
                                    DriveUploadStatus.UPLOADING -> {
                                        val pct = if (item.filesize > 0) ((item.bytesUploaded * 100) / item.filesize).toInt().coerceIn(0, 100) else 0
                                        "传输中 · $pct%"
                                    }
                                    DriveUploadStatus.FAILED -> "失败: ${item.errorMessage ?: "未知错误"}"
                                    DriveUploadStatus.SUCCESS -> "成功"
                                    else -> item.status
                                }
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.status == DriveUploadStatus.FAILED) {
                                TextButton(onClick = { onRetryUpload(item.id) }) {
                                    Text("重试", color = contentColor)
                                }
                            }
                            IconButton(
                                onClick = { onCancelUpload(item.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "取消上传",
                                    tint = contentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("文件夹名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(folderName) },
                enabled = folderName.isNotBlank()
            ) { Text("新建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RenameDialog(
    item: DriveItemEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name != item.name
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun MoveDialog(
    item: DriveItemEntity,
    allContainers: List<DriveItemEntity>,
    currentFolderId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedId by remember { mutableStateOf(currentFolderId) }
    val candidates = remember(allContainers, item.drivewsid) {
        allContainers.filter { it.drivewsid != item.drivewsid }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动「${item.name}」到") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(candidates, key = { it.drivewsid }) { container ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedId = container.drivewsid }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedId == container.drivewsid,
                            onClick = { selectedId = container.drivewsid }
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = container.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedId) },
                enabled = selectedId.isNotBlank() && selectedId != currentFolderId
            ) { Text("移动") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
        text = { Text("确定要将「${item.name}」移入最近删除吗？可在网页端恢复或彻底清除。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("移入最近删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun DriveTrashWebDialog(
    isChinaMainland: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val url = if (isChinaMainland) "https://www.icloud.com.cn/iclouddrive/" else "https://www.icloud.com/iclouddrive/"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("最近删除 (网页端)") },
        text = {
            Column {
                Text("iCloud Drive 云盘删除文件保留在苹果云端回收站。可在网页版直接恢复或彻底清空。")
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("前往 iCloud 云盘网页端", style = MaterialTheme.typography.titleSmall)
                            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun ChinaUploadNoticeDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("云上贵州账号上传限制说明") },
        text = {
            Text(
                "您的账号为云上贵州（中国大陆）节点。实测苹果服务端针对该区域单次文件上传请求限制为 1 GiB (~1073 MB)。\n\n超过 1 GiB 的文件上传将会被云端拒绝，本应用将在选文件后自动进行预检提示。"
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text("知道了并继续") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
