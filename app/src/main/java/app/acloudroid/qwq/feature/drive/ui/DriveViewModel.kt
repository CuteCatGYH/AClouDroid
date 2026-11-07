package app.acloudroid.qwq.feature.drive.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.data.settings.AppSettings
import app.acloudroid.qwq.data.settings.DriveDisplayMode
import app.acloudroid.qwq.data.settings.DriveSortField
import app.acloudroid.qwq.data.settings.DriveSortOrder
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One level of the folder stack.
 */
data class DriveCrumb(
    val drivewsid: String,
    val name: String
)

data class DriveUiState(
    /** Trail from the root to the folder currently shown. Never empty once the root is known. */
    val trail: List<DriveCrumb> = emptyList(),
    val items: List<DriveItemEntity> = emptyList(),
    val displayMode: DriveDisplayMode = DriveDisplayMode.LIST,
    val sortField: DriveSortField = DriveSortField.NAME,
    val sortOrder: DriveSortOrder = DriveSortOrder.ASCENDING,
    val previewingItem: DriveItemEntity? = null,
    val webIWorkItem: DriveItemEntity? = null,
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
        get() = trail.lastOrNull()?.name ?: "Drive"

    val canGoUp: Boolean get() = trail.size > 1
}

/**
 * Sorts drive items according to [field], [order], keeping containers (folders/app libraries) at the top.
 */
fun sortDriveItems(
    items: List<DriveItemEntity>,
    field: DriveSortField,
    order: DriveSortOrder,
    foldersFirst: Boolean = true
): List<DriveItemEntity> {
    val comparator = Comparator<DriveItemEntity> { a, b ->
        if (foldersFirst && a.isContainer != b.isContainer) {
            return@Comparator if (a.isContainer) -1 else 1
        }
        val result = when (field) {
            DriveSortField.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
            DriveSortField.DATE_MODIFIED -> a.dateModified.orEmpty().compareTo(b.dateModified.orEmpty())
            DriveSortField.SIZE -> a.size.compareTo(b.size)
            DriveSortField.TYPE -> (a.extension ?: a.type).lowercase().compareTo((b.extension ?: b.type).lowercase())
        }
        if (order == DriveSortOrder.DESCENDING) -result else result
    }
    return items.sortedWith(comparator)
}

/**
 * Drives one directory-browsing screen with preview, display mode & custom sorting.
 */
class DriveViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repository: DriveRepository =
        (application as IClouDroidApp).container.driveRepository

    private val appSettings: AppSettings =
        (application as IClouDroidApp).container.appSettings

    private val _uiState = MutableStateFlow(DriveUiState())
    val uiState: StateFlow<DriveUiState> = _uiState.asStateFlow()

    private var rawChildren: List<DriveItemEntity> = emptyList()

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
        viewModelScope.launch { repository.sweepOrphanedUploadCache() }

        viewModelScope.launch {
            repository.ensureRoot()
            val restoredTrail = restoreTrailFromSavedState()
            val root = repository.getItem(DriveItemEntity.ROOT_DRIVEWSID)
            val rootName = root?.name?.takeIf { it.isNotBlank() && it != "iCloud Drive" } ?: "Drive"
            val rootCrumb = DriveCrumb(
                drivewsid = DriveItemEntity.ROOT_DRIVEWSID,
                name = rootName
            )
            val currentTrail = if (!restoredTrail.isNullOrEmpty()) {
                restoredTrail.mapIndexed { index, crumb ->
                    if (index == 0 && crumb.drivewsid == DriveItemEntity.ROOT_DRIVEWSID) {
                        crumb.copy(name = rootName)
                    } else {
                        crumb
                    }
                }
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

            // Collect active + recently-failed upload tasks into UI state
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

            // Observe settings flows to auto-apply sorting and view mode
            launch {
                combine(
                    appSettings.driveDisplayMode,
                    appSettings.driveSortField,
                    appSettings.driveSortOrder
                ) { mode, field, order ->
                    Triple(mode, field, order)
                }.collect {
                    applySortingAndPublish()
                }
            }

            syncIncrementalIfStale()
        }
    }

    private fun applySortingAndPublish() {
        val sorted = sortDriveItems(
            rawChildren,
            appSettings.driveSortField.value,
            appSettings.driveSortOrder.value
        )
        _uiState.value = _uiState.value.copy(
            items = sorted,
            displayMode = appSettings.driveDisplayMode.value,
            sortField = appSettings.driveSortField.value,
            sortOrder = appSettings.driveSortOrder.value
        )
    }

    fun setDisplayMode(mode: DriveDisplayMode) {
        appSettings.setDriveDisplayMode(mode)
    }

    fun setSortField(field: DriveSortField) {
        appSettings.setDriveSortField(field)
    }

    fun toggleSortOrder() {
        val nextOrder = if (appSettings.driveSortOrder.value == DriveSortOrder.ASCENDING) {
            DriveSortOrder.DESCENDING
        } else {
            DriveSortOrder.ASCENDING
        }
        appSettings.setDriveSortOrder(nextOrder)
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
    fun enter(item: DriveItemEntity) {
        if (!item.isContainer) return
        val currentTrail = _uiState.value.trail
        if (currentTrail.any { it.drivewsid == item.drivewsid }) return
        val newTrail = currentTrail + DriveCrumb(item.drivewsid, item.name)
        _uiState.value = _uiState.value.copy(trail = newTrail)
        saveTrailToSavedState(newTrail)
        observeAndRefresh(DriveCrumb(item.drivewsid, item.name))
    }

    /** Pops the top crumb. Returns false if already at the root. */
    fun goUp(): Boolean {
        val currentTrail = _uiState.value.trail
        if (currentTrail.size <= 1) return false
        val newTrail = currentTrail.dropLast(1)
        _uiState.value = _uiState.value.copy(trail = newTrail)
        saveTrailToSavedState(newTrail)
        observeAndRefresh(newTrail.last())
        return true
    }

    /** User triggered pull-to-refresh / swipe on current directory. */
    fun refresh() {
        val current = _uiState.value.trail.lastOrNull() ?: return
        observeAndRefresh(current)
    }

    /** Manually triggers an incremental D3 walk. */
    /**
     * Manually triggers an incremental D3 walk. Always runs, ignoring the freshness window —
     * pressing the button is an explicit "check now".
     */
    fun syncIncremental() = runSync(force = true)

    /**
     * The automatic pass fired when the screen first observes the current folder.
     *
     * Throttled on purpose. This used to call [syncIncremental] unconditionally, so *every* visit
     * to the Drive tab re-probed the entire cached tree — up to 200 containers, one request each,
     * behind an indeterminate progress strip. Tabbing away and back re-ran the whole thing.
     */
    private fun syncIncrementalIfStale() = runSync(force = false)

    private fun runSync(force: Boolean) {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            try {
                val reportResult = if (force) {
                    repository.syncIncremental()
                } else {
                    repository.syncIncrementalIfStale()
                }

                // A throttled skip is not an error and must not clear the existing summary —
                // "已是最新（检查 37 个文件夹）" from the previous pass is still accurate.
                if (reportResult.exceptionOrNull() is DriveRepository.SyncThrottledException) {
                    Log.d(TAG, "sync skipped: a recent pass is still fresh")
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    lastSyncSummary = reportResult.getOrNull()?.let { summarize(it) }
                )
            } catch (e: Exception) {
                Log.w(TAG, "syncIncremental failed", e)
            } finally {
                _uiState.value = _uiState.value.copy(isSyncing = false)
            }
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

            // Outcome counts, aggregated per selection.
            //
            // `GetMultipleContents()` lets one pick return dozens of files, so reporting per file
            // would queue up dozens of toasts. One summary at the end is the only readable option.
            var deduped = 0
            var revived = 0
            var renamed = 0
            var renamedSample: String? = null

            for (uri in uris) {
                repository.enqueueUpload(uri, folderId, folderPath) { outcome ->
                    when (outcome) {
                        is DriveRepository.EnqueueOutcome.Deduped -> deduped++
                        is DriveRepository.EnqueueOutcome.Revived -> revived++
                        is DriveRepository.EnqueueOutcome.Renamed -> {
                            renamed++
                            if (renamedSample == null) renamedSample = outcome.filename
                        }
                        is DriveRepository.EnqueueOutcome.Queued -> Unit
                    }
                }
            }

            val parts = buildList {
                if (deduped > 0) add("$deduped 个文件已在队列中")
                if (revived > 0) add("$revived 个失败任务已重新排队")
                if (renamed > 0) add("$renamed 个同名文件已改名为 $renamedSample 等")
            }
            if (parts.isNotEmpty()) {
                android.widget.Toast.makeText(
                    context,
                    parts.joinToString("；"),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }

            // NOTE: no second `DriveUploadWorker.enqueue(context)` here — `enqueueUpload` already
            // schedules one per file. Calling it again per selection only gave KEEP more chances
            // to be misjudged.

            // Fast recheck for the "KEEP dropped us because the slot was not released yet" case.
            // The slow one lives in DriveRepository.scheduleStuckRecheck() and covers the case this
            // delay is far too short to see: a work JobScheduler deferred by tens of minutes.
            kotlinx.coroutines.delay(ENQUEUE_RECOVERY_DELAY_MS)
            val stillQueued = repository.uploadQueue.hasPendingWork()
            if (stillQueued && (!DriveUploadWorker.isWorkActive(context) ||
                    DriveUploadWorker.isOutOfWindow(context))
            ) {
                android.util.Log.w(
                    TAG,
                    "No usable upload work after enqueue; forcing a drain pass"
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

    /** Preview action triggered by direct click on a file. */
    fun previewItem(item: DriveItemEntity, context: Context) {
        if (!item.isDownloadable) return
        val ext = item.extension?.lowercase()?.trim() ?: ""

        when {
            isMediaDriveItem(ext) -> {
                _uiState.value = _uiState.value.copy(previewingItem = item)
            }
            ext == "pages" || ext == "numbers" || ext == "key" || ext == "keynote" ||
                item.name.contains("Pages", ignoreCase = true) ||
                item.name.contains("Numbers", ignoreCase = true) ||
                item.name.contains("Keynote", ignoreCase = true) -> {
                _uiState.value = _uiState.value.copy(webIWorkItem = item)
            }
            else -> {
                viewModelScope.launch {
                    val localFile = repository.checkDownloadedFile(item)
                    if (localFile?.uri != null) {
                        launchIntentView(context, localFile.uri, item.extension)
                        return@launch
                    }

                    _uiState.value = _uiState.value.copy(downloadMessage = "正在准备 ${item.name} 预览...")
                    val result = repository.fetchFileToPreviewCache(item)
                    result.fold(
                        onSuccess = { file ->
                            _uiState.value = _uiState.value.copy(downloadMessage = null)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "app.acloudroid.qwq.fileprovider",
                                file
                            )
                            launchIntentView(context, uri, item.extension)
                        },
                        onFailure = { err ->
                            _uiState.value = _uiState.value.copy(downloadMessage = "预览失败: ${err.message}")
                        }
                    )
                }
            }
        }
    }

    private fun launchIntentView(context: Context, uri: android.net.Uri, extension: String?) {
        val mime = DriveDownloader.mimeTypeFor(extension)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                _uiState.value = _uiState.value.copy(downloadMessage = "无法找到能打开该文件的应用")
            }
    }

    fun openIWorkWebSuite(item: DriveItemEntity) {
        _uiState.value = _uiState.value.copy(webIWorkItem = item)
    }

    fun closePreview() {
        _uiState.value = _uiState.value.copy(previewingItem = null, webIWorkItem = null)
    }

    /** Explicit download action triggered by the download button to the left of the ⁝ button. */
    fun downloadItemToDownloads(item: DriveItemEntity) {
        if (!item.isDownloadable) return
        viewModelScope.launch {
            repository.enqueueDownload(item.drivewsid)
        }
    }

    fun shareItem(item: DriveItemEntity, context: Context) {
        if (!item.isDownloadable) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(downloadMessage = "正在准备 ${item.name} 分享文件...")
            val result = repository.getShareableUri(item, context)
            result.fold(
                onSuccess = { uri ->
                    _uiState.value = _uiState.value.copy(downloadMessage = null)
                    val mime = DriveDownloader.mimeTypeFor(item.extension)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = mime.ifBlank { "*/*" }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(shareIntent, "分享 ${item.name}").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(chooser) }
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(downloadMessage = "分享准备失败: ${err.message}")
                }
            )
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
                onSuccess = { newFolder ->
                    Log.i(TAG, "Created folder ${newFolder.name}")
                    refresh()
                },
                onFailure = { e ->
                    Log.w(TAG, "createFolder failed", e)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "新建文件夹失败: ${e.message}"
                    )
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
                    Log.i(TAG, "Renamed ${item.name} -> $trimmed")
                    refresh()
                },
                onFailure = { e ->
                    Log.w(TAG, "renameItem failed", e)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "重命名失败: ${e.message}"
                    )
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
                    refresh()
                },
                onFailure = { e ->
                    Log.w(TAG, "moveItems failed", e)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "移动失败: ${e.message}"
                    )
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
                    refresh()
                },
                onFailure = { e ->
                    Log.w(TAG, "deleteItems failed", e)
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "删除失败: ${e.message}"
                    )
                }
            )
        }
    }

    private fun observeAndRefresh(crumb: DriveCrumb) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repository.observeChildren(crumb.drivewsid).collect { children ->
                rawChildren = children
                applySortingAndPublish()
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
        private const val ENQUEUE_RECOVERY_DELAY_MS = 800L
    }
}
