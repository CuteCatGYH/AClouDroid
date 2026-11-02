package com.getup.iClouDroid.feature.drive.data

import android.content.Context
import android.util.Log
import com.getup.iClouDroid.BuildConfig
import com.getup.iClouDroid.data.lock.DataMutationLock
import com.getup.iClouDroid.data.session.SessionManager
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity
import com.getup.icloudcore.Icloudcore
import kotlinx.coroutines.CancellationException
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
    private val appContext: Context
) {

    private val dao = database.driveItemDao()

    /** Publishes downloaded bytes into MediaStore. Stateless, so one instance is enough. */
    private val downloader = DriveDownloader(appContext)

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
        } catch (e: CancellationException) {
            // Navigating to another folder cancels the in-flight listing. That is normal control
            // flow, not a failure — logging it as an error produced a wall of scary-looking
            // stack traces for something the user did on purpose.
            Log.d(TAG, "refreshFolder cancelled drivewsid=$target")
            throw e
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
    // D3 — incremental sync walk
    // ----------------------------------------------------------------------------------------

    /**
     * Re-lists only the containers whose `etag` moved, and removes rows that vanished.
     *
     * ## Why a walk and not a cursor
     *
     * Drive has no `changes` endpoint (REFERENCE.md §5.6–5.7), so there is no token to resume
     * from. Instead this visits every cached container in parent-first order and asks the cheap
     * single-node endpoint for its current `etag`. Containers whose tag is unchanged cost one
     * small request and no database writes; the rest get a full re-list.
     *
     * ## Concurrency
     *
     * Held under [DataMutationLock] for the whole walk, not per-container. A partial walk that
     * released the lock between containers could interleave with a foreground [refreshFolder],
     * and the two would then each delete rows the other just wrote.
     *
     * ## Abort semantics
     *
     * Every early exit sets [SyncReport.walkCompleted] to false and fills `abortReason`. The
     * caller must treat an incomplete walk as "nothing conclusive happened" — in particular it
     * must not run any cleanup based on what the walk saw, because containers it never reached
     * would look deleted.
     *
     * @param maxContainers caps one pass so a huge tree cannot monopolise the lock. The walk
     *   resumes on the next call; it is not a correctness limit, only a fairness one.
     */
    suspend fun syncIncremental(
        maxContainers: Int = DEFAULT_SYNC_CONTAINER_BUDGET
    ): Result<DriveDeltaPlanner.SyncReport> = dataMutationLock.withLock {
        withContext(Dispatchers.IO) { syncIncrementalLocked(maxContainers) }
    }

    private suspend fun syncIncrementalLocked(
        maxContainers: Int
    ): Result<DriveDeltaPlanner.SyncReport> {
        wipeBlocked()?.let { return Result.failure(it) }

        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        val candidates = try {
            dao.containersForSync().map {
                DriveDeltaPlanner.Candidate(
                    drivewsid = it.drivewsid,
                    parentDrivewsid = it.parentDrivewsid,
                    name = it.name,
                    cachedEtag = it.etag,
                    childrenFetched = it.childrenFetched,
                    lastSyncedAt = it.lastSyncedAt
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncIncremental: failed to read candidates", e)
            return Result.failure(e)
        }

        if (candidates.isEmpty()) {
            return Result.success(DriveDeltaPlanner.SyncReport(walkCompleted = true))
        }

        val ordered = DriveDeltaPlanner.orderParentsFirst(candidates)
        val budget = ordered.take(maxContainers.coerceAtLeast(1))
        val budgetTruncated = budget.size < ordered.size

        var listed = 0
        var skipped = 0
        var removedRows = 0
        val now = System.currentTimeMillis()

        for (candidate in budget) {
            // A logout can start mid-walk (P2-018). Abort rather than write rows for an account
            // whose tables are being dropped underneath us.
            if (isWiping) {
                return Result.success(
                    DriveDeltaPlanner.SyncReport(
                        containersConsidered = budget.size,
                        listed = listed,
                        skipped = skipped,
                        removedRows = removedRows,
                        walkCompleted = false,
                        abortReason = "正在退出登录，同步已中断"
                    )
                )
            }

            when (val outcome = syncOne(candidate, now)) {
                is SyncOne.Listed -> {
                    listed++
                    removedRows += outcome.removed
                }
                SyncOne.Skipped -> skipped++
                is SyncOne.Failed -> {
                    // One bad container must not abort the whole walk: a single 404 on a folder
                    // deleted from another device is normal, and stopping would leave every
                    // container after it permanently unsynced.
                    Log.w(TAG, "syncIncremental: ${candidate.drivewsid} failed: ${outcome.reason}")
                }
                SyncOne.Aborted -> return Result.success(
                    DriveDeltaPlanner.SyncReport(
                        containersConsidered = budget.size,
                        listed = listed,
                        skipped = skipped,
                        removedRows = removedRows,
                        walkCompleted = false,
                        abortReason = "认证失效"
                    )
                )
            }
        }

        val report = DriveDeltaPlanner.SyncReport(
            containersConsidered = budget.size,
            listed = listed,
            skipped = skipped,
            removedRows = removedRows,
            walkCompleted = !budgetTruncated,
            abortReason = if (budgetTruncated) "本轮已达容器预算，下次继续" else null
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "syncIncremental: considered=${report.containersConsidered} listed=$listed " +
                    "skipped=$skipped removed=$removedRows completed=${report.walkCompleted}"
            )
        }
        return Result.success(report)
    }

    private sealed interface SyncOne {
        /** The container was re-listed; [removed] rows were deleted as a result. */
        data class Listed(val removed: Int) : SyncOne

        /** `etag` unchanged and recently verified — no network listing, no writes. */
        data object Skipped : SyncOne

        /** This container could not be synced; the walk continues with the next one. */
        data class Failed(val reason: String) : SyncOne

        /** Auth is gone; the walk must stop and the caller should retry later. */
        data object Aborted : SyncOne
    }

    /**
     * Probes one container and lists it only if the planner says so.
     *
     * Returns [SyncOne.Skipped] without touching the DB when the `etag` matches — the whole point
     * of the design is that an unchanged tree costs one small request per container and zero
     * writes.
     */
    private suspend fun syncOne(
        candidate: DriveDeltaPlanner.Candidate,
        now: Long
    ): SyncOne {
        val probeRaw = Icloudcore.retrieveItemDetails(candidate.drivewsid)
        if (isAuthError(probeRaw)) return SyncOne.Aborted

        // A parse failure here leaves `probe` null, which the planner turns into LIST_STALE
        // rather than SKIP. That is deliberate: an unreadable probe must not be allowed to
        // vouch for "nothing changed".
        val probe = runCatching { DriveItemMapper.parseSingleItem(JSONObject(probeRaw)) }.getOrNull()
        val freshEtag = probe?.etag

        return when (DriveDeltaPlanner.decide(candidate, freshEtag, now)) {
            DriveDeltaPlanner.Action.SKIP -> {
                // Stamp the successful probe so the staleness window restarts. Without this a
                // container is judged stale forever once DEFAULT_MAX_AGE_MS has passed, even
                // though every probe is succeeding.
                dao.updateProbeResult(
                    drivewsid = candidate.drivewsid,
                    etag = freshEtag,
                    childCount = probe?.directChildrenCount ?: 0,
                    now = now
                )
                SyncOne.Skipped
            }

            DriveDeltaPlanner.Action.LIST_UNKNOWN,
            DriveDeltaPlanner.Action.LIST_CHANGED,
            DriveDeltaPlanner.Action.LIST_STALE -> listAndReconcile(candidate, now)
        }
    }

    /**
     * Full re-list of one container, then deletes whatever is no longer there.
     *
     * The deletion step is the only place D3 can lose a live row, so it is gated on the listing
     * having actually succeeded. A failed parse ([DriveItemMapper.parseFolderList] returning
     * null) exits without deleting anything — an unparseable response must never read as an
     * empty folder.
     */
    private suspend fun listAndReconcile(
        candidate: DriveDeltaPlanner.Candidate,
        now: Long
    ): SyncOne {
        val jsonStr = Icloudcore.retrieveItemDetailsInFolders(candidate.drivewsid)
        if (isAuthError(jsonStr)) return SyncOne.Aborted

        val json = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            return SyncOne.Failed("解析失败: ${e.message}")
        }

        if (!json.optBoolean("success", false)) {
            return SyncOne.Failed(json.optString("errorMessage", "目录读取失败"))
        }

        val children = DriveItemMapper.parseFolderList(candidate.drivewsid, json)
            ?: return SyncOne.Failed("响应不是有效的目录列表")

        val freshIds = children.mapTo(HashSet()) { it.drivewsid }
        val localIds = dao.childIdsOf(candidate.drivewsid)
        val removedIds = DriveDeltaPlanner.removedChildren(localIds, freshIds)

        dao.replaceChildrenRaw(candidate.drivewsid, children)
        dao.markChildrenFetched(candidate.drivewsid, now)

        if (removedIds.isNotEmpty()) {
            // A removed folder takes its whole subtree with it, otherwise the descendants stay
            // cached pointing at a row that no longer exists (orphans the UI can never reach).
            val subtree = collectSubtree(removedIds)
            dao.deleteByIds(subtree)
            Log.d(TAG, "syncIncremental: purged ${subtree.size} rows under ${candidate.drivewsid}")
        }

        return SyncOne.Listed(removed = removedIds.size)
    }

    /**
     * Expands a list of ids to include everything beneath them.
     *
     * Breadth-first with a visited set: a malformed parent pointer could otherwise make this
     * loop forever, and the cost of the guard is negligible next to the query.
     */
    private suspend fun collectSubtree(rootIds: List<String>): List<String> {
        val all = LinkedHashSet<String>()
        var frontier = rootIds
        while (frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (id in frontier) {
                if (!all.add(id)) continue
                next.addAll(dao.childIdsOf(id))
            }
            frontier = next
        }
        return all.toList()
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

    // ----------------------------------------------------------------------------------------
    // D4 — download
    // ----------------------------------------------------------------------------------------

    /**
     * Downloads one file into the shared Downloads collection.
     *
     * ## Why this does not take [DataMutationLock]
     *
     * That lock exists to serialise writes to `drive_items` (AGENTS §12). A download writes no
     * database rows at all — it only reads one row and streams bytes to storage. Taking the lock
     * would make every download queue behind the incremental sync walk, which can run for
     * minutes over a large tree, and it would **block the very sync that is holding the lock
     * from finishing** if the download were slow. The lock protects a resource this path does not
     * touch.
     *
     * ## Why the URL is always re-minted
     *
     * The signed URL from `download/by_id` is short-lived. Caching it would look like an
     * optimisation and then fail for the user who starts a download, backgrounds the app, and
     * resumes ten minutes later. Minting costs one small request; a stale URL costs a failed
     * multi-megabyte download.
     */
    suspend fun downloadFile(
        drivewsid: String,
        onProgress: ((Int) -> Unit)? = null
    ): Result<DriveDownloadOutcome> {
        wipeBlocked()?.let { return Result.failure(it) }

        val item = try {
            dao.getById(drivewsid)
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile: failed to read row $drivewsid", e)
            return Result.failure(e)
        } ?: return Result.failure(Exception("找不到该文件"))

        if (item.type != DriveItemEntity.TYPE_FILE) {
            return Result.failure(Exception("只有文件可以下载"))
        }

        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        return downloader.download(item, onProgress)
    }

    companion object {
        private const val TAG = "DriveRepository"

        /** Apple's two "your session is no longer valid" statuses. */
        private val AUTH_STATUS_CODES = setOf(401, 421)

        /**
         * How many containers one incremental pass may visit.
         *
         * Not a correctness limit — the walk resumes on the next call. It exists so a large tree
         * cannot hold [DataMutationLock] for minutes and starve foreground folder refreshes.
         */
        internal const val DEFAULT_SYNC_CONTAINER_BUDGET = 200
    }
}
