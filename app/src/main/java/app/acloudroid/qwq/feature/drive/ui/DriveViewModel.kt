package app.acloudroid.qwq.feature.drive.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.feature.drive.data.DriveDeltaPlanner
import app.acloudroid.qwq.feature.drive.data.DriveDownloadTaskState
import app.acloudroid.qwq.feature.drive.data.DriveDownloader
import app.acloudroid.qwq.feature.drive.data.DriveRepository
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.drive.data.db.DriveUploadEntity
import app.acloudroid.qwq.feature.drive.work.DriveUploadWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repository: DriveRepository =
        (application as IClouDroidApp).container.driveRepository

    private val _uiState = MutableStateFlow(DriveUiState())
    val uiState: StateFlow<DriveUiState> = _uiState.asStateFlow()

    /**
     * Observes sub-folders under [parentDrivewsid] for hierarchical folder picking.
     */
    fun observeFolderContainers(parentDrivewsid: String): Flow<List<DriveItemEntity>> {
        return repository.observeChildren(parentDrivewsid).map { items ->
            items.filter { it.isContainer }
        }
    }

    private var observeJob: Job? = null
    private var refreshJob: Job? = null
    private var syncJob: Job? = null

    init {
        // Reclaim cached upload copies orphaned by earlier failures or a process kill.
        // Runs before the listing so it cannot race the first upload.
        viewModelScope.launch { repository.sweepOrphanedUploadCache() }

        viewModelScope.launch {
            repository.ensureRoot()
            val restoredTrail = restoreTrailFromSavedState()
            val root = repository.getItem(DriveItemEntity.ROOT_DRIVEWSID)
            val rootCrumb = DriveCrumb(
                drivewsid = DriveItemEntity.ROOT_DRIVEWSID,
                name = root?.name?.takeIf { it.isNotBlank() } ?: "iCloud Drive"
            )
            val currentTrail = if (!restoredTrail.isNullOrEmpty()) {
                restoredTrail
            } else {
                listOf(rootCrumb)
            }
            _uiState.value = _uiState.value.copy(trail = currentTrail)
            currentTrail.lastOrNull()?.let { observeAndRefresh(it) }

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
                        it.status == app.acloudroid.qwq.feature.drive.data.db.DriveUploadStatus.QUEUED ||
                            it.status == app.acloudroid.qwq.feature.drive.data.db.DriveUploadStatus.UPLOADING
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

    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun saveScrollPosition(drivewsid: String, index: Int, offset: Int) {
        scrollPositions[drivewsid] = index to offset
    }

    fun getScrollPosition(drivewsid: String): Pair<Int, Int>? {
        return scrollPositions[drivewsid]
    }

    private fun saveTrailToSavedState(trail: List<DriveCrumb>) {
        savedStateHandle[KEY_TRAIL_IDS] = ArrayList(trail.map { it.drivewsid })
        savedStateHandle[KEY_TRAIL_NAMES] = ArrayList(trail.map { it.name })
    }

    private fun restoreTrailFromSavedState(): List<DriveCrumb>? {
        val ids = savedStateHandle.get<ArrayList<String>>(KEY_TRAIL_IDS) ?: return null
        val names = savedStateHandle.get<ArrayList<String>>(KEY_TRAIL_NAMES) ?: return null
        if (ids.isEmpty() || ids.size != names.size) return null
        return ids.zip(names) { id, name -> DriveCrumb(id, name) }
    }

    /** Jumps to a crumb by index, truncating everything below it. */
    fun navigateTo(index: Int) {
        val trail = _uiState.value.trail
        if (index !in trail.indices) return
        val newTrail = trail.subList(0, index + 1)
        _uiState.value = _uiState.value.copy(trail = newTrail)
        saveTrailToSavedState(newTrail)
        observeAndRefresh(trail[index])
    }

    /** Descends into a container. A `FILE` is not descendable and is ignored. */
    fun open(item: DriveItemEntity) {
        if (!item.isContainer) return
        val crumb = DriveCrumb(
            drivewsid = item.drivewsid,
            name = item.displayName
        )
        val newTrail = _uiState.value.trail + crumb
        _uiState.value = _uiState.value.copy(trail = newTrail)
        saveTrailToSavedState(newTrail)
        observeAndRefresh(crumb)
    }

    /** Pops one level. Returns false at the root, letting the caller exit the screen. */
    fun goUp(): Boolean {
        val trail = _uiState.value.trail
        if (trail.size <= 1) return false
        val target = trail[trail.size - 2]
        val newTrail = trail.subList(0, trail.size - 1)
        _uiState.value = _uiState.value.copy(trail = newTrail)
        saveTrailToSavedState(newTrail)
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

            // KEEP semantics: no-op when a drain pass is already scheduled or running, and that
            // pass will pick up the rows just added.
            DriveUploadWorker.enqueue(context)

            // One recovery attempt for the case KEEP cannot handle: the unique name is held by a
            // work that the system dropped, so `enqueue` was silently a no-op and nothing will
            // ever drain the queue. Waiting briefly first avoids cancelling a pass that is merely
            // still starting up.
            kotlinx.coroutines.delay(ENQUEUE_RECOVERY_DELAY_MS)
            val stillQueued = repository.uploadQueue.hasPendingWork()
            if (stillQueued && !DriveUploadWorker.isWorkActive(context)) {
                android.util.Log.w(
                    TAG,
                    "No active upload work after enqueue; forcing a drain pass"
                )
                DriveUploadWorker.forceEnqueue(context)
            }
        }
    }

    /** Cancels an active or queued upload task by ID. */
    fun cancelUpload(uploadId: Long) {
        viewModelScope.launch {
            repository.cancelUpload(uploadId)
        }
    }

    /** Retries a failed upload task by ID. */
    fun retryUpload(uploadId: Long) {
        viewModelScope.launch {
            repository.retryUpload(uploadId)
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

    // ----------------------------------------------------------------------------------------
    // D6 — Directory Operations (Create, Rename, Move, Delete)
    // ----------------------------------------------------------------------------------------

    private val _allContainers = MutableStateFlow<List<DriveItemEntity>>(emptyList())
    val allContainers: StateFlow<List<DriveItemEntity>> = _allContainers.asStateFlow()

    fun loadContainersForMove() {
        viewModelScope.launch {
            _allContainers.value = repository.getAllContainers()
        }
    }

    /** Creates a new folder under current directory with [folderName]. */
    fun createFolder(folderName: String) {
        val trimmed = folderName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val parentId = _uiState.value.currentDrivewsid
            repository.createFolder(parentId, trimmed).fold(
                onSuccess = {
                    Log.i(TAG, "Created folder ${it.name} under $parentId")
                },
                onFailure = { e ->
                    Log.w(TAG, "createFolder failed", e)
                    _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "新建文件夹失败")
                }
            )
        }
    }

    /** Renames [item] to [newName]. */
    fun renameItem(item: DriveItemEntity, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == item.name) return
        viewModelScope.launch {
            repository.renameItem(item.drivewsid, trimmed).fold(
                onSuccess = {
                    Log.i(TAG, "Renamed item ${item.drivewsid} to $trimmed")
                },
                onFailure = { e ->
                    Log.w(TAG, "renameItem failed", e)
                    _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "重命名失败")
                }
            )
        }
    }

    /** Moves [items] into [targetDrivewsId]. */
    fun moveItems(items: List<DriveItemEntity>, targetDrivewsId: String) {
        if (items.isEmpty() || targetDrivewsId.isBlank()) return
        viewModelScope.launch {
            repository.moveItems(items, targetDrivewsId).fold(
                onSuccess = {
                    Log.i(TAG, "Moved ${items.size} items to $targetDrivewsId")
                },
                onFailure = { e ->
                    Log.w(TAG, "moveItems failed", e)
                    _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "移动失败")
                }
            )
        }
    }

    /** Moves [items] to Trash. */
    fun deleteItems(items: List<DriveItemEntity>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            repository.deleteItems(items).fold(
                onSuccess = {
                    Log.i(TAG, "Deleted/Trashed ${items.size} items")
                },
                onFailure = { e ->
                    Log.w(TAG, "deleteItems failed", e)
                    _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "移入最近删除失败")
                }
            )
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
        private const val KEY_TRAIL_IDS = "drive_trail_ids"
        private const val KEY_TRAIL_NAMES = "drive_trail_names"

        /**
         * Grace period before checking whether the enqueue actually produced a running worker.
         *
         * Long enough that a work which is merely starting up is not cancelled and restarted, short
         * enough that a genuinely dropped work is repaired before the user notices the queue sitting
         * at "排队中".
         */
        private const val ENQUEUE_RECOVERY_DELAY_MS = 3_000L
    }
}
