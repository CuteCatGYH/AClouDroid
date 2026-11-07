package app.acloudroid.qwq.feature.local.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.feature.local.data.LocalAlbum
import app.acloudroid.qwq.feature.photos.work.PhotoBackupWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LocalAlbumsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IClouDroidApp
    private val repository = app.container.localAlbumRepository
    private val settings = app.container.appSettings
    private val db = app.container.photoDatabase

    private val _albums = MutableStateFlow<List<LocalAlbum>>(emptyList())
    val albums: StateFlow<List<LocalAlbum>> = _albums.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val queueTasks: StateFlow<List<app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity>> =
        db.uploadQueueDao().observeAllTasks()
            .stateIn(
                scope = viewModelScope,
                started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    fun loadAlbums() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val enabledBucketIds = settings.enabledBackupBucketIds.value
                _albums.value = repository.queryLocalAlbums(enabledBucketIds)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleAlbumBackup(bucketId: String, enabled: Boolean) {
        viewModelScope.launch {
            settings.toggleBackupBucketId(bucketId, enabled)
            if (enabled) {
                val newlyEnqueued = repository.scanAndEnqueueBucket(
                    bucketId,
                    db.uploadQueueDao(),
                    db.uploadedFingerprintDao()
                )
                if (newlyEnqueued > 0) {
                    PhotoBackupWorker.scheduleBackup(getApplication())
                }
            }
            loadAlbums()
        }
    }

    fun retryFailedTasks() {
        viewModelScope.launch {
            db.uploadQueueDao().retryFailedTasks()
            PhotoBackupWorker.scheduleBackup(getApplication())
        }
    }

    fun clearCompletedTasks() {
        viewModelScope.launch {
            db.uploadQueueDao().clearCompleted()
        }
    }

    val forceUploadUntilTimestamp: StateFlow<Long> = settings.forceUploadUntilTimestamp

    fun triggerForceUploadForOneHour() {
        viewModelScope.launch {
            settings.enableForceUploadForOneHour()
            PhotoBackupWorker.scheduleBackup(getApplication())
        }
    }

    fun removeTask(taskId: Long) {
        viewModelScope.launch {
            db.uploadQueueDao().deleteById(taskId)
        }
    }

    fun clearAllTasks() {
        viewModelScope.launch {
            db.uploadQueueDao().clearAll()
        }
    }
}
