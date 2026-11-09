package app.acloudroid.qwq.di

import android.content.Context
import app.acloudroid.qwq.data.lock.DataMutationLock
import app.acloudroid.qwq.data.session.SessionManager
import app.acloudroid.qwq.feature.drive.data.DriveDatabase
import app.acloudroid.qwq.feature.drive.data.DriveRepository
import app.acloudroid.qwq.data.settings.AppSettings
import app.acloudroid.qwq.feature.photos.data.MonthScanEngine
import app.acloudroid.qwq.feature.photos.data.PhotoDatabase
import app.acloudroid.qwq.feature.photos.data.PhotoRepository

/**
 * Single owner of app-scoped dependencies.
 *
 * This is deliberately hand-written rather than Hilt: the graph is tiny, and an explicit
 * container keeps the wiring readable while the app grows from one feature (photos) to many
 * (photos / drive / notes). Adding a feature means adding a field here — not cloning another
 * `getInstance()` singleton template.
 *
 * [SessionManager] lives here on purpose: cookies, dsid and the session generation counter are
 * shared by every iCloud feature, not just photos.
 */
class AppContainer(private val appContext: Context) {

    val sessionManager: SessionManager by lazy {
        SessionManager(appContext)
    }

    /**
     * User preferences.
     *
     * Lives here (not in a feature package) because settings are app-wide: the periodic scan
     * scheduler, the settings screen, and any future feature all read the same instance.
     */
    val appSettings: AppSettings by lazy {
        AppSettings(appContext)
    }

    /** App context, for the schedulers that need it without holding an Activity. */
    val context: Context get() = appContext

    /**
     * The one lock every data-mutating background path takes — photos and drive alike.
     *
     * App-scoped on purpose: two feature-local locks would each look correct and still let a
     * photo reconcile and a drive upload interleave against the same iCloud account
     * (AGENTS §12, "上传任务必须共用这把，不得另起").
     */
    val dataMutationLock: DataMutationLock by lazy {
        DataMutationLock()
    }

    val photoDatabase: PhotoDatabase by lazy {
        PhotoDatabase.getInstance(appContext)
    }

    val photoRepository: PhotoRepository by lazy {
        PhotoRepository(photoDatabase, sessionManager, appSettings, appContext, dataMutationLock)
    }

    /**
     * Walks the whole library into month sections (P2-016).
     *
     * App-scoped rather than ViewModel-scoped on purpose: the scan must keep running when the
     * screen goes away, and it is driven from both the UI (`viewModelScope`) and
     * `MonthScanWorker`. Both paths contend on the same instance, which serialises them.
     */
    val monthScanEngine: MonthScanEngine by lazy {
        MonthScanEngine(photoDatabase, photoRepository, appSettings)
    }

    val localAlbumRepository: app.acloudroid.qwq.feature.local.data.LocalAlbumRepository by lazy {
        app.acloudroid.qwq.feature.local.data.LocalAlbumRepository(appContext)
    }

    val driveDatabase: DriveDatabase by lazy {
        DriveDatabase.getInstance(appContext)
    }

    val driveRepository: DriveRepository by lazy {
        DriveRepository(driveDatabase, sessionManager, dataMutationLock, appContext)
    }

    val updateRepository: app.acloudroid.qwq.feature.update.data.UpdateRepository by lazy {
        app.acloudroid.qwq.feature.update.data.UpdateRepository(appSettings, appContext)
    }

    // Future features plug in here:
    // val notesRepository: NotesRepository by lazy { NotesRepository(...) }
}
