package com.getup.iClouDroid.feature.drive.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getup.iClouDroid.feature.drive.data.DriveDeltaPlanner
import com.getup.iClouDroid.feature.drive.data.DriveDownloadTaskState
import com.getup.iClouDroid.feature.drive.data.DriveDownloader
import com.getup.iClouDroid.feature.drive.data.DriveRepository
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity
import com.getup.iClouDroid.feature.drive.data.db.DriveUploadEntity
import com.getup.iClouDroid.feature.drive.work.DriveUploadWorker
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
    /** Map of active and queued download tasks. */
    val downloadTasks: Map<String, DriveDownloadTaskState> = emptyMap(),
    /** Transient confirmation shown as a snackbar-like line after a finished download. */
    val downloadMessage: String? = null,
    /** Active and queued upload tasks. */
    val activeUploads: List<DriveUploadEntity> = emptyList()
) {
    val currentDrivewsid: String
        get() = trail.lastOrNull()?.drivewsid ?: DriveItemEntity.ROOT_DRIVEWSID

    val currentFolderPath: String
        get() {
            val crumbs = trail.drop(1)
            if (crumbs.isEmpty()) return "/"
            return "/" + crumbs.joinToString("/") { it.name }
        }

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
        // Reclaim cached upload copies orphaned by earlier failures or a process kill.
        // Runs before the listing so it cannot race the first upload.
        viewModelScope.launch { repository.sweepOrphanedUploadCache() }

        viewModelScope.launch {
            repository.ensureRoot()
            val root = repository.getItem(DriveItemEntity.ROOT_DRIVEWSID)
            val rootCrumb = DriveCrumb(
                drivewsid = DriveItemEntity.ROOT_DRIVEWSID,
                name = root?.name?.takeIf { it.isNotBlank() } ?: "iCloud Drive"
            )
            _uiState.value = _uiState.value.copy(trail = listOf(rootCrumb))
            observeAndRefresh(rootCrumb)

            // Collect download queue task states into UI state
            launch {
                repository.downloadQueueTasks.collect { queueTasks ->
                    _uiState.value = _uiState.value.copy(downloadTasks = queueTasks)
                }
            }

            // Collect active + recently-failed upload tasks into UI state; auto-refresh when an
            // upload finishes.
            //
            // This deliberately observes `observeVisible()` rather than `observeActive()`: the
            // latter drops a row the moment it becomes FAILED, so a rejected file disappeared with
            // no message at all. `previousActiveCount` is tracked separately because a failure also
            // reduces the *active* count and must still trigger a folder refresh.
            var previousActiveCount = 0
            launch {
                repository.uploadQueue.observeVisible().collect { uploads ->
                    val activeCount = uploads.count {
                        it.status == com.getup.iClouDroid.feature.drive.data.db.DriveUploadStatus.QUEUED ||
                            it.status == com.getup.iClouDroid.feature.drive.data.db.DriveUploadStatus.UPLOADING
                    }
                    val previousCount = previousActiveCount
                    previousActiveCount = activeCount
                    _uiState.value = _uiState.value.copy(activeUploads = uploads)

                    if (previousCount > 0 && activeCount < previousCount) {
                        Log.i(TAG, "Upload finished (active $previousCount -> $activeCount), refreshing")
                        refresh()
                    }
                }
            }

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
            name = item.displayName
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
                    Log.w(TAG, "syncIncremental failed", e)
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        lastSyncSummary = e.message ?: "同步失败"
                    )
                }
            )
        }
    }

    private fun summarize(report: DriveDeltaPlanner.SyncReport): String {
        val base = if (report.listed == 0) {
            "已是最新（检查 ${report.containersConsidered} 个文件夹）"
        } else {
            "已更新 ${report.listed} 个文件夹（检查 ${report.containersConsidered} 个）"
        }
        return if (report.walkCompleted) base else "$base · ${report.abortReason ?: "未完成"}"
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun dismissDownloadMessage() {
        _uiState.value = _uiState.value.copy(downloadMessage = null)
    }

    /** Enqueues files for upload into current folder. */
    fun uploadFiles(uris: List<android.net.Uri>, context: Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val folderId = _uiState.value.currentDrivewsid
            val folderPath = _uiState.value.currentFolderPath

            for (uri in uris) {
                repository.enqueueUpload(uri, folderId, folderPath)
            }

            DriveUploadWorker.enqueue(context)
        }
    }

    /** Cancels an active or queued upload task by ID. */
    fun cancelUpload(uploadId: Long) {
        viewModelScope.launch {
            repository.cancelUpload(uploadId)
        }
    }

    /**
     * Handles file click: if file is already downloaded locally, opens it via Intent (bridge to D7 preview);
     * otherwise enqueues for download.
     */
    fun onFileClick(item: DriveItemEntity, context: Context? = null) {
        if (!item.isDownloadable) return

        viewModelScope.launch {
            val localFile = repository.checkDownloadedFile(item)
            if (localFile != null && localFile.uri != null && context != null) {
                val mime = DriveDownloader.mimeTypeFor(item.extension)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(localFile.uri, mime.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val opened = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)

                if (opened) return@launch
            }

            // Not downloaded locally or failed to launch viewer -> enqueue download
            repository.enqueueDownload(item.drivewsid)
        }
    }

    /** Cancels an active or queued download task for [drivewsid]. */
    fun cancelDownload(drivewsid: String) {
        viewModelScope.launch {
            repository.cancelDownload(drivewsid)
        }
    }

    private fun observeAndRefresh(crumb: DriveCrumb) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repository.observeChildren(crumb.drivewsid).collect { children ->
                _uiState.value = _uiState.value.copy(items = children)
            }
        }

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
