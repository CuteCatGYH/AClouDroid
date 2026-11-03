package com.getup.iClouDroid.feature.photos.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.getup.iClouDroid.IClouDroidApp
import com.getup.iClouDroid.feature.photos.data.MediaStoreDownloader
import com.getup.iClouDroid.feature.photos.data.MonthScanEngine
import com.getup.iClouDroid.feature.photos.data.ScanProgress
import com.getup.iClouDroid.feature.photos.data.db.PhotoAssetEntity
import com.getup.iClouDroid.feature.photos.data.db.PhotoMonthIndexEntity
import com.getup.iClouDroid.feature.photos.work.ScanNotificationHelper
import com.getup.iClouDroid.feature.photos.work.ScanScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Downloading : DownloadState
    data class Success(val uri: Uri, val filename: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

/**
 * State of the gallery as a whole.
 *
 * [months] is the *index* — a few hundred rows at most — not the photos. Each month's contents are
 * subscribed separately by the UI so a 16k-item library never materialises at once.
 */
data class GalleryUiState(
    val months: List<PhotoMonthIndexEntity> = emptyList(),
    val totalCount: Int = 0,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

class PhotosViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as IClouDroidApp).container

    /**
     * Exposed for the full-screen viewer, which needs URL re-minting and metadata lookups.
     *
     * The UI layer must not call `Icloudcore.*` directly (AGENTS §10); handing over the repository
     * keeps every Go call funnelled through it.
     */
    val repository = container.photoRepository
    private val scanEngine = container.monthScanEngine

    /**
     * Month sections, newest first.
     *
     * P2-016: this is the scroll anchor. Previously the grid was driven by `PagingData`, whose
     * `PagingSource` was invalidated after every APPEND — collapsing `itemCount` and clamping the
     * scroll position. A month key does not change when rows are appended, so there is nothing to
     * clamp.
     */
    val monthIndex: StateFlow<List<PhotoMonthIndexEntity>> = repository.monthIndexFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> = repository.totalCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Live scan progress: drives the bar and the "scanned so far" text. */
    val scanProgress: StateFlow<ScanProgress> = scanEngine.progress

    val galleryState: StateFlow<GalleryUiState> = monthIndex
        .map { GalleryUiState(months = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    val galleryViewMode: StateFlow<com.getup.iClouDroid.data.settings.GalleryViewMode> =
        container.appSettings.galleryViewMode

    fun setGalleryViewMode(mode: com.getup.iClouDroid.data.settings.GalleryViewMode) {
        container.appSettings.setGalleryViewMode(mode)
    }

    /**
     * Whether the jump card shows month cover thumbnails.
     *
     * Read by the UI to decide which of the two month grids to render. The *downloading* half of
     * the same switch lives in [com.getup.iClouDroid.feature.photos.data.PhotoRepository]; both read
     * this one preference so the two can never disagree.
     */
    val monthCoverEnabled: StateFlow<Boolean> = container.appSettings.monthCoverEnabled

    /**
     * Cover rows for one year, subscribed by the jump card while it is open.
     *
     * Returns an empty list flow when covers are switched off, so the card's collector has nothing
     * to observe and the plain text grid is used instead.
     */
    fun coverRows(year: Int): Flow<List<com.getup.iClouDroid.feature.photos.data.db.MonthCoverRow>> =
        if (monthCoverEnabled.value) repository.observeMonthCovers(year)
        else kotlinx.coroutines.flow.flowOf(emptyList())

    private val _gridColumns = MutableStateFlow(4)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    /**
     * Which photo the full-screen viewer is showing, and the ordered list it came from.
     *
     * Replaces the old single `selectedPhoto`. Two things drove that:
     *  - **One source of truth.** `selectedPhoto` plus a pager index would be two overlapping
     *    truths about "which photo is open", exactly the duplication PITFALLS §7 warns about.
     *  - **Paging needs the list.** Swiping left/right requires the surrounding photos, and the
     *    grid only holds them per-month.
     *
     * [photos] tracks the month's Flow while the viewer is open (see [openViewer]), so a month
     * that is still being scanned can grow underneath the viewer instead of leaving the user
     * stuck on a stale snapshot at the end of the list.
     */
    data class ViewerState(
        val photos: List<PhotoAssetEntity>,
        val index: Int
    ) {
        val current: PhotoAssetEntity? get() = photos.getOrNull(index)
    }

    private val _viewer = MutableStateFlow<ViewerState?>(null)
    val viewer: StateFlow<ViewerState?> = _viewer.asStateFlow()

    /**
     * The currently open photo, kept for the surrounding UI (download button, download state).
     *
     * Derived rather than stored so it can never disagree with [viewer].
     */
    val selectedPhoto: StateFlow<PhotoAssetEntity?> = _viewer
        .map { it?.current }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var viewerMonthJob: Job? = null

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private var hasAutoStarted = false
    private var scanJob: Job? = null

    init {
        // Continuously observe scanEngine.progress across the whole ViewModel lifecycle so
        // progress messages, cover caching state, and status notifications stay live everywhere.
        viewModelScope.launch {
            scanEngine.progress.collect { progress ->
                val context = getApplication<Application>()
                if (progress.isScanning) {
                    _syncMessage.value = buildProgressMessage(progress)
                    ScanNotificationHelper.showOngoing(context, progress)
                } else if (progress.isCachingCovers) {
                    _syncMessage.value = "正在缓存月份封面…"
                    ScanNotificationHelper.showOngoing(context, progress)
                } else if (progress.isComplete) {
                    _syncMessage.value = "全盘扫描完成，共 ${totalCount.value} 项"
                    if (progress.scanned > 0) {
                        ScanNotificationHelper.showFinished(context, progress.scanned)
                    } else {
                        ScanNotificationHelper.cancel(context)
                    }
                } else if (progress.scanned > 0) {
                    _syncMessage.value = "已扫描 ${progress.scanned} 项，稍后自动继续"
                    ScanNotificationHelper.cancel(context)
                }
            }
        }

        viewModelScope.launch {
            // Restore the persisted estimate first so the progress bar has a denominator even
            // before this run produces any progress of its own.
            scanEngine.restoreProgress()

            // Bucket any rows left over from a v3→v4 upgrade (or an interrupted scan) before the
            // UI reads the index. Without this an upgraded install shows a populated count but an
            // empty gallery, because `photo_assets` has rows while `photo_month_index` has none.
            scanEngine.ensureMonthIndexUpToDate()

            // Start the scan whenever it is not already finished — NOT only when the library is
            // empty.
            repository.totalCountFlow.collect { _ ->
                if (!hasAutoStarted && !_isSyncing.value) {
                    hasAutoStarted = true
                    startInitialLoad()
                }
            }
        }
    }

    /**
     * Hands the scan off to WorkManager when the screen goes away.
     *
     * The foreground scan runs in `viewModelScope`, which is cancelled with the screen. Leaving it
     * at that means a user who switches apps mid-scan comes back to a partially populated gallery
     * and no indication anything is still pending. `ScanScheduler` uses `KEEP`, so this never
     * duplicates an already-queued job, and the engine's mutex stops it from racing the foreground
     * path.
     */
    fun onScreenLeaving() {
        if (scanEngine.progress.value.isComplete) {
            ScanScheduler.cancel(getApplication<Application>())
            return
        }
        ScanScheduler.ensureScheduled(getApplication<Application>())
    }

    /** Photos of one month, newest first. Consumers subscribe per month as it scrolls into view. */
    fun observeMonth(yearMonth: Int): Flow<List<PhotoAssetEntity>> =
        repository.observeMonth(yearMonth)

    /**
     * Photos of every month currently on screen, keyed by `yyyyMM`.
     *
     * The gallery emits one row per `columns` photos so that every cell is a direct child of the
     * single `LazyColumn` — nesting a `LazyVerticalGrid` per month meant the inner grid owned its
     * own measurement, and after a fast fling it came back with a single row and dropped the rest.
     *
     * Rows look their data up here rather than each starting its own query: a month with forty rows
     * would otherwise open forty cursors for identical data. Subscriptions are added as months
     * become visible and dropped once the list no longer needs them.
     */
    private val _monthPhotos = MutableStateFlow<Map<Int, List<PhotoAssetEntity>>>(emptyMap())
    val monthPhotos: StateFlow<Map<Int, List<PhotoAssetEntity>>> = _monthPhotos.asStateFlow()

    private val _monthDaysMap = MutableStateFlow<Map<Int, List<com.getup.iClouDroid.feature.photos.data.DayBucketing.DayGroup>>>(emptyMap())
    /**
     * Day-level groupings per month, memoized for the Day View.
     */
    val monthDaysMap: StateFlow<Map<Int, List<com.getup.iClouDroid.feature.photos.data.DayBucketing.DayGroup>>> = _monthDaysMap.asStateFlow()

    private val monthJobs = mutableMapOf<Int, Job>()
    private val prewarmDedup = mutableMapOf<String, Long>()

    /**
     * Subscribes to every month in the library.
     *
     * Deliberately **not** driven by viewport visibility. An earlier version subscribed only to the
     * months near the viewport and evicted the rest, which broke under fast flinging: the visible
     * window is rewritten several times per second during a fling, so months were torn down a frame
     * after being created and came back on screen before their query had emitted — rows found no
     * data and painted empty. Deferring the eviction only narrowed the window in which that could
     * happen.
     *
     * Subscribing to everything is affordable here precisely because of the row-based layout: every
     * month is a handful of `PhotoAssetEntity` objects (no bitmaps — Coil holds those), and a
     * 16k-item library across 108 months is a few thousand small objects. Correctness under
     * flinging is worth far more than trimming that.
     */
    fun subscribeToAllMonths(yearMonths: Collection<Int>) {
        for (ym in yearMonths) {
            if (monthJobs[ym] != null) continue
            monthJobs[ym] = viewModelScope.launch {
                repository.observeMonth(ym).collect { photos ->
                    _monthPhotos.update { it + (ym to photos) }
                    val dayGroups = com.getup.iClouDroid.feature.photos.data.DayBucketing.groupPhotosByDay(photos)
                    _monthDaysMap.update { it + (ym to dayGroups) }
                    prewarmUrlsIfNeeded(photos)
                }
            }
        }

        // Drop subscriptions for months that no longer exist at all (after a re-scan or a wipe).
        val known = yearMonths.toSet()
        val gone = monthJobs.keys - known
        for (ym in gone) {
            monthJobs.remove(ym)?.cancel()
            _monthPhotos.update { it - ym }
            _monthDaysMap.update { it - ym }
        }
    }

    private fun prewarmUrlsIfNeeded(photos: List<PhotoAssetEntity>) {
        if (photos.isEmpty()) return
        val now = System.currentTimeMillis()
        val urlMaxAgeMs = 12 * 60 * 1000L
        val dedupWindowMs = 5 * 60 * 1000L

        val staleMasters = photos
            .filter { photo ->
                val lastAttempt = prewarmDedup[photo.masterId] ?: 0L
                val isStale = photo.thumbUrl.isNullOrBlank() || (now - photo.urlFetchedAt > urlMaxAgeMs)
                isStale && (now - lastAttempt > dedupWindowMs)
            }
            .map { it.masterId }
            .distinct()
            .filter { it.isNotBlank() }

        if (staleMasters.isNotEmpty()) {
            staleMasters.forEach { prewarmDedup[it] = now }
            viewModelScope.launch(Dispatchers.IO) {
                repository.refreshUrlsForMasters(staleMasters)
            }
        }
    }

    fun setGridColumns(columns: Int) {
        _gridColumns.value = columns.coerceIn(2, 6)
    }

    fun toggleGridColumns() {
        _gridColumns.value = when (_gridColumns.value) {
            3 -> 4
            4 -> 5
            5 -> 3
            else -> 4
        }
    }

    /**
     * Opens the full-screen viewer on [photo].
     *
     * The list is taken from the photo's own month, so swirling left/right walks the month the user
     * tapped in — no extra query, and it stays consistent with the grid they came from.
     */
    fun openViewer(photo: PhotoAssetEntity) {
        val month = monthPhotos.value[photo.yearMonth].orEmpty()
        // The tapped photo may not be in the map yet (a month whose Flow has not emitted). Fall
        // back to a single-item list rather than refusing to open.
        val list = if (month.isEmpty()) listOf(photo) else month
        val index = list.indexOfFirst { it.id == photo.id }.coerceAtLeast(0)

        _downloadState.value = DownloadState.Idle
        _viewer.value = ViewerState(photos = list, index = index)

        // Keep the list live while the viewer is open: a month still being scanned grows, and the
        // user should be able to keep swiping into the newly arrived photos.
        viewerMonthJob?.cancel()
        viewerMonthJob = viewModelScope.launch {
            repository.observeMonth(photo.yearMonth).collect { rows ->
                if (rows.isEmpty()) return@collect
                _viewer.update { state ->
                    state ?: return@update null
                    // Preserve identity across emissions: keep the viewer on the same *photo* even
                    // if rows were inserted ahead of it, but never jump if it was removed.
                    val currentId = state.photos.getOrNull(state.index)?.id
                    val restored = currentId?.let { id -> rows.indexOfFirst { it.id == id } }
                        ?.takeIf { it >= 0 }
                    state.copy(
                        photos = rows,
                        index = restored ?: state.index.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
                    )
                }
            }
        }
    }

    /** Called as the pager settles, so the surrounding UI (download, title) tracks the page. */
    fun setViewerIndex(index: Int) {
        _viewer.update { state ->
            state ?: return@update null
            if (index !in state.photos.indices) state
            else state.copy(index = index).also { _downloadState.value = DownloadState.Idle }
        }
    }

    fun closeViewer() {
        viewerMonthJob?.cancel()
        viewerMonthJob = null
        _viewer.value = null
        _downloadState.value = DownloadState.Idle
    }

    /**
     * First load: verify the zone, then walk the library into month sections.
     *
     * The walk is resumable and idempotent, so calling this after an interruption picks up where
     * it stopped rather than starting over.
     */
    private fun startInitialLoad() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "正在连接 iCloud 照片库..."

        scanJob = viewModelScope.launch {
            try {
                val zoneReady = repository.checkZoneReady()
                if (zoneReady.isFailure) {
                    val err = zoneReady.exceptionOrNull()?.message ?: "PrimarySync 分区未就绪"
                    _syncMessage.value = "错误: $err"
                    Log.w(TAG, "checkZoneReady failed: $err")
                    return@launch
                }

                _syncMessage.value = "正在扫描照片库..."

                val result = scanEngine.scanAll()

                if (result.isSuccess) {
                    val progress = scanEngine.progress.value
                    if (progress.isComplete) {
                        _syncMessage.value = "正在同步最新照片变更..."
                        repository.purgeRecentlyDeleted()
                        repository.syncIncremental(limit = 100)
                        _syncMessage.value = "同步完成，共 ${totalCount.value} 项"
                    } else {
                        _syncMessage.value = "已扫描 ${progress.scanned} 项，稍后自动继续"
                    }
                    Log.i(TAG, "Scan finished, complete=${progress.isComplete}, scanned=${progress.scanned}")
                } else {
                    val err = result.exceptionOrNull()?.message ?: "扫描出现异常"
                    _syncMessage.value = "扫描失败: $err"
                    Log.w(TAG, "scanAll failed: $err")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _syncMessage.value = null
                    throw e
                }
                Log.e(TAG, "initial load exception", e)
                _syncMessage.value = "异常: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Toolbar refresh: incremental only.
     *
     * Deliberately **never** starts a full walk. A full walk is hundreds of CloudKit round trips
     * and several minutes of work; putting it behind a one-tap toolbar button meant a user could
     * accidentally kick one off (and then swipe the app away, leaving `seen` incomplete). It now
     * lives in Settings, behind an explicit warning. See [startFullScan].
     */
    fun syncPhotos() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "正在检查增量更新..."

        viewModelScope.launch {
            try {
                val zoneReady = repository.checkZoneReady()
                if (zoneReady.isFailure) {
                    val err = zoneReady.exceptionOrNull()?.message ?: "PrimarySync 分区未就绪"
                    _syncMessage.value = "错误: $err"
                    return@launch
                }

                _syncMessage.value = "正在同步最新变更..."
                repository.purgeRecentlyDeleted()
                val result = repository.syncIncremental(limit = INCREMENTAL_PAGE_LIMIT)

                if (result.isSuccess) {
                    val count = result.getOrDefault(0)
                    _syncMessage.value = if (count > 0) {
                        "同步完成，变更 $count 项"
                    } else {
                        "已是最新，没有新增照片"
                    }
                    Log.i(TAG, "Incremental sync finished, count=$count")
                } else {
                    val err = result.exceptionOrNull()?.message ?: "同步出现异常"
                    _syncMessage.value = "同步失败: $err"
                }
            } catch (e: Exception) {
                Log.e(TAG, "syncPhotos exception", e)
                _syncMessage.value = "异常: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Settings screen: start (or resume) a full-library walk.
     *
     * Runs in `viewModelScope`, and [onScreenLeaving] hands it to WorkManager if the user navigates
     * away — the walk is resumable, so a background hand-off continues from the same cursor rather
     * than restarting.
     */
    fun startFullScan() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "正在扫描照片库..."

        scanJob = viewModelScope.launch {
            try {
                val zoneReady = repository.checkZoneReady()
                if (zoneReady.isFailure) {
                    val err = zoneReady.exceptionOrNull()?.message ?: "PrimarySync 分区未就绪"
                    _syncMessage.value = "错误: $err"
                    return@launch
                }

                val result = scanEngine.scanAll(force = true)

                if (result.isSuccess) {
                    val progress = scanEngine.progress.value
                    if (progress.isComplete) {
                        _syncMessage.value = "全盘扫描完成，共 ${totalCount.value} 项"
                    } else {
                        _syncMessage.value = "已扫描 ${progress.scanned} 项，稍后自动继续"
                    }
                } else {
                    _syncMessage.value = "扫描失败: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "startFullScan exception", e)
                _syncMessage.value = "异常: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Banner text for a running scan.
     *
     * Shows both the concrete position ("3,847 项") and the month currently being written, because
     * a bare percentage is meaningless while `estimatedTotal` is unknown — and it frequently is,
     * since CloudKit's cursor never reports a total.
     */
    private fun buildProgressMessage(progress: ScanProgress): String {
        val monthLabel = com.getup.iClouDroid.feature.photos.data.MonthBucketing
            .labelOf(progress.currentYearMonth)
        val where = if (progress.currentYearMonth > 0) " · $monthLabel" else ""
        return if (progress.estimatedTotal > 0) {
            "正在扫描 ${progress.scanned} / ${progress.estimatedTotal} 项$where"
        } else {
            "正在扫描 · 已扫描 ${progress.scanned} 项$where"
        }
    }

    fun downloadPhoto(context: Context, photo: PhotoAssetEntity) {
        if (_downloadState.value is DownloadState.Downloading) return
        _downloadState.value = DownloadState.Downloading

        viewModelScope.launch {
            val result = MediaStoreDownloader.downloadAsset(context, photo, repository)
            if (result.isSuccess) {
                val uri = result.getOrThrow()
                _downloadState.value = DownloadState.Success(uri, photo.filename)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "已下载到相册: ${photo.filename}", Toast.LENGTH_SHORT).show()
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "下载失败"
                _downloadState.value = DownloadState.Error(err)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "下载失败: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun sharePhoto(context: Context, photo: PhotoAssetEntity) {
        if (_isSharing.value) return
        _isSharing.value = true

        viewModelScope.launch {
            try {
                val result = MediaStoreDownloader.downloadAsset(context, photo, repository)
                if (result.isSuccess) {
                    val uri = result.getOrThrow()
                    withContext(Dispatchers.Main) {
                        launchShareIntent(context, uri, photo)
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "下载文件失败"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "准备分享失败: $err", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "分享出错: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                _isSharing.value = false
            }
        }
    }

    private fun launchShareIntent(context: Context, uri: Uri, photo: PhotoAssetEntity) {
        val mimeType = context.contentResolver.getType(uri) ?: when {
            photo.filename.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            photo.filename.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            photo.filename.endsWith(".heic", ignoreCase = true) -> "image/heic"
            photo.filename.endsWith(".png", ignoreCase = true) -> "image/png"
            else -> "image/*"
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "分享照片或视频")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "无法启动分享: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "PhotosViewModel"

        /** Change-feed page size for the toolbar refresh (`/records/changes` caps at 200). */
        private const val INCREMENTAL_PAGE_LIMIT = 100

        /**
         * How long a month stays subscribed after leaving the viewport.
         *
         * Long enough to survive the visible-window churn of a fast fling (which can rewrite the
         * window several times per second) but short enough that memory still tracks the viewport
         * rather than the whole library.
         */
        private const val MONTH_EVICTION_GRACE_MS = 4_000L
    }
}
