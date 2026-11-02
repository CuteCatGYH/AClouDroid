package com.getup.iClouDroid.feature.drive.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getup.iClouDroid.feature.drive.data.DriveDeltaPlanner
import com.getup.iClouDroid.feature.drive.data.DriveRepository
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One level of the folder stack.
 *
 * The trail is kept as a list of these rather than as a list of ids so the breadcrumb can render
 * names while a deep link is still loading — showing `drivewsid` in the breadcrumb would leak
 * CloudKit identifiers into the UI.
 */
data class DriveCrumb(
    val drivewsid: String,
    val name: String
)

data class DriveUiState(
    /** Trail from the root to the folder currently shown. Never empty once the root is known. */
    val trail: List<DriveCrumb> = emptyList(),
    val items: List<DriveItemEntity> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** True until the first listing completes, so the screen shows a spinner not "empty". */
    val isInitialLoading: Boolean = true,
    /** True while the D3 incremental walk runs; drives a subtle strip under the header. */
    val isSyncing: Boolean = false,
    /** Result of the last incremental walk, for the "上次检查…" line. Null until one runs. */
    val lastSyncSummary: String? = null,
    /** The file currently downloading, or null. */
    val downloadingDrivewsid: String? = null,
    /** 0..100 for [downloadingDrivewsid]. */
    val downloadProgress: Int = 0,
    /** Transient confirmation shown as a snackbar-like line after a finished download. */
    val downloadMessage: String? = null
) {
    val currentDrivewsid: String
        get() = trail.lastOrNull()?.drivewsid ?: DriveItemEntity.ROOT_DRIVEWSID

    val currentName: String
        get() = trail.lastOrNull()?.name ?: "iCloud Drive"

    val canGoUp: Boolean get() = trail.size > 1
}

/**
 * Drives one directory-browsing screen.
 *
 * ## Why the trail lives here and not in a nav graph
 *
 * The project has no Navigation Compose graph (AGENTS §10.1 explains why the viewer is an overlay
 * rather than a route). Descending into a folder is therefore a state change, and the back
 * behaviour is "pop one crumb" — which [goUp] implements explicitly. Relying on the system back
 * stack would exit the app from inside a folder (PITFALLS §20).
 */
