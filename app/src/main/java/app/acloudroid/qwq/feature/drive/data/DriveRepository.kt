package app.acloudroid.qwq.feature.drive.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.acloudroid.qwq.BuildConfig
import app.acloudroid.qwq.data.lock.DataMutationLock
import app.acloudroid.qwq.data.session.SessionManager
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.drive.data.db.DriveUploadEntity
import app.acloudroid.qwq.feature.drive.work.DriveUploadWorker
import com.getup.icloudcore.Icloudcore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    /** Manages temporary sandbox preview cache (`cache/drive_preview/`). */
    val previewCache = DrivePreviewCache(appContext)

    /** Serialises session rebuilds across all features so N concurrent API calls do not each call /validate. */
    private val sessionMutex get() = sessionManager.sessionMutex

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
    ): Result<DriveDeltaPlanner.SyncReport> = withContext(Dispatchers.IO) {
        syncIncrementalLocked(maxContainers)
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
                dataMutationLock.withLock {
                    dao.updateProbeResult(
                        drivewsid = candidate.drivewsid,
                        etag = freshEtag,
                        childCount = probe?.directChildrenCount ?: 0,
                        now = now
                    )
                }
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

        return dataMutationLock.withLock {
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

            SyncOne.Listed(removed = removedIds.size)
        }
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

                        // `docws` must be checked too, not just `drivews`.
                        //
                        // Uploads and downloads resolve their endpoint from
                        // `webservices.docws`, so a session with drivews but no docws passed this
                        // guard and then failed inside the Go call with
                        // `AUTH_EXPIRED: docws endpoint is not set`. That is a terminal error with
                        // no retry, so a queued file was marked FAILED instead of waiting for the
                        // session to finish initialising — observed right after a cold start.
                        val hasActiveSession = summaryJson != null &&
                            summaryJson.optBoolean("hasAuthToken", false) &&
                            summaryJson.optString("drivewsURL", "").isNotBlank() &&
                            summaryJson.optString("docwsURL", "").isNotBlank()

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

    val uploadQueue = DriveUploadQueue(database.driveUploadDao())
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * Deletes cached copies in `cacheDir/drive_uploads` that no live queue row references.
     *
     * Uploads stage the whole file here before streaming (AGENTS §3 forbids passing bytes through
     * JNI), so a rejected or crashed upload can leave a full-size copy behind — the failure path
     * used to skip cleanup entirely and 5.3 GB accumulated from four failed attempts.
     *
     * A sweep at startup is the only way to reclaim copies orphaned by a process death, where no
     * `finally` block ever runs. Live rows are protected by path, and QUEUED/UPLOADING rows are
     * still going to be streamed, so they are kept.
     */
    suspend fun sweepOrphanedUploadCache() = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(appContext.cacheDir, "drive_uploads")
            if (!dir.isDirectory) return@runCatching

            val livePaths = database.driveUploadDao().getPendingUploads()
                .map { it.localPath }
                .toSet()

            var removed = 0
            var freed = 0L
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.absolutePath !in livePaths) {
                    val len = file.length()
                    if (file.delete()) {
                        removed++
                        freed += len
                    }
                }
            }
            if (removed > 0) {
                Log.i(TAG, "sweepOrphanedUploadCache: removed $removed file(s), freed ${formatSize(freed)}")
            }
        }.onFailure { Log.w(TAG, "sweepOrphanedUploadCache failed: ${it.message}") }
    }

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
            uploadQueue.clearCompleted()
            true
        } catch (e: Exception) {
            Log.e(TAG, "wipeAllData failed", e)
            false
        }
    }

    // ----------------------------------------------------------------------------------------
    // D5 — upload
    // ----------------------------------------------------------------------------------------

    suspend fun enqueueUpload(
        uri: Uri,
        targetFolderDrivewsId: String,
        targetFolderPath: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it) }

        try {
            var filename = "upload_file"
            var filesize = 0L
            var mimeType = "application/octet-stream"

            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { filename = it }
                    }
                    if (sizeIdx >= 0) {
                        filesize = cursor.getLong(sizeIdx)
                    }
                }
            }

            appContext.contentResolver.getType(uri)?.let { mimeType = it }

            val uploadDir = File(appContext.cacheDir, "drive_uploads")
            if (!uploadDir.exists()) {
                uploadDir.mkdirs()
            }

            val localFile = File(uploadDir, "upload_${System.currentTimeMillis()}_$filename")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("无法读取选择的文件"))

            if (filesize <= 0L) {
                filesize = localFile.length()
            }

            val uploadEntity = DriveUploadEntity(
                localPath = localFile.absolutePath,
                targetFolderDrivewsId = targetFolderDrivewsId.ifBlank { DriveItemEntity.ROOT_DRIVEWSID },
                targetFolderPath = targetFolderPath.ifBlank { "/" },
                filename = filename,
                filesize = filesize,
                mimeType = mimeType
            )

            val id = uploadQueue.enqueue(uploadEntity)
            Log.i(TAG, "Enqueued upload #$id for $filename into $targetFolderPath")
            // Do NOT drain from here.
            //
            // `DriveUploadWorker` is the single drain engine (AGENTS §6.7.9 ①). This used to also
            // call `triggerUploadProcessor()`, and since both loops share `uploadDrainMutex` they
            // did not race — they took turns. The mutex only prevents CONCURRENT execution, not
            // REPEATED execution: engine A drained #13, released the lock, and engine B immediately
            // re-scanned the queue and uploaded the same file again. Observed on device as
            // `Upload finished (active 4 -> 3)` and `Processor executing upload #13` in the same
            // millisecond.
            //
            // Enqueue only writes the row. WorkManager survives a process kill, which the
            // in-process coroutine does not, so the worker is also the safer of the two.
            DriveUploadWorker.enqueue(appContext)
            Result.success(id)
        } catch (e: Exception) {
            Log.e(TAG, "enqueueUpload failed", e)
            Result.failure(e)
        }
    }

    /**
     * Serialises the whole drain loop.
     *
     * ## There is exactly one drain engine now
     *
     * [DriveUploadWorker] is the only caller of [drainUploadQueue]. This used to be shared with an
     * in-process `triggerUploadProcessor()` started from `enqueueUpload`, and the mutex below was
     * installed to stop the two from clashing. It did prevent *concurrent* execution, but not
     * *repeated* execution: after engine A released the lock, engine B re-scanned the queue and
     * uploaded the same rows again.
     *
     * The mutex is kept because WorkManager may still start a second worker instance (a retry
     * racing a fresh enqueue); it now simply serialises worker passes.
     */
    private val uploadDrainMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Drain entry point for [app.acloudroid.qwq.feature.drive.work.DriveUploadWorker].
     *
     * Returns the number of files uploaded so the worker can report progress.
     */
    suspend fun drainUploadQueue(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var succeeded = 0
        var failed = 0
        uploadDrainMutex.withLock {
            uploadQueue.resetUploadingToQueued()
            while (true) {
                val pending = uploadQueue.getNextPending() ?: break
                Log.i(TAG, "Worker executing upload #${pending.id}: ${pending.filename}")
                if (executeUpload(pending.id).isSuccess) succeeded++ else failed++
            }
        }
        succeeded to failed
    }

    suspend fun cancelUpload(uploadId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val upload = database.driveUploadDao().getById(uploadId)
            if (upload != null) {
                runCatching { File(upload.localPath).delete() }
                uploadQueue.delete(uploadId)
                Log.i(TAG, "Cancelled upload #$uploadId for ${upload.filename}")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "cancelUpload failed for #$uploadId", e)
            Result.failure(e)
        }
    }

    suspend fun retryUpload(uploadId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val upload = database.driveUploadDao().getById(uploadId)
            if (upload != null) {
                uploadQueue.retryUpload(uploadId)
                Log.i(TAG, "Retrying upload #$uploadId for ${upload.filename}")
                DriveUploadWorker.enqueue(appContext)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "retryUpload failed for #$uploadId", e)
            Result.failure(e)
        }
    }

    /**
     * Returns a user-facing reason when [filesize] can never be uploaded by this account, else null.
     *
     * The ceiling is a property of the account's region, not of the app (ISS-057): the same 1.96 GB
     * file uploads fine on icloud.com and returns 413 on icloud.com.cn. International accounts
     * therefore report a limit of 0 and must never be blocked here.
     *
     * Failing open on any error is deliberate — a broken region probe should degrade to "try the
     * upload" rather than silently refusing valid files.
     */
    private fun checkRegionUploadLimit(filesize: Long): String? {
        val info: JSONObject = runCatching {
            JSONObject(Icloudcore.getDriveUploadLimitInfo())
        }.getOrNull() ?: return null

        val limit = info.optLong("singleFileLimitBytes", 0L)
        if (limit <= 0L || filesize <= limit) return null

        return "文件过大 (${formatSize(filesize)})。当前账号（云上贵州区）不支持超过 " +
            "${formatSize(limit)} 的单文件上传，这是国区特有限制，外区账号不受此限制。"
    }

    suspend fun executeUpload(uploadId: Long): Result<String> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it) }

        val upload = database.driveUploadDao().getById(uploadId)
            ?: return@withContext Result.failure(Exception("上传任务 #$uploadId 不存在"))

        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            val err = sessionCheck.exceptionOrNull() ?: Exception("会话建立失败")
            uploadQueue.markFailed(uploadId, err.message ?: "会话建立失败")
            return@withContext Result.failure(err)
        }

        val wipeErr = wipeBlocked()
        if (wipeErr != null) {
            return@withContext Result.failure(wipeErr)
        }

        // Reject a file the account's region can never upload BEFORE spending minutes hashing it.
        //
        // 云上贵州 accounts cap a single POST at 1 GiB (ISS-057) — measured, and confirmed against
        // three accounts. Without this check the user waits through a full SHA-256 pass and a
        // partial transfer only to get a 413, which is the exact complaint that started this
        // investigation. International accounts report a limit of 0 and are never blocked.
        checkRegionUploadLimit(upload.filesize)?.let { reason ->
            uploadQueue.markFailed(uploadId, reason)
            Log.w(TAG, "Upload #$uploadId rejected before transfer: $reason")
            runCatching { File(upload.localPath).delete() }
            return@withContext Result.failure(Exception(reason))
        }

        uploadQueue.markUploading(uploadId)

        // Session start, kept only for the completion message.
        val transferStartMs = System.currentTimeMillis()

        // --- Sliding-window speed tracking ------------------------------------------------
        //
        // The previous formula was `bytes / (now - phaseStartMs)` — a *cumulative average* since
        // the phase began. That is monotonically decreasing by construction, so a healthy transfer
        // looked like it was slowing down, and it is completely blind to a stall (a frozen counter
        // still yields a large "average").
        //
        // Observed on device: `1.6 MB/s (100%)` followed 30 s later by `652 KB/s (100%)` for the
        // same file — the same cumulative average, falling.
        //
        // The window below reports the rate over the last ~2 s, which is what a user expects from
        // a speed readout and which drops to `0 B/s` when bytes genuinely stop moving.
        var lastPhase = ""
        var winStartMs = System.currentTimeMillis()
        var winStartRead = 0L
        var lastRead = 0L

        val pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(250)
                val pObj: JSONObject = runCatching {
                    JSONObject(Icloudcore.getDriveUploadProgress(upload.localPath))
                }.getOrNull() ?: continue

                val read = pObj.optLong("bytesUploaded", 0L)
                // Go reports the authoritative total for the phase. Trust it over the `filesize`
                // captured at enqueue time: that value can be 0 when the picker's cursor had no
                // SIZE column, in which case the poller divided by a stale fallback and pinned the
                // bar at 100%.
                val reportedTotal = pObj.optLong("totalBytes", 0L)
                val total = if (reportedTotal > 0L) reportedTotal else upload.filesize
                val phase = pObj.optString("phase", "UPLOAD")
                val now = System.currentTimeMillis()

                if (phase != lastPhase) {
                    lastPhase = phase
                    winStartMs = now
                    winStartRead = read
                    lastRead = read
                }

                if (phase == "HASH" || phase == "COMMIT") {
                    val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else 0
                    val speedText = if (phase == "HASH") "校验中 $pct%" else "正在写入云盘..."
                    uploadQueue.updateProgressAndSpeed(uploadId, read, speedText)
                    continue
                }

                // Restart the window every ~2 s, or immediately if the counter went backwards
                // (a retried upload restarts from byte 0 — a negative delta must not produce a
                // negative speed).
                val winMs = now - winStartMs
                if (winMs >= SPEED_WINDOW_MS || read < lastRead) {
                    winStartMs = now
                    winStartRead = read
                }
                lastRead = read

                val windowSec = ((now - winStartMs) / 1000.0).coerceAtLeast(0.25)
                val bps = ((read - winStartRead) / windowSec).toLong().coerceAtLeast(0L)
                val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else 0

                // Once every byte is in, stop quoting a rate. A 0-length delta window makes any
                // computed speed meaningless, and "652 KB/s (100%)" was being read by the user as
                // "still transferring" while the commit round trip ran.
                val speedText = if (total > 0 && read >= total) {
                    "正在写入云盘..."
                } else {
                    "${formatSpeed(bps)} ($pct%)"
                }

                uploadQueue.updateProgressAndSpeed(uploadId, read, speedText)
            }
        }

        try {
            var jsonResult = Icloudcore.uploadDriveItemByPath(
                upload.localPath,
                upload.filename,
                upload.mimeType,
                upload.targetFolderPath
            )

            var obj = JSONObject(jsonResult)
            if (!obj.optBoolean("success", false) && isAuthError(jsonResult)) {
                Log.w(TAG, "executeUpload got auth error, forcing session rebuild…")
                val rebuildCheck = ensureGoSession(force = true)
                if (rebuildCheck.isSuccess) {
                    jsonResult = Icloudcore.uploadDriveItemByPath(
                        upload.localPath,
                        upload.filename,
                        upload.mimeType,
                        upload.targetFolderPath
                    )
                    obj = JSONObject(jsonResult)
                }
            }

            val uploadElapsedSec = ((System.currentTimeMillis() - transferStartMs) / 1000.0).coerceAtLeast(0.001)
            val speedText = formatSpeed((upload.filesize / uploadElapsedSec).toLong())

            if (obj.optBoolean("success", false)) {
                val docId = obj.optString("documentId", "")
                val drivewsid = obj.optString("drivewsId", "").ifBlank {
                    if (docId.isNotBlank()) "FILE::$docId" else "FILE::${upload.targetFolderDrivewsId}::$uploadId"
                }

                // Upsert the newly uploaded file locally so it shows immediately
                // even if server-side index refresh has a slight delay
                val now = System.currentTimeMillis()
                val newEntity = DriveItemEntity(
                    drivewsid = drivewsid,
                    docwsid = docId,
                    parentDrivewsid = upload.targetFolderDrivewsId,
                    name = upload.filename,
                    type = DriveItemEntity.TYPE_FILE,
                    zone = DriveItemEntity.CLOUD_DOCS_ZONE,
                    size = upload.filesize,
                    dateModified = null,
                    dateChanged = null,
                    dateCreated = null,
                    etag = null,
                    extension = upload.filename.substringAfterLast('.', "").takeIf { it.isNotBlank() },
                    directChildrenCount = 0,
                    itemId = docId,
                    iconUrl = null,
                    childrenFetched = false,
                    lastSyncedAt = now
                )
                dataMutationLock.withLock {
                    dao.upsert(newEntity)
                }

                uploadQueue.updateProgressAndSpeed(uploadId, upload.filesize, speedText)
                uploadQueue.markSuccess(uploadId, docId)
                Log.i(TAG, "Upload #$uploadId succeeded for ${upload.filename}, documentId=$docId, speed=$speedText")

                // Clean up cached upload copy
                runCatching { File(upload.localPath).delete() }

                // Trigger folder refresh to update current directory listing
                refreshFolder(upload.targetFolderDrivewsId)

                Result.success(docId)
            } else {
                val rawErrMsg = obj.optString("errorMessage", "文件上传失败")

                // Always log the server's verbatim message before rewriting it.
                //
                // The friendly-text branch below replaces the payload with a Chinese string,
                // which is exactly how the real cause of a large-file rejection became
                // undiagnosable: the log said "文件过大 … 服务端拒绝了本次上传" with no
                // indication of WHICH endpoint refused or with what code.
                Log.w(TAG, "Upload #$uploadId failed raw: status=${obj.optInt("httpStatus", 0)} " +
                    "code=${obj.optString("errorCode", "?")} size=${upload.filesize} msg=$rawErrMsg")

                val displayErr = when {
                    // CloudDocs rejects a second file with the same name in the same folder
                    // with 409 UNIQUE_FIELD_FAILURE.
                    rawErrMsg.contains("UNIQUE_FIELD_FAILURE") -> "同名文件已存在：${upload.filename}"

                    // 413 is a REGION limit, not an app limit (ISS-057).
                    //
                    // Captured from three accounts with the SAME 1.96 GB file: both
                    // icloud.com.cn (云上贵州) accounts returned 413 while the icloud.com
                    // account uploaded it in a single POST. The requests were byte-for-byte
                    // the same protocol, so this cannot be fixed client-side — and it must
                    // not be reported as a generic failure, because the user would reasonably
                    // blame the app rather than their account region.
                    rawErrMsg.contains("413") || rawErrMsg.contains("Too Large") ->
                        "文件过大 (${formatSize(upload.filesize)})。" +
                            "当前账号（云上贵州区）不支持超过 1 GB 的单文件上传，这是账号区域限制。"

                    else -> rawErrMsg
                }
                uploadQueue.markFailed(uploadId, displayErr)
                        Log.w(TAG, "Upload #$uploadId failed for ${upload.filename}: $displayErr")

                        // Keep the cached copy while the failed task remains in the queue so the user
                        // can click Retry. When the user dismisses/cancels the item, `cancelUpload`
                        // deletes the file. `sweepOrphanedUploadCache` prunes old orphans on startup.
                        Result.failure(Exception(displayErr))
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // WorkManager cancels the worker when the OS reclaims it or when the unique work
                    // is replaced. The Go call is a blocking JNI call, so it does NOT stop when the
                    // coroutine is cancelled — it keeps streaming until it returns or the socket
                    // dies, and we lose the result.
                    //
                    // Leaving the row in UPLOADING is the failure mode that caused #21 to be
                    // uploaded twice: the next drain pass called resetUploadingToQueued(), saw it as
                    // an orphaned in-flight row, and restarted it from byte 0. Park it back to QUEUED
                    // explicitly so that the restart decision is at least recorded and visible.
                    Log.w(TAG, "executeUpload #$uploadId cancelled mid-flight; returning it to QUEUED")
                    runCatching { uploadQueue.markQueued(uploadId) }
                    throw ce
                } finally {
                    pollJob.cancel()

                    // Drop the Go-side progress entry. `uploadDriveItemFromFile` clears it from a
                    // `defer`, but that only runs when the JNI call actually returns: a process kill
                    // or a coroutine cancelled while the blocking call is in flight leaves the entry
                    // behind. `GetDriveUploadProgress`'s basename fallback would then hand that
                    // abandoned counter to the NEXT upload, and the banner showed a stalled
                    // percentage for a file that had not moved a byte.
                    runCatching { Icloudcore.clearDriveUploadProgress(upload.localPath) }
                }
    }

    // ----------------------------------------------------------------------------------------
    // D6 — Directory operations (Create, Rename, Move, Delete/Trash)
    // ----------------------------------------------------------------------------------------

    /**
     * Creates a new folder under [parentDrivewsId] with the given [folderName].
     */
    suspend fun createFolder(parentDrivewsId: String, folderName: String): Result<DriveItemEntity> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it) }

        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        val jsonStr = Icloudcore.createDriveFolder(parentDrivewsId, folderName)
        val obj = JSONObject(jsonStr)
        if (!obj.optBoolean("success", false)) {
            val errMsg = obj.optString("errorMessage", "新建文件夹失败")
            return@withContext Result.failure(Exception(errMsg))
        }

        val itemsArr = obj.optJSONArray("items")
        if (itemsArr == null || itemsArr.length() == 0) {
            return@withContext Result.failure(Exception("服务端未返回新文件夹信息"))
        }

        val firstObj = itemsArr.getJSONObject(0)
        val entity = DriveItemMapper.parseItem(firstObj, parentDrivewsId)
            ?: return@withContext Result.failure(Exception("解析新文件夹属性失败"))

        dataMutationLock.withLock {
            dao.upsert(entity)
        }

        refreshFolder(parentDrivewsId)
        Result.success(entity)
    }

    /**
     * Renames a file or folder identified by [drivewsid] to [newName].
     */
    suspend fun renameItem(drivewsid: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it) }

        val item = dao.getById(drivewsid) ?: return@withContext Result.failure(Exception("找不到指定的项目"))

        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        var jsonStr = Icloudcore.renameDriveItem(drivewsid, item.etag.orEmpty(), newName)
        var obj = JSONObject(jsonStr)

        if (!obj.optBoolean("success", false) && isAuthError(obj.optString("errorCode", ""))) {
            Log.w(TAG, "renameItem got auth error, forcing session rebuild…")
            ensureGoSession(force = true)
            jsonStr = Icloudcore.renameDriveItem(drivewsid, item.etag.orEmpty(), newName)
            obj = JSONObject(jsonStr)
        }

        if (!obj.optBoolean("success", false)) {
            val errMsg = obj.optString("errorMessage", "重命名失败")
            return@withContext Result.failure(Exception(errMsg))
        }

        val itemsArr = obj.optJSONArray("items")
        val newEtag = if (itemsArr != null && itemsArr.length() > 0) {
            itemsArr.getJSONObject(0).optString("etag", item.etag)
        } else item.etag

        dataMutationLock.withLock {
            dao.updateNameAndEtag(drivewsid, newName, newEtag)
        }
        refreshFolder(item.parentDrivewsid)

        Result.success(Unit)
    }

    /**
     * Moves a set of [items] into the folder [targetDrivewsId].
     */
    suspend fun moveItems(items: List<DriveItemEntity>, targetDrivewsId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success(Unit)
        wipeBlocked()?.let { return@withContext Result.failure(it) }

        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        val reqArr = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("drivewsid", item.drivewsid)
            o.put("etag", item.etag.orEmpty())
            reqArr.put(o)
        }

        var jsonStr = Icloudcore.moveDriveItems(targetDrivewsId, reqArr.toString())
        var obj = JSONObject(jsonStr)

        if (!obj.optBoolean("success", false) && isAuthError(obj.optString("errorCode", ""))) {
            Log.w(TAG, "moveItems got auth error, forcing session rebuild…")
            ensureGoSession(force = true)
            jsonStr = Icloudcore.moveDriveItems(targetDrivewsId, reqArr.toString())
            obj = JSONObject(jsonStr)
        }

        if (!obj.optBoolean("success", false)) {
            val errMsg = obj.optString("errorMessage", "移动失败")
            return@withContext Result.failure(Exception(errMsg))
        }

        val sourceParents = items.map { it.parentDrivewsid }.distinct()
        dataMutationLock.withLock {
            items.forEach { item ->
                dao.updateParentAndEtag(item.drivewsid, targetDrivewsId, item.etag)
            }
        }

        sourceParents.forEach { refreshFolder(it) }
        refreshFolder(targetDrivewsId)

        Result.success(Unit)
    }

    /**
     * Moves a set of [items] to Trash.
     */
    suspend fun deleteItems(items: List<DriveItemEntity>): Result<Unit> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success(Unit)
        wipeBlocked()?.let { return@withContext Result.failure(it) }

        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        val reqArr = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("drivewsid", item.drivewsid)
            o.put("etag", item.etag.orEmpty())
            reqArr.put(o)
        }

        var jsonStr = Icloudcore.moveDriveItemsToTrash(reqArr.toString())
        var obj = JSONObject(jsonStr)

        if (!obj.optBoolean("success", false) && isAuthError(obj.optString("errorCode", ""))) {
            Log.w(TAG, "deleteItems got auth error, forcing session rebuild…")
            ensureGoSession(force = true)
            jsonStr = Icloudcore.moveDriveItemsToTrash(reqArr.toString())
            obj = JSONObject(jsonStr)
        }

        if (!obj.optBoolean("success", false)) {
            val errMsg = obj.optString("errorMessage", "移入最近删除失败")
            return@withContext Result.failure(Exception(errMsg))
        }

        val ids = items.map { it.drivewsid }
        val sourceParents = items.map { it.parentDrivewsid }.distinct()

        dataMutationLock.withLock {
            dao.deleteByIds(ids)
        }
        sourceParents.forEach { refreshFolder(it) }

        Result.success(Unit)
    }

    /**
     * Returns all cached containers for the move target picker.
     */
    suspend fun getAllContainers(): List<DriveItemEntity> = withContext(Dispatchers.IO) {
        dao.getAllContainers()
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
    private val downloadQueue = DriveDownloadQueue()
    val downloadQueueTasks: StateFlow<Map<String, DriveDownloadTaskState>> = downloadQueue.tasks

    /** Enqueues a file for background download via [DriveDownloadQueue]. */
    suspend fun enqueueDownload(drivewsid: String) {
        val item = dao.getById(drivewsid) ?: return
        if (item.type != DriveItemEntity.TYPE_FILE) return
        downloadQueue.enqueue(item) { taskItem, onProgress ->
            downloadFile(taskItem.drivewsid, onProgress)
        }
    }

    /** Cancels an active or queued download task. */
    suspend fun cancelDownload(drivewsid: String) {
        downloadQueue.cancel(drivewsid)
    }

    /**
     * Checks if a file has already been downloaded to the local Downloads directory.
     *
     * Returns the [DriveDownloadOutcome] with uri if found, allowing instant local opening.
     */
    suspend fun checkDownloadedFile(item: DriveItemEntity): DriveDownloadOutcome? = withContext(Dispatchers.IO) {
        if (item.type != DriveItemEntity.TYPE_FILE) return@withContext null
        val fileName = DriveDownloader.sanitizeFileName(item.name, item.extension)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AClouDroid"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(fileName, "$relativePath/")
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)

            try {
                appContext.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        val id = cursor.getLong(idIndex)
                        val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                        val contentUri = ContentUris.withAppendedId(collection, id)
                        return@withContext DriveDownloadOutcome(
                            uri = contentUri,
                            displayLocation = "下载/AClouDroid",
                            bytes = size
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "checkDownloadedFile query failed: ${e.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AClouDroid")
            val file = File(dir, fileName)
            if (file.exists() && file.length() > 0) {
                return@withContext DriveDownloadOutcome(
                    uri = Uri.fromFile(file),
                    displayLocation = "下载/AClouDroid",
                    bytes = file.length()
                )
            }
        }
        null
    }

    private val thumbnailUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val thumbnailSemaphore = kotlinx.coroutines.sync.Semaphore(4)

    /**
     * Bridge for D7: mints signed thumbnail URL for file preview, caching in memory.
     */
    suspend fun fetchThumbnailUrl(drivewsid: String, filesize: Long = 0L): Result<String> = withContext(Dispatchers.IO) {
        thumbnailUrlCache[drivewsid]?.let { return@withContext Result.success(it) }

        ensureGoSession().onFailure { return@withContext Result.failure(it) }
        runCatching {
            val jsonStr = Icloudcore.retrieveDownloadURL(drivewsid)
            Log.d(TAG, "retrieveDownloadURL for $drivewsid -> $jsonStr")
            val obj = JSONObject(jsonStr)
            if (!obj.optBoolean("success", false)) {
                throw Exception(obj.optString("errorMessage", "获取缩略图地址失败"))
            }
            val tokenObj = obj.optJSONObject("token")
            val thumbUrl = tokenObj?.optString("thumbnailUrl").orEmpty()
            val fullUrl = tokenObj?.optString("url").orEmpty()
            val selectedUrl = when {
                thumbUrl.isNotBlank() -> thumbUrl
                filesize in 1..35_000_000L -> fullUrl
                filesize == 0L -> fullUrl
                else -> ""
            }
            if (selectedUrl.isBlank()) throw Exception("缺少缩略图地址 (filesize=$filesize, token=$tokenObj)")
            thumbnailUrlCache[drivewsid] = selectedUrl
            selectedUrl
        }
    }

    suspend fun getOrFetchThumbnailUrl(drivewsid: String, filesize: Long = 0L): String? {
        return fetchThumbnailUrl(drivewsid, filesize).getOrNull()
    }

    /**
     * Fetches and caches a media file's thumbnail image to `cacheDir/drive_thumbs/<hash>.jpg`.
     */
    suspend fun getOrFetchThumbnailFile(item: DriveItemEntity): File? = withContext(Dispatchers.IO) {
        val ext = item.extension?.lowercase()?.trim() ?: ""
        if (!app.acloudroid.qwq.feature.drive.ui.isMediaDriveItem(ext)) return@withContext null

        val thumbDir = File(appContext.cacheDir, "drive_thumbs").apply { if (!exists()) mkdirs() }
        val targetFile = File(thumbDir, "${item.drivewsid.hashCode()}.jpg")

        if (targetFile.exists() && targetFile.length() > 0) {
            return@withContext targetFile
        }

        val previewFile = previewCache.findPreviewFile(item.drivewsid)
        if (previewFile != null && previewFile.length() > 0) {
            return@withContext previewFile
        }

        thumbnailSemaphore.withPermit {
            if (targetFile.exists() && targetFile.length() > 0) {
                return@withPermit targetFile
            }

            val sessionCheck = ensureGoSession()
            if (sessionCheck.isFailure) return@withPermit null

            val urlResult = fetchThumbnailUrl(item.drivewsid, item.size)
            val url = urlResult.getOrNull() ?: return@withPermit null

            val tempFile = File(thumbDir, "${item.drivewsid.hashCode()}.tmp")
            val resultJson = Icloudcore.downloadToFileWithID(url, tempFile.absolutePath, 0L, item.docwsid)
            val downloadObj = JSONObject(resultJson)
            if (downloadObj.optBoolean("success", false) && tempFile.exists() && tempFile.length() > 0) {
                if (tempFile.renameTo(targetFile) || (targetFile.exists() && targetFile.length() > 0)) {
                    targetFile
                } else {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                    targetFile
                }
            } else {
                tempFile.delete()
                null
            }
        }
    }

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

    /**
     * Downloads [item] to the sandbox temporary preview cache (`cache/drive_preview/`).
     *
     * Returns the cached [File] on success.
     */
    suspend fun fetchFileToPreviewCache(
        item: DriveItemEntity,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it) }
        val cached = previewCache.findPreviewFile(item.drivewsid)
        if (cached != null && cached.length() > 0) {
            return@withContext Result.success(cached)
        }
        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        val targetFile = previewCache.getPreviewFile(item.drivewsid, item.extension.orEmpty())
        val result = downloader.downloadToCacheFile(item, targetFile, onProgress)
        if (result.isSuccess) {
            previewCache.cleanOldPreviews()
        }
        result
    }

    /**
     * Resolves a shareable FileProvider [Uri] for [item].
     *
     * Prefers already downloaded file in Downloads; falls back to fetching to [previewCache].
     */
    suspend fun getShareableUri(item: DriveItemEntity, context: Context): Result<Uri> = withContext(Dispatchers.IO) {
        val outcome = checkDownloadedFile(item)
        if (outcome?.uri != null) {
            return@withContext Result.success(outcome.uri)
        }

        val fileResult = fetchFileToPreviewCache(item)
        fileResult.mapCatching { file ->
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "app.acloudroid.qwq.fileprovider",
                file
            )
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1L shl 20).toDouble())
            bytesPerSec >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / (1L shl 10).toDouble())
            bytesPerSec > 0 -> "$bytesPerSec B/s"
            else -> "准备中..."
        }
    }

    companion object {
        private const val TAG = "DriveRepository"

        /**
         * Sliding window for the transfer-speed readout.
         *
         * Long enough that per-chunk jitter does not make the number jump around, short enough
         * that a genuine stall shows up as `准备中...` within a couple of seconds instead of being
         * masked by the cumulative average it replaced.
         */
        private const val SPEED_WINDOW_MS = 2_000L

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
