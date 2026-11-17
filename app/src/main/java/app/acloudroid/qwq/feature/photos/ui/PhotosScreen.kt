package app.acloudroid.qwq.feature.photos.ui

import app.acloudroid.qwq.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.acloudroid.qwq.data.settings.GalleryViewMode
import app.acloudroid.qwq.feature.photos.data.DayBucketing
import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity
import kotlinx.coroutines.launch

/**
 * Modern Glimpse-style Photo Gallery screen.
 *
 * Emits flat rows into a single LazyColumn to prevent nested lazy layout measurement cycles
 * and scroll clamp/jump (ISS-027). Supports Day and Month view modes seamlessly.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onOpenAccountInfo: () -> Unit,
    modifier: Modifier = Modifier,
    isViewerOpen: Boolean = false,
    onViewerOpenChange: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val monthIndex by viewModel.monthIndex.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()
    val monthPhotos by viewModel.monthPhotos.collectAsStateWithLifecycle()
    val monthDaysMap by viewModel.monthDaysMap.collectAsStateWithLifecycle()
    val viewMode by viewModel.galleryViewMode.collectAsStateWithLifecycle()
    val monthCoverEnabled by viewModel.monthCoverEnabled.collectAsStateWithLifecycle()

    val gridColumns = if (viewMode == GalleryViewMode.DAY) 3 else 5

    val listState = rememberLazyListState()
    val thumbnailBounds = rememberThumbnailBoundsTracker()
    val scope = rememberCoroutineScope()

    val monthKeys = remember(monthIndex) { monthIndex.map { it.yearMonth } }

    val currentMonth by remember(listState, monthKeys) {
        derivedStateOf { monthKeyAt(listState, monthKeys) }
    }

    val showScrollToTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 3 }
    }

    LaunchedEffect(monthKeys) {
        if (monthKeys.isNotEmpty()) {
            viewModel.subscribeToAllMonths(monthKeys)
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.onScreenLeaving()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val viewerTransition = rememberViewerTransitionState()

    Scaffold(
        topBar = {
            if (isViewerOpen) return@Scaffold
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Photo",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            if (totalCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = "$totalCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = when {
                                totalCount > 0 && scanProgress.isScanning -> stringResource(R.string.photos_title_scanning)
                                totalCount > 0 -> stringResource(R.string.photos_title_all)
                                else -> stringResource(R.string.photos_title_syncing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Day / Month View Switcher Capsule
                    ViewModeToggleCapsule(
                        currentMode = viewMode,
                        onModeSelected = { newMode ->
                            if (newMode != viewMode) {
                                val anchor = GalleryViewModeSync.currentVisibleAnchor(
                                    state = listState,
                                    months = monthIndex,
                                    monthPhotos = monthPhotos,
                                    monthDaysMap = monthDaysMap,
                                    viewMode = viewMode
                                )
                                viewModel.setGalleryViewMode(newMode)
                                val targetIdx = if (newMode == GalleryViewMode.MONTH) {
                                    GalleryViewModeSync.listIndexOfMonthView(
                                        months = monthIndex,
                                        columns = 5,
                                        targetYearMonth = anchor.yearMonth,
                                        targetPhotoIndexInMonth = anchor.photoIndexInMonth ?: 0
                                    )
                                } else {
                                    GalleryViewModeSync.listIndexOfDayView(
                                        months = monthIndex,
                                        monthDaysMap = monthDaysMap,
                                        columns = 3,
                                        targetYearMonth = anchor.yearMonth,
                                        targetDayKey = anchor.dayKey
                                    )
                                }
                                if (targetIdx >= 0) {
                                    scope.launch { listState.scrollToItem(targetIdx) }
                                }
                            }
                        }
                    )

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = { viewModel.syncPhotos() },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.photos_action_sync),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    IconButton(onClick = onOpenAccountInfo) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.photos_action_account),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding()
                )
                .graphicsLayer {
                    val p = viewerTransition.effectiveProgress
                    if (p <= 0f) return@graphicsLayer
                    val s = 1f - GRID_RECEDE_SCALE * p
                    scaleX = s
                    scaleY = s
                    alpha = 1f - GRID_FADE * p
                }
        ) {
            ScanProgressBar(progress = scanProgress)

            AnimatedVisibility(visible = syncMessage != null && !scanProgress.isScanning) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = syncMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            val pullToRefreshState = rememberPullToRefreshState()
            val density = LocalDensity.current
            val thresholdPx = with(density) { PullToRefreshDefaults.PositionalThreshold.toPx() }

            PullToRefreshBox(
                isRefreshing = isSyncing,
                onRefresh = {
                    if (!isViewerOpen && !scanProgress.isScanning) {
                        viewModel.syncPhotos()
                    }
                },
                state = pullToRefreshState,
                modifier = Modifier.fillMaxSize(),
                indicator = {}
            ) {
                // 1. Revealed indicator at the top behind the photo stream
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(PullToRefreshDefaults.PositionalThreshold)
                        .graphicsLayer {
                            val fraction = pullToRefreshState.distanceFraction
                            // Subtle parallax downward motion as the stream pulls away
                            translationY = (fraction * thresholdPx - thresholdPx) * 0.45f
                            alpha = (fraction * 1.5f).coerceIn(0f, 1f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.4.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = { pullToRefreshState.distanceFraction.coerceIn(0f, 1f) },
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.4.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = when {
                                isSyncing -> stringResource(R.string.photos_ptr_syncing)
                                pullToRefreshState.distanceFraction >= 1f -> stringResource(R.string.photos_ptr_release)
                                else -> stringResource(R.string.photos_ptr_pull)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // 2. Main content (photo stream and jump bar), translated downward following the pull gesture
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = pullToRefreshState.distanceFraction * thresholdPx
                        }
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                ) {
                    if (viewMode == GalleryViewMode.MONTH) {
                        // Month View: Sectioned by Month
                        monthIndex.forEach { month ->
                            stickyHeader(key = "h_${month.yearMonth}") {
                                MonthHeader(
                                    label = MonthBucketing.labelOf(month.yearMonth),
                                    itemCount = month.itemCount,
                                    isComplete = month.isComplete
                                )
                            }

                            items(
                                count = rowsFor(month.itemCount, gridColumns),
                                key = { row -> "r_${month.yearMonth}_$row" }
                            ) { row ->
                                MonthRow(
                                    photos = monthPhotos[month.yearMonth].orEmpty(),
                                    row = row,
                                    columns = gridColumns,
                                    onPhotoClick = viewModel::openViewer,
                                    boundsTracker = thumbnailBounds
                                )
                            }
                        }
                    } else {
                        // Day View: Sectioned by Day inside months (ISS-027 compliant single LazyColumn)
                        monthIndex.forEach { month ->
                            val dayGroups = monthDaysMap[month.yearMonth].orEmpty()
                            dayGroups.forEach { dayGroup ->
                                stickyHeader(key = "dh_${dayGroup.dayKey}") {
                                    DayHeader(
                                        label = dayGroup.label,
                                        itemCount = dayGroup.photos.size
                                    )
                                }

                                items(
                                    count = rowsFor(dayGroup.photos.size, gridColumns),
                                    key = { row -> "dr_${dayGroup.dayKey}_$row" }
                                ) { row ->
                                    MonthRow(
                                        photos = dayGroup.photos,
                                        row = row,
                                        columns = gridColumns,
                                        onPhotoClick = viewModel::openViewer,
                                        boundsTracker = thumbnailBounds
                                    )
                                }
                            }
                        }
                    }
                }

                if (monthIndex.isEmpty()) {
                    when {
                        scanProgress.isScanning -> EmptyStateOverlay {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.photos_empty_syncing))
                        }
                        else -> EmptyStateOverlay {
                            Text(
                                text = stringResource(R.string.photos_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.photos_empty_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                if (monthIndex.isNotEmpty()) {
                    // The jump panel takes the full height (its year column scrolls) but only its
                    // own width — `fillMaxSize()` here would make the tap-outside barrier cover the
                    // whole gallery and swallow every thumbnail tap.
                    MonthIndexBar(
                        currentYearMonth = currentMonth,
                        months = monthIndex,
                        onJumpToMonth = { yearMonth ->
                            val idx = if (viewMode == GalleryViewMode.MONTH) {
                                GalleryViewModeSync.listIndexOfMonthView(monthIndex, gridColumns, yearMonth, 0)
                            } else {
                                GalleryViewModeSync.listIndexOfDayView(monthIndex, monthDaysMap, gridColumns, yearMonth, null)
                            }
                            if (idx >= 0) {
                                scope.launch { listState.animateScrollToItem(idx) }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, bottom = 12.dp),
                        // null = the user switched month covers off. Passing null rather than an
                        // empty flow is what stops the card subscribing to the cover query at all,
                        // so a disabled feature costs nothing.
                        coverRowsOf = if (monthCoverEnabled) viewModel::coverRows else null
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showScrollToTop,
                    enter = fadeIn(animationSpec = tween(durationMillis = 180)) + scaleIn(
                        initialScale = 0.7f,
                        animationSpec = tween(durationMillis = 200)
                    ),
                    exit = fadeOut(animationSpec = tween(durationMillis = 150)) + scaleOut(
                        targetScale = 0.7f,
                        animationSpec = tween(durationMillis = 150)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = ScrollToTopIcon,
                            contentDescription = stringResource(R.string.photos_action_scroll_to_top)
                        )
                    }
                }
            }
            }
        }
    }

    val viewerState = viewer
    if (viewerState != null) {
        val currentPhotoId = viewerState.photos.getOrNull(viewerState.index)?.id
        androidx.compose.runtime.key(currentPhotoId ?: viewerState.index) {
            PhotoViewerScreen(
                photos = viewerState.photos,
                initialIndex = viewerState.index,
                repository = viewModel.repository,
                downloadState = downloadState,
                onDismiss = viewModel::closeViewer,
                onDownload = { photo -> viewModel.downloadPhoto(context, photo) },
                onShare = { photo -> viewModel.sharePhoto(context, photo) },
                isSharing = isSharing,
                onIndexChange = viewModel::setViewerIndex,
                anchorBounds = currentPhotoId?.let { thumbnailBounds.boundsOf(it) },
                boundsTracker = thumbnailBounds,
                transition = viewerTransition,
                onTransitionSettled = { open -> onViewerOpenChange(open) }
            )
        }
    }
}

/**
 * Capsule switcher for Day vs Month view.
 */
