package com.getup.iClouDroid.feature.drive.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val isInitialLoading: Boolean = true
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

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Subscribes to the cached children of [crumb], then refreshes from the server.
     *
     * The order matters: subscribe first so an already-cached folder paints immediately, then
     * refresh. Doing it the other way would blank the screen on every back-navigation to a folder
     * the user has already visited.
     */
    private fun observeAndRefresh(crumb: DriveCrumb) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repository.observeChildren(crumb.drivewsid).collect { children ->
                _uiState.value = _uiState.value.copy(
                    items = children,
                    isInitialLoading = false
                )
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
            repository.refreshFolder(crumb.drivewsid).onFailure { e ->
                Log.w(TAG, "refreshFolder failed for ${crumb.drivewsid}", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message ?: "目录读取失败"
                )
            }
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                isInitialLoading = false
            )
        }
    }

    companion object {
        private const val TAG = "DriveViewModel"
    }
}
