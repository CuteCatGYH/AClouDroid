package com.getup.iClouDroid.feature.drive.data

import android.content.Context
import android.util.Log
import com.getup.iClouDroid.BuildConfig
import com.getup.iClouDroid.data.lock.DataMutationLock
import com.getup.iClouDroid.data.session.SessionManager
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity
import com.getup.icloudcore.Icloudcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The only place allowed to talk to the Go core for Drive (AGENTS §10: the UI layer must never
 * call `Icloudcore.*` directly).
 *
 * ## Session handling
 *
 * This reuses the same Go session that Photos primes. It does **not** duplicate the session
 * rebuild logic: [ensureGoSession] here is a thin, Drive-specific version of the Photos guard,
 * and the two converge on the same `Icloudcore.initSession` call and the same encrypted cookie
 * store. What it deliberately does *not* do is bump the session generation — that counter exists
 * to invalidate cached photo CDN URLs (AGENTS §5.3), and Drive has no equivalent cache yet.
 *
 * ## Concurrency
 *
 * Every write to `drive_items` goes through [DataMutationLock], the app-wide lock shared with
 * photos. Two feature-local locks would each look correct in isolation and still let a Drive
 * sync and a photo reconcile interleave against the same account.
 */
class DriveRepository(
    private val database: DriveDatabase,
    private val sessionManager: SessionManager,
    private val dataMutationLock: DataMutationLock,
    @Suppress("unused") private val appContext: Context
) {

    private val dao = database.driveItemDao()

    /** Serialises session rebuilds so N concurrent folder opens do not each call /validate. */
    private val sessionMutex = Mutex()

    @Volatile
    private var isWiping = false

    // ----------------------------------------------------------------------------------------
    // Reads
    // ----------------------------------------------------------------------------------------

    /**
     * Live children of a container.
     *
     * Read-only: this must never wait behind [DataMutationLock], or a gallery-style scroll would
     * block on a background sync.
     */
    fun observeChildren(parentDrivewsid: String): Flow<List<DriveItemEntity>> =
        dao.observeChildren(parentDrivewsid)

    fun observeItem(drivewsid: String): Flow<DriveItemEntity?> = dao.observeById(drivewsid)

    suspend fun getItem(drivewsid: String): DriveItemEntity? = withContext(Dispatchers.IO) {
        dao.getById(drivewsid)
    }

    /** True when nothing has been listed yet, so the UI can show a first-run state. */
    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) { dao.count() == 0 }

    // ----------------------------------------------------------------------------------------
    // Refresh
    // ----------------------------------------------------------------------------------------

    /**
     * Lists one folder from the server and caches its children.
     *
     * Returns the number of children cached. A folder that genuinely has none returns
     * `Result.success(0)` — that is *not* a failure, and the UI distinguishes it by showing an
     * empty state rather than an error.
     *
     * On an auth error the session is rebuilt once and the call retried, matching the pattern
     * every Photos entry point uses (AGENTS §7.1: only AUTH_EXPIRED justifies a rebuild;
     * TIMEOUT / RATE_LIMITED must simply fail through so the caller can back off).
     */
    suspend fun refreshFolder(drivewsid: String): Result<Int> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it) }

        val target = drivewsid.ifBlank { DriveItemEntity.ROOT_DRIVEWSID }

        var sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(
                sessionCheck.exceptionOrNull() ?: Exception("会话建立失败")
            )
        }

        try {
            var jsonStr = Icloudcore.retrieveItemDetailsInFolders(target)

            if (isAuthError(jsonStr)) {
                Log.w(TAG, "refreshFolder got auth error, forcing session rebuild…")
                sessionCheck = ensureGoSession(force = true)
                if (sessionCheck.isFailure) {
                    return@withContext Result.failure(
                        sessionCheck.exceptionOrNull() ?: Exception("会话重建失败")
                    )
                }
                jsonStr = Icloudcore.retrieveItemDetailsInFolders(target)
            }

            val json = JSONObject(jsonStr)
            if (!json.optBoolean("success", false)) {
                val msg = json.optString("errorMessage", "目录读取失败")
                Log.w(TAG, "refreshFolder failed drivewsid=$target: $msg")
                return@withContext Result.failure(Exception(msg))
            }

            // null means "not a successful payload" (already handled above); an empty list is a
            // real answer and must reach replaceChildren() so childrenFetched gets stamped.
            val children = DriveItemMapper.parseFolderList(target, json)
                ?: return@withContext Result.success(0)

            dataMutationLock.withLock {
                // Make sure the container itself is known locally before its children point at it,
                // otherwise a first-run listing of a deep link would leave orphan child rows.
                dao.insertRootIfMissing(DriveItemMapper.rootEntity())
                dao.replaceChildren(target, children)
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "refreshFolder $target -> ${children.size} children")
            }
            Result.success(children.size)
        } catch (e: Exception) {
            Log.e(TAG, "refreshFolder error drivewsid=$target", e)
            Result.failure(e)
        }
    }

    /**
     * Makes sure the synthetic root row exists.
     *
     * Called on first composition so `observeChildren(ROOT)` emits something even before the
     * network round trip finishes — otherwise the screen shows a spinner where it could show an
     * empty container that is obviously still loading.
     */
    suspend fun ensureRoot() = withContext(Dispatchers.IO) {
        dao.insertRootIfMissing(DriveItemMapper.rootEntity())
    }

    // ----------------------------------------------------------------------------------------
    // Session guard (Drive-specific)
    // ----------------------------------------------------------------------------------------

    /**
     * Ensures the Go core has a session with a Drive endpoint.
     *
     * Deliberately checks `drivewsURL`, not `ckDatabaseURL`: an account can have Photos but not
     * Drive (Apple lets you disable iCloud Drive per-account), and in that case the session is
     * "ready" for photos and useless here. Reporting that as a clear error beats minting
     * requests to an empty host.
     *
     * NOTE: does **not** call `bumpSessionGeneration()`. That counter exists to invalidate cached
     * photo CDN signatures (AGENTS §5.3) and bumping it from a Drive code path would mark the
     * entire photo library stale — the exact P2-008 refresh storm.
     */
    suspend fun ensureGoSession(force: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    if (!force) {
                        val summaryJson = runCatching {
                            JSONObject(Icloudcore.getSessionStateSummary())
                        }.getOrNull()

                        val hasActiveSession = summaryJson != null &&
                            summaryJson.optBoolean("hasAuthToken", false) &&
                            summaryJson.optString("drivewsURL", "").isNotBlank()

                        if (hasActiveSession) return@withContext Result.success(Unit)
                    }

                    Log.i(TAG, "Rebuilding Go core session for Drive (force=$force)…")
                    val cookieStr = sessionManager.getCookieString()
                    if (cookieStr.isNullOrBlank()) {
                        return@withContext Result.failure(
                            Exception("本地缺少 iCloud Cookie 凭证，请重新登录")
                        )
                    }

                    val jsonResult = Icloudcore.initSession(cookieStr, sessionManager.isChinaMainland())
                    val json = JSONObject(jsonResult)

                    if (!json.optBoolean("success", false)) {
                        val errMsg = json.optString(
                            "errorMessage",
                            "会话初始化失败，请在“账号”页面重新登录"
                        )
                        return@withContext Result.failure(Exception(errMsg))
                    }

                    if (json.optString("drivewsUrl", "").isBlank()) {
                        Log.w(TAG, "Session has no drivewsUrl — iCloud Drive may be disabled")
                        return@withContext Result.failure(
                            Exception("该 Apple ID 未启用 iCloud Drive")
                        )
                    }

                    // Route info is shared with photos; keep it fresh so the next feature
                    // (and the next cold start) sees the current dsid.
                    val dsid = json.optString("dsid", "")
                    val ckUrl = json.optString("ckDatabaseUrl", "")
                    if (dsid.isNotBlank() && ckUrl.isNotBlank()) {
                        sessionManager.updateRouteInfo(dsid, ckUrl)
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "ensureGoSession error", e)
                    Result.failure(e)
                }
            }
        }

    /**
     * Whether a response is an authentication failure.
     *
     * Reads the structured `errorCode` Go emits (P2-015) instead of regex-matching the raw JSON.
     * Substring matching was the cause of two production incidents where a base64 cursor
     * containing "401" triggered an unnecessary session rebuild (AGENTS §7.1).
     */
    private fun isAuthError(jsonStr: String): Boolean = runCatching {
        val json = JSONObject(jsonStr)
        if (json.optBoolean("success", true)) return@runCatching false
        val code = json.optString("errorCode", "")
        code == "AUTH_EXPIRED" || json.optInt("httpStatus", 0) in AUTH_STATUS_CODES
    }.getOrDefault(false)

    // ----------------------------------------------------------------------------------------
    // Logout guard
    // ----------------------------------------------------------------------------------------

    fun beginWipe() {
        isWiping = true
        Log.i(TAG, "Session teardown started — blocking all drive network entry points")
    }

    fun endWipe() {
        isWiping = false
        Log.i(TAG, "Session teardown finished — drive network entry points re-enabled")
    }

    internal fun isWipeInProgress(): Boolean = isWiping

    private fun wipeBlocked(): Exception? =
        if (isWiping) Exception("正在退出登录，已暂停 iCloud Drive 同步") else null

    /**
     * Drops every cached Drive row.
     *
     * Must run during logout for the same reason photos do (AGENTS §5.5): `drive_items` is keyed
     * by CloudKit-side ids that belong to one specific account. Keeping them across a re-login
     * leaves a tree whose entries the new account cannot fetch, which reads as "folders are there
     * but empty".
     */
    suspend fun wipeAllData(): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.clearAll()
            true
        } catch (e: Exception) {
            Log.e(TAG, "wipeAllData failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "DriveRepository"

        /** Apple's two "your session is no longer valid" statuses. */
        private val AUTH_STATUS_CODES = setOf(401, 421)
    }
}