@Composable
private fun ViewModeToggleCapsule(
    currentMode: GalleryViewMode,
    onModeSelected: (GalleryViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = modifier.height(34.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(3.dp)
        ) {
            GalleryViewMode.entries.forEach { mode ->
                val selected = currentMode == mode
                val targetBg = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
                val targetText = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

                val bg by animateColorAsState(targetBg, label = "toggleBg")
                val textColor by animateColorAsState(targetText, label = "toggleText")

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(bg)
                        .clickable { onModeSelected(mode) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(mode.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
fun DayHeader(
    label: String,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.photos_count_suffix, itemCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val GRID_RECEDE_SCALE = 0.06f
private const val GRID_FADE = 0.88f

private fun rowsFor(itemCount: Int, columns: Int): Int = GalleryViewModeSync.rowsFor(itemCount, columns)

@Composable
private fun MonthRow(
    photos: List<PhotoAssetEntity>,
    row: Int,
    columns: Int,
    onPhotoClick: (PhotoAssetEntity) -> Unit,
    boundsTracker: ThumbnailBoundsTracker? = null
) {
    val start = row * columns
    val end = (start + columns).coerceAtMost(photos.size)

    val tileSize = LocalConfiguration.current.screenWidthDp.dp / columns

    if (start >= photos.size) {
        Spacer(modifier = Modifier.height(tileSize))
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (i in start until end) {
            val photo = photos[i]
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                PhotoGridItem(
                    photo = photo,
                    onClick = { onPhotoClick(photo) },
                    modifier = Modifier.fillMaxWidth(),
                    onBoundsChanged = { coords -> boundsTracker?.update(photo.id, coords) }
                )
            }
        }
        repeat(columns - (end - start)) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

private fun monthKeyAt(state: LazyListState, monthKeys: List<Int>): Int? {
    if (monthKeys.isEmpty()) return null

    val layout = state.layoutInfo
    val firstVisible = layout.visibleItemsInfo.firstOrNull()
        ?: return monthKeys.firstOrNull()

    val pinnedHeader = layout.visibleItemsInfo.firstOrNull { info ->
        val k = info.key.toString()
        (k.startsWith("h_") || k.startsWith("dh_")) && info.offset <= 0
    }

    val key = (pinnedHeader ?: firstVisible).key.toString()
    val raw = when {
        key.startsWith("h_") -> key.removePrefix("h_")
        key.startsWith("r_") -> key.removePrefix("r_").substringBefore('_')
        key.startsWith("dh_") -> key.removePrefix("dh_").substring(0, 6)
        key.startsWith("dr_") -> key.removePrefix("dr_").substring(0, 6)
        else -> return monthKeys.firstOrNull()
    }
    return raw.toIntOrNull() ?: monthKeys.firstOrNull()
}

@Composable
private fun EmptyStateOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            content()
        }
    }
}

private var _scrollToTopIcon: ImageVector? = null

private val ScrollToTopIcon: ImageVector
    get() {
        if (_scrollToTopIcon != null) return _scrollToTopIcon!!
        _scrollToTopIcon = ImageVector.Builder(
            name = "ScrollToTop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Horizontal bar on top
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 3.2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(6.5f, 7.5f)
                lineTo(17.5f, 7.5f)
            }
            // Upward chevron without stem
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7.5f, 15f)
                lineTo(12f, 10.5f)
                lineTo(16.5f, 15f)
            }
        }.build()
        return _scrollToTopIcon!!
    }