class DriveViewModel(
    private val repository: DriveRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DriveUiState())
    val uiState: StateFlow<DriveUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null
    private var refreshJob: Job? = null
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            repository.ensureRoot()
            val root = repository.getItem(DriveItemEntity.ROOT_DRIVEWSID)
            val rootCrumb = DriveCrumb(
                drivewsid = DriveItemEntity.ROOT_DRIVEWSID,
                name = root?.name?.takeIf { it.isNotBlank() } ?: "iCloud Drive"
            )
            _uiState.value = _uiState.value.copy(trail = listOf(rootCrumb))
            observeAndRefresh(rootCrumb)

            // The walk needs a tree to walk. On a cold start the root listing above is the only
            // cached container, so running this immediately would check exactly one folder and
            // stop — the deep folders only become candidates after the user opens them once.
            // Kicking it off here still helps on a warm start, where the tree is already cached.
            syncIncremental()
        }
    }

    /** Jumps to a crumb by index, truncating everything below it. */
    fun navigateTo(index: Int) {
        val trail = _uiState.value.trail
        if (index !in trail.indices) return
        _uiState.value = _uiState.value.copy(trail = trail.subList(0, index + 1))
        observeAndRefresh(trail[index])
    }

    /** Descends into a container. A `FILE` is not descendable and is ignored. */
    fun open(item: DriveItemEntity) {
        if (!item.isContainer) return
        val crumb = DriveCrumb(
            drivewsid = item.drivewsid,
            name = item.name.takeIf { it.isNotBlank() } ?: "未命名"
        )
        _uiState.value = _uiState.value.copy(trail = _uiState.value.trail + crumb)
        observeAndRefresh(crumb)
    }

    /** Pops one level. Returns false at the root, letting the caller exit the screen. */
    fun goUp(): Boolean {
        val trail = _uiState.value.trail
        if (trail.size <= 1) return false
        val target = trail[trail.size - 2]
        _uiState.value = _uiState.value.copy(trail = trail.subList(0, trail.size - 1))
        observeAndRefresh(target)
        return true
    }

    fun refresh() {
        val crumb = _uiState.value.trail.lastOrNull() ?: return
        observeAndRefresh(crumb)
    }

    /**
     * Runs one incremental sync pass over every cached container.
     *
     * Separate from [refresh] on purpose. [refresh] re-lists **one** folder and is what the user
     * presses; this walks the **whole** cached tree and re-lists only what changed. Running it on
     * entry to the screen is what makes a folder the user has never opened show its real contents.
     *
     * The walk is capped by the repository's container budget and reports whether it finished;
     * an unfinished walk is not an error, so it does not raise [DriveUiState.errorMessage].
     */
    fun syncIncremental() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            repository.syncIncremental().fold(
                onSuccess = { report ->
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        lastSyncSummary = summarize(report)
                    )
                },
                onFailure = { e ->
                    // A failed walk must not blank the screen — the cached tree is still valid.
                    Log.w(TAG, "syncIncremental failed", e)
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        lastSyncSummary = e.message ?: "同步失败"
                    )
                }
            )
        }
    }

    /** Turns a [DriveDeltaPlanner.SyncReport] into one short Chinese line for the UI. */
    private fun summarize(report: DriveDeltaPlanner.SyncReport): String {
        val base = if (report.listed == 0) {
            "已是最新（检查 ${report.containersConsidered} 个文件夹）"
        } else {
            "已更新 ${report.listed} 个文件夹（检查 ${report.containersConsidered} 个）"
        }
        // An incomplete walk is reported honestly rather than shown as a full success: the user
        // is entitled to know the tree was only partly checked.
        return if (report.walkCompleted) base else "$base · ${report.abortReason ?: "未完成"}"
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun dismissDownloadMessage() {
        _uiState.value = _uiState.value.copy(downloadMessage = null)
    }

    /**
     * Downloads [item] into the shared Downloads collection.
     *
     * Only one download at a time: a second tap while one is in flight is ignored rather than
     * queued. Two concurrent multi-hundred-megabyte streams would compete for the same link and
     * both appear stalled.
     */
    fun download(item: DriveItemEntity) {
        if (!item.isDownloadable) return
        if (_uiState.value.downloadingDrivewsid != null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                downloadingDrivewsid = item.drivewsid,
                downloadProgress = 0,
                downloadMessage = null
            )

            repository.downloadFile(item.drivewsid) { percent ->
                _uiState.value = _uiState.value.copy(downloadProgress = percent)
            }.fold(
                onSuccess = { outcome ->
                    Log.i(TAG, "downloaded ${item.name} -> ${outcome.uri} (${outcome.bytes} B)")
                    _uiState.value = _uiState.value.copy(
                        downloadingDrivewsid = null,
                        downloadProgress = 0,
                        downloadMessage = "已保存到${outcome.displayLocation}：${item.name}"
                    )
                },
                onFailure = { e ->
                    Log.w(TAG, "download failed for ${item.name}", e)
                    _uiState.value = _uiState.value.copy(
                        downloadingDrivewsid = null,
                        downloadProgress = 0,
                        downloadMessage = e.message ?: "下载失败"
                    )
                }
            )
        }
    }

    /**
     * Subscribes to the cached children of [crumb], then refreshes from the server.
     *
     * The order matters: subscribe first so an already-cached folder paints immediately, then
     * refresh. Doing it the other way would blank the screen on every back-navigation to a folder
     * the user has already visited.
     *
     * ## Why the refresh result drives `isInitialLoading`, not the Flow
     *
     * An earlier version cleared `isInitialLoading` only from the `collect` block. That works for
     * a folder with children and **spins forever on an empty one**: Room only re-emits when the
     * table changes, and an empty folder writes no rows — the delete removes none, the insert adds
     * none. So the Flow never fires a second time, and the screen keeps showing the spinner even
     * though the server answered successfully with `items: []`.
     *
     * The refresh result is therefore the authority on "the first answer has arrived". The Flow
     * stays responsible for keeping [DriveUiState.items] current after that.
     */
    private fun observeAndRefresh(crumb: DriveCrumb) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repository.observeChildren(crumb.drivewsid).collect { children ->
                _uiState.value = _uiState.value.copy(items = children)
            }
        }

        // Cancel any in-flight refresh for the previous folder: otherwise a slow listing of the
        // folder the user just left can land after the new one and overwrite `items` with the
        // wrong contents.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
            try {
                repository.refreshFolder(crumb.drivewsid).onFailure { e ->
                    Log.w(TAG, "refreshFolder failed for ${crumb.drivewsid}", e)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = e.message ?: "目录读取失败"
                    )
                }
            } finally {
                // In `finally` so a cancellation or an unexpected throw cannot leave the spinner
                // running. This is the line whose absence caused the stuck empty folder.
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    isInitialLoading = false
                )
            }
        }
    }

    companion object {
        private const val TAG = "DriveViewModel"
    }
}
