package app.acloudroid.qwq.feature.photos.data

import android.content.Context
import android.util.Log
import app.acloudroid.qwq.BuildConfig
import app.acloudroid.qwq.data.lock.DataMutationLock
import app.acloudroid.qwq.data.session.SessionManager
import app.acloudroid.qwq.feature.photos.data.db.MonthCoverRow
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity
import app.acloudroid.qwq.feature.photos.data.db.PurgedIdDao
import app.acloudroid.qwq.feature.photos.data.db.PurgedIdEntity
import app.acloudroid.qwq.feature.photos.data.db.ScanSeenIdEntity
import app.acloudroid.qwq.feature.photos.data.db.ScanStateEntity
import app.acloudroid.qwq.feature.photos.data.db.SyncStateEntity
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageModel
import com.getup.icloudcore.Icloudcore
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

data class RefreshedUrls(
    val thumbUrl: String?,
    val medUrl: String?,
    val originalUrl: String?,
    val videoComplUrl: String?
)

/**
 * What a Go core failure actually means (P2-015).
 *
 * Names mirror the `errorCode` values emitted by `go-core/errors.go` so the mapping is a plain
 * name lookup. [NONE] means the response reported success.
 */
internal enum class CoreErrorKind {
    NONE,
    AUTH_EXPIRED,
    NOT_FOUND,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    PARSE,
    ZONE_MISSING,
    UNKNOWN
}

/**
 * A classified Go core response.
 *
 * @param kind what the failure means; drives retry-vs-rebuild decisions.
 * @param httpStatus raw HTTP status when Go reported one, otherwise 0.
 * @param message human-readable detail, already safe to log (no signed URLs).
 */
internal data class CoreError(
    val kind: CoreErrorKind,
    val httpStatus: Int,
    val message: String
)

/**
 * One raw page of the photo library, as consumed by `MonthScanEngine` (P2-016).
 *
 * [hasMore] is already collapsed to `false` when the cursor is blank, so callers never have to
 * guard against "hasMore but no way to continue".
 *
 * [trashedIds] carries assets CloudKit returned in this page while flagged `isDeleted` (still
 * inside the 30-day "Recently Deleted" window). They are deliberately absent from [entities]:
 * caching one would show a deleted photo, and *silently* dropping one would make a page that
 * contained it indistinguishable from a page that never did (ISS-031).
 */
internal data class ScanPage(
    val entities: List<PhotoAssetEntity>,
    val nextMarker: String,
    val hasMore: Boolean,
    val syncToken: String = "",
    val trashedIds: Set<String> = emptySet()
)

/**
 * A failed page fetch, carrying the raw JSON so the caller can classify it.
 *
 * The scan needs the *kind* of failure, not just the fact of one: a 429 must be retried against
 * the same cursor, while a 401 means the cursor is dead and the walk must restart (ISS-020).
 */
internal class CoreFetchFailure(
    val rawJson: String,
    val kind: CoreErrorKind,
    cause: Throwable? = null
) : Exception(cause?.message ?: "core fetch failed", cause)

/**
 * Matches an explicit HTTP status token such as `HTTP 401`, `status:421`, `StatusCode=401`.
 * The `\b` word boundary prevents accidental hits inside longer digit/base64 runs.
 */
private val AUTH_STATUS_TOKEN_REGEX =
    Regex("""(?:HTTP|status|StatusCode)\s*[:=]?\s*(?:401|421)\b""", RegexOption.IGNORE_CASE)

/**
 * Classifies a Go core response JSON into a [CoreError] (P2-015).
 *
 * Pure and free of Android/Room/Go dependencies so it can be unit tested directly. This is the
 * single place that decides what a failure *means*, which matters far more once the month scan
 * issues a long chain of `/records/query` calls: a transient timeout or a 429 must be retried
 * with backoff, **not** mistaken for an expired session (a rebuild costs a `/validate` round trip
 * and re-signs every cached URL).
 *
 * Order of preference:
 *  1. `errorCode` from Go — a closed set, never invented locally.
 *  2. `httpStatus` — used when the errorCode is absent (e.g. an older AAR).
 *  3. `errorMessage` regex — last-resort compatibility shim.
 *
 * Any parse failure returns [CoreErrorKind.UNKNOWN] rather than throwing: failing open is what
 * keeps a malformed response from ever triggering an expensive rebuild.
 *
 * History this guards: ISS-013 / ISS-015 (a `jsonStr.contains("401")` check matched base64
 * `continuationMarker` payloads), ISS-020 (a dead cursor must be dropped, but only on a confirmed
 * 401).
 */
internal fun classifyCoreResponse(jsonStr: String): CoreError {
    if (jsonStr.isBlank()) {
        return CoreError(CoreErrorKind.UNKNOWN, 0, "Empty response")
    }

    return try {
        val json = JSONObject(jsonStr)
        val success = json.optBoolean("success", false)
        val errorCode = json.optString("errorCode", "")
        val httpStatus = json.optInt("httpStatus", 0)
        val message = json.optString("errorMessage", "")

        if (success) {
            return CoreError(CoreErrorKind.NONE, httpStatus, "")
        }

        // 1. Structured error code straight from the Go core.
        if (errorCode.isNotBlank()) {
            val kind = CoreErrorKind.entries.firstOrNull { it.name == errorCode }
                ?: CoreErrorKind.UNKNOWN
            return CoreError(kind, httpStatus, message)
        }

        // 2. Raw status code, for responses produced by an older AAR.
        when (httpStatus) {
            401, 421 -> return CoreError(CoreErrorKind.AUTH_EXPIRED, httpStatus, message)
            404 -> return CoreError(CoreErrorKind.NOT_FOUND, httpStatus, message)
            429 -> return CoreError(CoreErrorKind.RATE_LIMITED, httpStatus, message)
        }

        // 3. Last-resort text match. Only ever applied to `errorMessage` — never the whole
        // document — and only with a word boundary, so base64 payloads cannot hit it.
        if (message.isBlank()) {
            return CoreError(CoreErrorKind.UNKNOWN, httpStatus, "")
        }
        if (message.contains("AUTH_EXPIRED") ||
            message.contains("No active session") ||
            message.contains("CloudKit database URL is not set") ||
            AUTH_STATUS_TOKEN_REGEX.containsMatchIn(message)
        ) {
            return CoreError(CoreErrorKind.AUTH_EXPIRED, httpStatus, message)
        }
        if (message.contains("PrimarySync zone not found")) {
            return CoreError(CoreErrorKind.ZONE_MISSING, httpStatus, message)
        }

        CoreError(CoreErrorKind.UNKNOWN, httpStatus, message)
    } catch (e: Exception) {
        CoreError(CoreErrorKind.UNKNOWN, 0, e.message ?: "")
    }
}

/**
 * Parses a `k1=v1; k2=v2` cookie header into its unordered key/value pairs (P2-019).
 *
 * Order-insensitive by construction, so serialisation jitter can never masquerade as a credential
 * change. Values may legitimately contain `=` (base64), hence the split is limited to the first
 * separator. Blank parts and RFC 2965 `$`-prefixed attributes are skipped.
 *
 * Top-level and `internal` rather than private to the repository, purely so it can be unit tested
 * without standing up Room or the Go core.
 */
internal fun parseCookiePairs(cookieStr: String?): Set<Pair<String, String>> {
    if (cookieStr.isNullOrBlank()) return emptySet()
    return cookieStr.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("$") }
        .mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) {
                null
            } else {
                part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
        }
        .toSet()
}

data class LocalUploadResult(
    val success: Boolean,
    val masterId: String,
    val assetId: String,
    val fileChecksum: String
)

class PhotoRepository(
    private val database: PhotoDatabase,
    private val sessionManager: SessionManager,
    /**
     * Read for [monthCoverEnabled] only.
     *
     * The repository — not the UI — has to consult this, because the two expensive halves of the
     * cover feature live here: the per-month `newestInMonth` lookup during a scan, and the
     * download pass afterwards. Hiding the images while still paying both costs would be the
     * worst of both outcomes (see AGENTS §10.0).
     */
    private val appSettings: app.acloudroid.qwq.data.settings.AppSettings,
    /**
     * Only used to build the Coil `ImageRequest` for [prefetchMonthCovers].
     *
     * Held as the application context, so this is not a leak and does not tie the repository to
     * any Activity.
     */
    private val appContext: Context,
    /**
     * The app-wide mutation lock (see [DataMutationLock]).
     *
     * Injected rather than owned because Drive's sync/upload workers write concurrently with
     * photos' reconcile walk. Two separate locks would each look correct and still let the two
     * features interleave — AGENTS §12 requires one shared lock.
     */
    private val dataMutationLock: DataMutationLock = DataMutationLock()
) {
    private val photoDao = database.photoDao()
    private val syncStateDao = database.syncStateDao()
    private val photoMonthIndexDao = database.photoMonthIndexDao()
    private val scanStateDao = database.scanStateDao()
    internal val scanSeenIdDao = database.scanSeenIdDao()
    private val purgedIdDao = database.purgedIdDao()
    private val sessionMutex = Mutex()

    // ----------------------------------------------------------------------------------------
    // URL refresh de-duplication (P2-008)
    // ----------------------------------------------------------------------------------------

    private val refreshMutex = Mutex()

    /** Dedicated scope for sharing in-flight refreshes across concurrent API + Coil callers. */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** masterId -> epoch millis of the last *successful* URL refresh. */
    private val recentRefreshes = mutableMapOf<String, Long>()

    /** masterId -> in-flight refresh, so N concurrent failures share a single /records/lookup. */
    private val inFlightRefreshes = mutableMapOf<String, Deferred<Result<RefreshedUrls>>>()

    // EXIF lookups get their own mutex/map rather than sharing the URL-refresh ones: the two are
    // unrelated requests and must be able to proceed independently.
    private val metadataMutex = Mutex()

    /** masterId -> in-flight metadata lookup, so tapping "info" twice sends one request. */
    private val inFlightMetadata = mutableMapOf<String, Deferred<Result<String>>>()

    /** Timestamp of the last session rebuild (used to join the "everything just expired" storm). */
    @Volatile
    private var sessionUrlRefreshedAt: Long = 0L

    /**
     * Serialises every operation that mutates [photo_assets] outside of a single upsert.
     *
     * Today that is the full reconciliation walk, the incremental sync and the "Recently Deleted"
     * sweep. They all read-then-delete, so letting two of them interleave would let one delete rows
     * the other just proved alive. The same lock is what a future user-initiated delete must take
     * (see the delete note below).
     *
     * Now the *shared* [DataMutationLock] rather than a private `Mutex`: Drive writes to its own
     * database but contends for the same session and the same account, so it has to wait behind
     * a photo reconcile — and vice versa.
     */
    private val dataMutationMutex: DataMutationLock get() = dataMutationLock

    /**
     * Assets CloudKit has confirmed are gone, remembered across restarts.
     *
     * `/records/changes` does **not** report a soft-deleted (trashed) asset as a tombstone: the
     * record still exists, it is merely flagged `isDeleted`. So a photo deleted on another device
     * can be re-delivered as an ordinary *create* whenever the change feed is replayed from a
     * `syncToken` older than the deletion. Vetoing such upserts is what stops a purged photo from
     * reappearing.
     *
     * Persisted (not an in-memory set) because the replay usually happens on a cold start, which is
     * exactly when process memory has been thrown away. See [PurgedIdEntity] for the expiry rule
     * that keeps a genuine "restore from Recently Deleted" from being vetoed forever.
     */
    @Volatile
    private var purgedCache: Set<String> = emptySet()

    private suspend fun refreshPurgedCache() {
        val notBefore = System.currentTimeMillis() - PurgedIdDao.RETENTION_MS
        purgedCache = runCatching { purgedIdDao.getActiveIds(notBefore).toSet() }
            .getOrDefault(emptySet())
    }

    /** True when CloudKit has recently confirmed this id is no longer part of the gallery. */
    internal fun isPurged(id: String): Boolean = id in purgedCache

    suspend fun rememberPurged(ids: Collection<String>, source: String = "purge") {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = ids.distinct().map { PurgedIdEntity(id = it, purgedAt = now, source = source) }
        runCatching {
            purgedIdDao.upsertAll(rows)
            purgedIdDao.deleteOlderThan(now - PurgedIdDao.RETENTION_MS)
            val total = purgedIdDao.count()
            val overflow = total - PurgedIdDao.MAX_ROWS
            if (overflow > 0) {
                purgedIdDao.deleteOldest(overflow)
            }
            refreshPurgedCache()
        }.onFailure { Log.w(TAG, "rememberPurged failed: ${it.message}") }
    }

    val totalCountFlow: Flow<Int> = photoDao.getCount()

    /**
     * Month sections for the gallery (P2-016).
     *
     * Replaces the old `photoPagingFlow`. Paging 3 is gone entirely: `RemoteMediator` invalidated
     * its `PagingSource` after every APPEND, which collapsed `itemCount` to `initialLoadSize` and
     * clamped the scroll position — the jump-to-top of ISS-021. There is no tunable for that; the
     * only fix is to stop paging.
     */
    val monthIndexFlow: Flow<List<PhotoMonthIndexEntity>> = photoMonthIndexDao.observeAll()

    /** Photos of a single month, newest first. Subscribed per visible month by the UI. */
    fun observeMonth(yearMonth: Int): Flow<List<PhotoAssetEntity>> =
        photoDao.observeMonth(yearMonth)

    /**
     * Recomputes the index rows for the given months from the photo table.
     *
     * Room cannot express `INSERT ... SELECT` with aggregates, so this is a read-then-upsert per
     * month. Only called for months that were actually touched, so it stays cheap.
     *
     * ## Why the cover backfill lives here
     *
     * Every write path — the full walk, incremental sync, the periodic worker, a manual refresh —
     * ends up in this function. Putting the cover selection here therefore covers all of them
     * without touching a single line of scan scheduling, and it costs one extra indexed query per
     * *month* rather than per photo (a 15k-item library is ~200 months, not 15k queries).
     */
    private suspend fun rebuildMonthIndexFor(yearMonths: List<Int>) {
        val coversWanted = appSettings.monthCoverEnabled.value
        for (ym in yearMonths) {
            if (ym <= 0) continue
            val aggregate = photoDao.monthAggregate(ym) ?: continue
            if (aggregate.itemCount <= 0) {
                photoMonthIndexDao.deleteMonth(ym)
            } else {
                // Skipped entirely when the user has cover previews off: the column stays null and
                // the jump card renders text cells, with no lookup and no download.
                val cover = if (coversWanted) photoDao.newestInMonth(ym)?.id else null
                photoMonthIndexDao.upsert(
                    PhotoMonthIndexEntity(
                        yearMonth = ym,
                        itemCount = aggregate.itemCount,
                        firstAssetDate = aggregate.firstAssetDate ?: 0L,
                        lastAssetDate = aggregate.lastAssetDate ?: 0L,
                        isComplete = false,
                        coverAssetId = cover
                    )
                )
            }
        }
    }

    /**
     * Month rows for [year], each joined with its cover asset, newest month first.
     *
     * Subscribed by the gallery's jump card only while it is open, so a closed card costs nothing.
     */
    fun observeMonthCovers(year: Int): Flow<List<MonthCoverRow>> =
        if (year <= 0) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            photoMonthIndexDao.observeCoversInRange(
                fromYearMonth = year * 100 + 1,
                toYearMonth = year * 100 + 12
            )
        }

    /**
     * Downloads the cover thumbnails for [yearMonths] so the jump card opens pre-populated.
     *
     * ## Cost control
     *
     * A full walk touches ~200 months. Firing 200 CDN requests at once is exactly the burst
     * AGENTS §6.2 warns about, so the months are processed in bounded shards, and each shard's
     * URLs are re-minted with ONE batched `/records/lookup` rather than one per image.
     *
     * Downloads are sequential within a shard on purpose: the whole point is to fill a cache that
     * nobody is waiting on, so there is no reason to compete with foreground thumbnail loads for
     * the loader's bandwidth.
     *
     * ## Failure policy
     *
     * Every failure is swallowed and logged at debug level. A missing cover degrades to a text cell
     * in the card; it must never fail a scan or block the walk's completion.
     *
     * @return how many covers were successfully brought into the Coil cache.
     */
    suspend fun prefetchMonthCovers(yearMonths: List<Int>): Int = withContext(Dispatchers.IO) {
        if (!appSettings.monthCoverEnabled.value) return@withContext 0

        val months = yearMonths.distinct().filter { it > 0 }
        if (months.isEmpty()) return@withContext 0

        // Reuse the app-scoped loader so covers land in the SAME cache the grid reads from.
        // Null means nothing has rendered a thumbnail yet, so there is no cache to warm.
        val loader = ICloudImageLoaderFactory.peek() ?: return@withContext 0
        var done = 0

        for (shard in months.chunked(COVER_PREFETCH_BATCH)) {
            // Re-check per shard: the user may flip the switch while a long walk is still running.
            if (!appSettings.monthCoverEnabled.value) return@withContext done

            val covers = shard.mapNotNull { ym ->
                runCatching { photoDao.newestInMonth(ym) }.getOrNull()
            }
            if (covers.isEmpty()) continue

            // One lookup for the whole shard. A failure here is tolerable: the rows may still
            // carry a live signature from when they were last synced.
            runCatching { refreshUrlsForMasters(covers.map { it.masterId }) }

            // Refresh first, THEN re-read: the lookup above may have just written fresh URLs, and
            // loading the stale entity would throw those away.
            for (asset in covers) {
                if (!appSettings.monthCoverEnabled.value) return@withContext done
                val fresh = runCatching { photoDao.getById(asset.id) }.getOrNull() ?: asset
                val ok = runCatching {
                    val request = coil.request.ImageRequest.Builder(appContext)
                        .data(ICloudImageModel.fromEntity(fresh, ICloudImageModel.Quality.THUMB))
                        .build()
                    loader.execute(request)
                }.onFailure {
                    Log.d(TAG, "cover prefetch failed ym=${fresh.yearMonth}: ${it.message}")
                }.getOrNull()
                if (ok is coil.request.SuccessResult) done++
            }
        }
        Log.i(TAG, "cover prefetch finished: $done/${months.size} months")
        done
    }

    /**
     * Session Guard: Verifies that Go core has an active session.
     * Rebuilds session transparently using stored cookies if Go core's session is missing or expired.
     */
    suspend fun ensureGoSession(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            try {
                if (!force) {
                    val summaryStr = Icloudcore.getSessionStateSummary()
                    val summaryJson = if (summaryStr.startsWith("{")) JSONObject(summaryStr) else null
                    val hasActiveSession = summaryJson != null &&
                        summaryJson.optBoolean("hasAuthToken", false) &&
                        summaryJson.optString("ckDatabaseURL", "").isNotBlank()

                    if (hasActiveSession) {
                        // NOTE: deliberately do NOT bump the generation here. This path runs on every
                        // API entry point; bumping would invalidate every stored URL dozens of times
                        // per second and force a `/records/lookup` per grid cell (P2-008 storm).
                        return@withContext Result.success(Unit)
                    }
                }

                Log.i(TAG, "Rebuilding Go core session (force=$force)...")
                val cookieStr = sessionManager.getCookieString()
                if (cookieStr.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("本地缺少 iCloud Cookie 凭证，请重新登录"))
                }

                val isChina = sessionManager.isChinaMainland()
                val jsonResult = Icloudcore.initSession(cookieStr, isChina)
                Log.d(TAG, "Rebuilt Go session result: $jsonResult")

                val json = JSONObject(jsonResult)
                val success = json.optBoolean("success", false)
                if (!success) {
                    val errMsg = json.optString("errorMessage", "会话初始化失败，请在“账号”页面重新登录")
                    return@withContext Result.failure(Exception(errMsg))
                }

                val dsid = json.optString("dsid", "")
                val ckUrl = json.optString("ckDatabaseUrl", "")

                // Detect an account switch that did NOT go through logout().
                //
                // `wipeAllData()` clears the photo cache on sign-out (P2-018), but logging in as a
                // *different* Apple ID without signing out first bypasses that entirely: the
                // local rows, the sync cursor and — critically — the month-scan cursor still
                // belong to the previous account. The previous state was catastrophic in a subtle
                // way: `scan_state.isComplete` stayed 1, so the new account's scan either refused
                // to run or ran without bucketing, and its photos sat at `yearMonth = 0` where the
                // gallery cannot see them, behind a count that looked plausible.
                val previousDsid = sessionManager.getDsid()
                if (dsid.isNotBlank() && previousDsid != null && previousDsid.isNotBlank() &&
                    previousDsid != dsid
                ) {
                    Log.w(TAG, "Account changed ($previousDsid -> $dsid) - discarding cached library")
                    runCatching { wipeAllData() }
                        .onFailure { Log.e(TAG, "Wipe on account change failed", it) }
                }

                if (dsid.isNotBlank() && ckUrl.isNotBlank()) {
                    sessionManager.updateRouteInfo(dsid, ckUrl)
                }

                // Persist whatever cookie material Go handed back. This does NOT bump the
                // session generation any more (P2-019).
                //
                // Measured on device (SM-F9760): a URL minted under generation N still returns
                // 206 after the session is fully rebuilt into generation N+1 — the CDN signature
                // is bound to the record, not to the session that handed it out. Meanwhile
                // Apple rotates `X-APPLE-WEBAUTH-TOKEN` and `X-APPLE-WEBAUTH-VALIDATE` on *every*
                // `/validate` handshake (3/3 trials), so keying the generation bump off cookie
                // values meant every genuine rebuild re-marked the whole library stale and fired
                // one `/records/lookup` per visible thumbnail. That is the real source of the
                // P2-008 refresh storm, not the ordering jitter alone.
                //
                // Generation is now bumped only by an explicit `force` rebuild or by the
                // 401/421 recovery paths (see [markSessionReady] callers), which is exactly the
                // "credentials are dead" signal. Expiry itself is handled by age, see
                // [ICloudPhotoInterceptor.URL_MAX_AGE_MS].
                applyRefreshedCookies(isChina, dsid, ckUrl)
                markSessionReady(bumpGeneration = force)

                // NOTE (P2-014): deliberately do NOT drop the continuationMarker here. This
                // method also runs on the plain cold-start path (force=false) where we are
                // merely re-attaching to a cached DB — the persisted cursor is still valid and
                // is our only way to keep paginating. It is dropped only on the auth-error
                // recovery path, see [invalidateContinuationCursor].

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "ensureGoSession error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Persists any cookie material Go handed back during the handshake.
     *
     * "Changed" means the actual **key/value pairs** differ — not that the serialised string
     * differs. Comparing raw strings was a bug (P2-019): Go iterated its `CookieMap` in
     * randomised order, so the same cookie set serialised to a different string on every call and
     * every cold start looked like a rotation (`Cookies rotated (len 2893 -> 2893)` in the logs).
     * The Go side now sorts its keys ([go-core/session.go] `BuildCookieHeader`); this set-based
     * check is the defence in depth against any future ordering jitter.
     *
     * IMPORTANT: the return value is **no longer used to bump the session generation**. It did
     * once, on the theory that rotated credentials invalidate every previously issued URL. That
     * theory was measured and disproved (see `.artifacts/url_lifetime_experiment.md`): a URL
     * minted under generation N still returns `206` after a rebuild into N+1, while Apple rotates
     * `X-APPLE-WEBAUTH-TOKEN` / `X-APPLE-WEBAUTH-VALIDATE` on *every* `/validate`. So the old
     * wiring bumped the generation on every genuine rebuild for no reason. The flag is kept for
     * logging/diagnostics only; URL expiry is handled by age in the Coil interceptor.
     *
     * @return true when the stored cookie set actually changed (diagnostics only).
     */
    private fun applyRefreshedCookies(isChina: Boolean, dsid: String, ckUrl: String): Boolean {
        if (!Icloudcore.hasCookieUpdates()) return false

        val updatedCookies = Icloudcore.getCurrentCookieString()
        if (updatedCookies.isNullOrBlank()) return false

        val oldCookies = sessionManager.getCookieString()
        val updatedPairs = parseCookiePairs(updatedCookies)
        val oldPairs = parseCookiePairs(oldCookies)
        if (updatedPairs == oldPairs) {
            // Same cookie material, merely re-serialised. Do NOT persist and do NOT count as a
            // rotation: the stored URLs are still signed by these very keys.
            Log.d(TAG, "Cookie updates reported but set is unchanged (order-only jitter)")
            return false
        }

        // Name which pairs changed. Cookie *values* are never logged (they are live credentials,
        // AGENTS §8.2) — only names and counts. `valueChanged` is what made P2-019 diagnosable:
        // it revealed that only the short-lived `VALIDATE` / `TOKEN` pair actually rotates on
        // every `/validate`, while the rest of the set is stable.
        if (BuildConfig.DEBUG) {
            val oldByName = oldPairs.toMap()
            val newByName = updatedPairs.toMap()
            Log.d(
                TAG,
                "Cookie diff: added=${newByName.keys - oldByName.keys} " +
                    "removed=${oldByName.keys - newByName.keys} " +
                    "valueChanged=${
                        oldByName.keys.intersect(newByName.keys)
                            .filter { oldByName[it] != newByName[it] }
                    }"
            )
        }

        Log.d(TAG, "Cookies rotated (pairs ${oldPairs.size} -> ${updatedPairs.size})")
        sessionManager.saveSession(updatedCookies, isChina, dsid, ckUrl)
        return true
    }



    /**
     * Records that a Go session is now active.
     *
     * @param bumpGeneration true only for an explicit `force` rebuild (auth-error recovery).
     *   Cookie-value changes during a normal handshake deliberately no longer bump: measurement
     *   showed a URL stays valid across a full session rebuild, and Apple rotates two cookies on
     *   every `/validate`, so the old cookie-driven bump fired on every rebuild and marked the
     *   whole cached library stale (`.artifacts/url_lifetime_experiment.md`).
     *
     *   The generation still serves the account-switch case: after a logout/login the rows are
     *   cleared anyway (P2-018), but any that survive carry a stale stamp that lets the Coil
     *   interceptor treat them as suspicious sooner.
     */
    private fun markSessionReady(bumpGeneration: Boolean) {
        if (bumpGeneration) {
            val next = sessionManager.bumpSessionGeneration()
            Log.i(TAG, "Session generation -> $next (URLs previously minted are now stale)")
        }
        sessionUrlRefreshedAt = System.currentTimeMillis()
    }

    /**
     * Drops the persisted `continuationMarker` (P2-014).
     *
     * The marker is a CloudKit cursor bound to the session that produced it. Once a fetch has
     * come back `401` and we have rebuilt the session, that cursor is known-dead: replaying it
     * just earns another `401`, another rebuild, and another dead cursor — the 17s / 4-rebuild
     * loop of P2-014.
     *
     * IMPORTANT: this must only be called on the **auth-error recovery path**, never from
     * [ensureGoSession] itself. `ensureGoSession(force = false)` also runs on plain cold start
     * while we are re-attaching to a cached DB, where the stored cursor is still perfectly
     * good — wiping it there silently stops all further pagination ("No more photos to fetch
     * via continuationMarker").
     *
     * After clearing, the caller should re-fetch with an empty marker. Results are keyed by
     * `id`, so the overlap de-duplicates on upsert.
     */
    private suspend fun invalidateContinuationCursor() {
        val state = runCatching { syncStateDao.getSyncState(KEY_PRIMARY_SYNC) }.getOrNull() ?: return
        if (state.continuationMarker.isNullOrBlank()) return
        Log.d(TAG, "Auth recovery - dropping dead continuationMarker")
        syncStateDao.setSyncState(state.copy(continuationMarker = null, hasMore = true))
    }

    /**
     * Structured auth-error detection.
     *
     * IMPORTANT: never do plain `jsonStr.contains("401")` on the raw response — CloudKit's
     * base64 `continuationMarker` / `syncToken` / record UUIDs routinely contain the literal
     * digits `401` / `421`, which used to make every other page look like an auth failure and
     * trigger a full session rebuild (see ISS-015 / P2-001).
     *
     * Kept as a thin wrapper over [classifyCoreResponse] so every call site reads the same way it
     * always has; the classification itself now comes from the Go core layer (P2-015).
     */
    private fun isAuthError(jsonStr: String): Boolean =
        classifyCoreResponse(jsonStr).kind == CoreErrorKind.AUTH_EXPIRED

    private fun parseHttpStatus(jsonStr: String): Int {
        return try {
            JSONObject(jsonStr).optInt("httpStatus", 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun parseErrorMessage(jsonStr: String): String {
        return try {
            JSONObject(jsonStr).optString("errorMessage", "")
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun checkZoneReady(): Result<Boolean> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it.exceptionOrNull()!!) }
        var sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        try {
            var jsonStr = Icloudcore.checkPrimaryZone()
            if (isAuthError(jsonStr)) {
                Log.w(TAG, "checkZoneReady returned auth error, forcing session refresh...")
                sessionCheck = ensureGoSession(force = true)
                if (sessionCheck.isFailure) {
                    return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话重建失败"))
                }
                jsonStr = Icloudcore.checkPrimaryZone()
            }

            val json = JSONObject(jsonStr)
            val success = json.optBoolean("success", false)
            val ready = json.optBoolean("ready", false)
            val syncToken = json.optString("syncToken", "")
            if (success && ready) {
                if (syncToken.isNotBlank()) {
                    val existing = syncStateDao.getSyncState(KEY_PRIMARY_SYNC)
                    if (existing == null || existing.syncToken.isNullOrBlank()) {
                        syncStateDao.setSyncState(
                            SyncStateEntity(
                                key = KEY_PRIMARY_SYNC,
                                syncToken = syncToken,
                                continuationMarker = null,
                                hasMore = true,
                                lastSyncTime = System.currentTimeMillis()
                            )
                        )
                    }
                }
                Result.success(true)
            } else {
                val err = json.optString("errorMessage", "PrimarySync 分区尚未就绪")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchInitialPage(limit: Int = 100): Result<Int> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it.exceptionOrNull()!!) }
        var sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        try {
            Log.d(TAG, "Fetching initial photo page (limit=$limit)...")
            var jsonStr = Icloudcore.fetchPhotoPage("", limit.toLong())
            if (isAuthError(jsonStr)) {
                Log.w(TAG, "fetchInitialPage returned auth error, forcing session refresh...")
                sessionCheck = ensureGoSession(force = true)
                if (sessionCheck.isFailure) {
                    return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话重建失败"))
                }
                jsonStr = Icloudcore.fetchPhotoPage("", limit.toLong())
            }

            val json = JSONObject(jsonStr)
            val success = json.optBoolean("success", false)

            if (!success) {
                val err = json.optString("errorMessage", "拉取首屏照片失败")
                return@withContext Result.failure(Exception(err))
            }

            val initialTrashed = mutableSetOf<String>()
            val photos = parsePhotoItems(json.optJSONArray("photos"), initialTrashed)
            if (initialTrashed.isNotEmpty()) purgeTrashed(initialTrashed)
            val marker = json.optString("continuationMarker", "")
            val hasMore = json.optBoolean("hasMore", false)
            val syncToken = json.optString("syncToken", "")

            Log.d(TAG, "Initial page fetched: ${photos.size} photos, hasMore=$hasMore")

            upsertPhotosWithMerge(photos)
            syncStateDao.setSyncState(
                SyncStateEntity(
                    key = KEY_PRIMARY_SYNC,
                    syncToken = syncToken.ifBlank { null },
                    continuationMarker = marker.ifBlank { null },
                    hasMore = hasMore,
                    lastSyncTime = System.currentTimeMillis()
                )
            )

            Result.success(photos.size)
        } catch (e: Exception) {
            Log.e(TAG, "fetchInitialPage error", e)
            Result.failure(e)
        }
    }

    suspend fun fetchNextPage(limit: Int = 100): Result<Int> = withContext(Dispatchers.IO) {
        wipeBlocked()?.let { return@withContext Result.failure(it.exceptionOrNull()!!) }
        var sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        try {
            val state = syncStateDao.getSyncState(KEY_PRIMARY_SYNC)
            val marker = state?.continuationMarker
            if (marker.isNullOrBlank() || state.hasMore == false) {
                Log.d(TAG, "No more photos to fetch via continuationMarker")
                return@withContext Result.success(0)
            }

            Log.d(TAG, "Fetching next photo page (limit=$limit)")
            var jsonStr = Icloudcore.fetchPhotoPage(marker, limit.toLong())
            if (isAuthError(jsonStr)) {
                Log.w(
                    TAG,
                    "fetchNextPage auth error (status=${parseHttpStatus(jsonStr)}), forcing session refresh. " +
                        "detail=${parseErrorMessage(jsonStr).take(200)}"
                )
                sessionCheck = ensureGoSession(force = true)
                if (sessionCheck.isFailure) {
                    return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话重建失败"))
                }
                // P2-014: `marker` belonged to the dead session and is now guaranteed invalid.
                // Replaying it is what produced the endless 401 -> rebuild -> 401 loop.
                // Drop the persisted cursor and restart from the live end; rows are keyed by
                // `id`, so the overlap de-duplicates on the upsert below.
                invalidateContinuationCursor()
                jsonStr = Icloudcore.fetchPhotoPage("", limit.toLong())
            }

            val json = JSONObject(jsonStr)
            val success = json.optBoolean("success", false)

            if (!success) {
                val err = json.optString("errorMessage", "分页拉取照片失败")
                return@withContext Result.failure(Exception(err))
            }

            val nextTrashed = mutableSetOf<String>()
            val photos = parsePhotoItems(json.optJSONArray("photos"), nextTrashed)
            if (nextTrashed.isNotEmpty()) purgeTrashed(nextTrashed)
            val nextMarker = json.optString("continuationMarker", "")
            val hasMore = json.optBoolean("hasMore", false)
            val syncToken = json.optString("syncToken", state.syncToken ?: "")

            Log.d(TAG, "Next page returned: ${photos.size} photos, hasMore=$hasMore")
            upsertPhotosWithMerge(photos)

            syncStateDao.setSyncState(
                SyncStateEntity(
                    key = KEY_PRIMARY_SYNC,
                    syncToken = syncToken.ifBlank { null },
                    continuationMarker = nextMarker.ifBlank { null },
                    hasMore = hasMore,
                    lastSyncTime = System.currentTimeMillis()
                )
            )

            Result.success(photos.size)
        } catch (e: Exception) {
            Log.e(TAG, "fetchNextPage error", e)
            Result.failure(e)
        }
    }

    /**
     * One raw page for the month scan (P2-016).
     *
     * Unlike [fetchInitialPage] / [fetchNextPage] this neither persists a cursor nor writes to
     * Room — the scan owns its own cursor (`scan_state`) and decides what a failure means, because
     * it must distinguish "back off and retry" from "the session is dead" (P2-015).
     *
     * Only **confirmed** auth failures trigger a rebuild here, and the caller is responsible for
     * dropping its cursor when that happens (ISS-020).
     */
    internal suspend fun fetchScanPage(
        marker: String,
        limit: Int
    ): Result<ScanPage> = withContext(Dispatchers.IO) {
        if (isWiping) {
            return@withContext Result.failure(Exception("正在退出登录，已暂停照片同步"))
        }

        val sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(
                sessionCheck.exceptionOrNull() ?: Exception("会话建立失败")
            )
        }

        try {
            val jsonStr = Icloudcore.fetchPhotoPage(marker, limit.toLong())
            val error = classifyCoreResponse(jsonStr)

            if (error.kind != CoreErrorKind.NONE) {
                Log.w(TAG, "fetchScanPage failed (${error.kind}, HTTP ${error.httpStatus})")
                return@withContext Result.failure(
                    CoreFetchFailure(jsonStr, error.kind, Exception(error.message))
                )
            }

            val json = JSONObject(jsonStr)
            val trashedIds = mutableSetOf<String>()
            val photos = parsePhotoItems(json.optJSONArray("photos"), trashedIds)
            val nextMarker = json.optString("continuationMarker", "")
            val hasMore = json.optBoolean("hasMore", false)
            val syncToken = json.optString("syncToken", "")

            // Purging happens in `recordScanPage`, under the mutation lock — doing it here would
            // delete rows from a background thread racing the walk.
            Result.success(
                ScanPage(
                    entities = photos,
                    nextMarker = nextMarker,
                    hasMore = hasMore && nextMarker.isNotBlank(),
                    syncToken = syncToken,
                    trashedIds = trashedIds
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchScanPage error", e)
            Result.failure(e)
        }
    }

    /**
     * Drops the local copies of assets CloudKit reported as trashed (`isDeleted = 1`).
     *
     * Shared by the scan and the incremental sync. Everything it removes is also remembered in
     * [recentlyPurgedIds], so a replay of an old `syncToken` cannot put the row back.
     *
     * @return how many identities were purged (0 when nothing was local to begin with).
     */
    private suspend fun purgeTrashed(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        rememberPurged(ids, source = "trashed")
        val affectedMonths = photoDao.getYearMonthsForIds(ids.toList())
        if (affectedMonths.isEmpty()) return 0
        Log.d(TAG, "Purging ${ids.size} trashed assets (months=$affectedMonths)")
        photoDao.deleteByIds(ids.toList())
        rebuildMonthIndexFor(affectedMonths)
        ICloudImageLoaderFactory.evictAssets(ids)
        return ids.size
    }

    /**
     * Applies an in-progress walk's page to the local cache (ISS-031).
     *
     * Two things happen here that plain [fetchScanPage] does not do:
     *
     *  1. Every live asset is recorded in `scan_seen_ids`, so the walk can later prove what still
     *     exists in the account.
     *  2. Assets CloudKit flagged `isDeleted` are purged immediately instead of being cached.
     *
     * Caller must already hold [dataMutationMutex] (the scan engine does).
     */
    internal suspend fun recordScanPage(page: ScanPage): Int {
        val now = System.currentTimeMillis()
        if (page.entities.isNotEmpty()) {
            upsertPhotosWithMerge(page.entities)
            scanSeenIdDao.upsertAll(
                page.entities.map { ScanSeenIdEntity(it.id, it.masterId, now) }
            )
            rebuildMonthIndexFor(page.entities.map { it.yearMonth }.distinct())
        }
        if (page.trashedIds.isNotEmpty()) {
            purgeTrashed(page.trashedIds)
        }
        return page.entities.size
    }

    /** Clears the walk's scratch table. Called before a new walk starts. */
    internal suspend fun resetSeenIds() {
        runCatching { scanSeenIdDao.clearAll() }
            .onFailure { Log.w(TAG, "resetSeenIds failed: ${it.message}") }
    }

    /**
     * Diff the cached library against everything the cloud still returned, and delete the extras.
     *
     * This is the only code path that can remove a photo the cloud deleted *and* expunged long
     * ago, because it is the only one that can positively prove absence.
     *
     * ### Safety gates (all must pass)
     *
     * 1. The caller guarantees the walk was declared finished by CloudKit. A partial walk would
     *    make almost every row look deleted.
     * 2. [Reconcile.isSafeToApply] — refuses a run whose seen-id count is implausibly small, which
     *    is what a server answering `200` with an empty page looks like.
     * 3. Conservative master rule — implemented in [Reconcile.decide].
     *
     * Deletion is limited to `photo_assets` (a cache). Files already written into the user's
     * gallery by `MediaStoreDownloader` are never touched.
     *
     * @return number of rows removed, or a failure describing which gate refused the run.
     */
    internal suspend fun reconcileWithSeenIds(): Result<Int> = dataMutationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (isWiping) {
                return@withContext Result.failure(Exception("正在退出登录，已暂停照片同步"))
            }

            val local = photoDao.getAllIdentities()
            if (local.isEmpty()) {
                scanSeenIdDao.clearAll()
                return@withContext Result.success(0)
            }

            val seenIds = scanSeenIdDao.getSeenIds().toSet()
            if (!Reconcile.isSafeToApply(localCount = local.size, seenIdCount = seenIds.size)) {
                Log.w(
                    TAG,
                    "reconcile aborted: seen too small (seen=${seenIds.size}, local=${local.size}) - " +
                        "refusing to delete on an implausible walk"
                )
                scanSeenIdDao.clearAll()
                return@withContext Result.failure(
                    Exception("对账中止：本轮仅看到 ${seenIds.size} 项，远少于本地 ${local.size} 项")
                )
            }

            val decision = Reconcile.decide(local, seenIds)
            Log.i(
                TAG,
                "reconcile: seen=${seenIds.size} local=${local.size} purged=${decision.toDelete.size} " +
                    "keptByMaster=${decision.keptByMaster} keptBySeen=${decision.keptBySeen}"
            )

            if (decision.toDelete.isEmpty()) {
                scanSeenIdDao.clearAll()
                return@withContext Result.success(0)
            }

            // Auditable before it happens: this run is the only place a live row can disappear.
            Log.w(
                TAG,
                "reconcile will purge ${decision.toDelete.size} cached rows that CloudKit no " +
                    "longer returns: ${decision.toDelete.take(PURGE_LOG_SAMPLE)}"
            )

            val affectedMonths = photoDao.getYearMonthsForIds(decision.toDelete)
            val removed = photoDao.deleteByAssetIds(decision.toDelete)
            if (affectedMonths.isNotEmpty()) {
                rebuildMonthIndexFor(affectedMonths)
            }
            ICloudImageLoaderFactory.evictAssets(decision.toDelete)
            rememberPurged(decision.toDelete, source = "reconcile")
            scanSeenIdDao.clearAll()

            Result.success(removed)
        }
    }

    /**
     * Pulls incremental changes on top of the already-scanned library.
     *
     * Serialised against the other mutation paths: it appends rows while the reconcile walk and the
     * expunge sweep delete them, and those two read-then-act.
     */
    suspend fun syncIncremental(limit: Int = 100): Result<Int> = dataMutationMutex.withLock {
        withContext(Dispatchers.IO) { syncIncrementalLocked(limit) }
    }

    private suspend fun syncIncrementalLocked(limit: Int): Result<Int> {
        wipeBlocked()?.let { return Result.failure(it.exceptionOrNull()!!) }
        var sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        try {
            val state = syncStateDao.getSyncState(KEY_PRIMARY_SYNC)
            var currentSyncToken = state?.syncToken ?: ""
            if (currentSyncToken.isBlank()) {
                Log.d(TAG, "No syncToken found, falling back to fetchInitialPage")
                return fetchInitialPage(limit)
            }

            Log.d(TAG, "Starting incremental sync with token: ${currentSyncToken.take(15)}...")
            var totalChanged = 0
            var pageCount = 0
            val maxPages = 10

            while (pageCount < maxPages) {
                pageCount++
                var jsonStr = Icloudcore.fetchChanges(currentSyncToken, limit.toLong())
                if (isAuthError(jsonStr)) {
                    Log.w(TAG, "syncIncremental returned auth error, forcing session refresh...")
                    sessionCheck = ensureGoSession(force = true)
                    if (sessionCheck.isFailure) {
                        return Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话重建失败"))
                    }
                    jsonStr = Icloudcore.fetchChanges(currentSyncToken, limit.toLong())
                }

                val json = JSONObject(jsonStr)
                val success = json.optBoolean("success", false)

                if (!success) {
                    val err = json.optString("errorMessage", "增量同步失败")
                    return Result.failure(Exception(err))
                }

                val trashedIds = mutableSetOf<String>()
                val newPhotos = parsePhotoItems(json.optJSONArray("photos"), trashedIds)
                val deletedIds = parseStringArray(json.optJSONArray("deletedIds"))
                val nextToken = json.optString("syncToken", currentSyncToken)
                val moreComing = json.optBoolean("moreComing", false)

                // Trashed records come back in the change feed as ordinary updates (the record
                // still exists, only `isDeleted` flipped). Treat them exactly like tombstones.
                val removedIds = (deletedIds + trashedIds).distinct()
                if (removedIds.isNotEmpty()) {
                    val affectedMonths = photoDao.getYearMonthsForIds(removedIds)
                    Log.d(TAG, "Deleting ${removedIds.size} removed assets from local db (affected months: $affectedMonths)")
                    photoDao.deleteByIds(removedIds)
                    if (affectedMonths.isNotEmpty()) {
                        rebuildMonthIndexFor(affectedMonths)
                    }
                    ICloudImageLoaderFactory.evictAssets(removedIds)
                    // Authoritative: never let the same record come back.
                    rememberPurged(removedIds, source = "changes")
                    totalChanged += removedIds.size
                }

                if (newPhotos.isNotEmpty()) {
                    // A change feed replayed from an older `syncToken` can re-deliver an asset whose
                    // CPLAsset still exists but carries `isDeleted = 1` ("Recently Deleted"). The
                    // record is not a tombstone, so it arrives as a normal create. We already know
                    // from the expunge sweep that it is gone — drop it instead of resurrecting it.
                    val resurrected = newPhotos.filter { isPurged(it.id) || isPurged(it.masterId) }
                    val upsertable = if (resurrected.isEmpty()) {
                        newPhotos
                    } else {
                        Log.d(
                            TAG,
                            "Skipping ${resurrected.size} change entries that are already purged: " +
                                resurrected.take(5).map { it.filename }
                        )
                        newPhotos - resurrected.toSet()
                    }

                    if (upsertable.isNotEmpty()) {
                        Log.d(TAG, "Upserting ${upsertable.size} new/modified photos to local db")
                        upsertPhotosWithMerge(upsertable)
                        // Keep the month sections in step, or the synced photos stay stranded at
                        // yearMonth = 0 and never appear in the gallery (P2-016).
                        rebuildMonthIndexFor(upsertable.map { it.yearMonth }.distinct())

                        // Protect newly synced records against reconciliation deletion if scan is in progress (ISS-031 / 修正 2)
                        val scanState = scanStateDao.get(ScanStateEntity.KEY_MONTH_SCAN)
                        if (scanState != null && !scanState.isComplete) {
                            val now = System.currentTimeMillis()
                            val seenEntities = upsertable.map {
                                ScanSeenIdEntity(id = it.id, masterId = it.masterId, scannedAt = now)
                            }
                            scanSeenIdDao.upsertAll(seenEntities)
                            Log.d(TAG, "Inserted ${seenEntities.size} newly synced items into scan_seen_ids for active scan")
                        }

                        totalChanged += upsertable.size
                    }
                }

                currentSyncToken = nextToken
                syncStateDao.setSyncState(
                    SyncStateEntity(
                        key = KEY_PRIMARY_SYNC,
                        syncToken = nextToken,
                        continuationMarker = state?.continuationMarker,
                        hasMore = state?.hasMore ?: false,
                        lastSyncTime = System.currentTimeMillis()
                    )
                )

                if (!moreComing) {
                    break
                }
            }

            return Result.success(totalChanged)
        } catch (e: Exception) {
            Log.e(TAG, "syncIncremental error", e)
            return Result.failure(e)
        }
    }

    /**
     * Purges assets currently in the cloud "Recently Deleted" album from local storage.
     *
     * Queries CloudKit's `CPLAssetAndMasterDeletedByExpungedDate` view, identifies matching local
     * assets, deletes them, refreshes affected month sections, and evicts Coil caches.
     *
     * Takes [dataMutationMutex] like the other mutation paths, because it reads month ids and then
     * deletes rows — interleaving with an incremental sync would let one of them act on stale data.
     */
    suspend fun purgeRecentlyDeleted(): Result<Int> = dataMutationMutex.withLock {
        withContext(Dispatchers.IO) {
            purgeRecentlyDeletedLocked()
        }
    }

    private suspend fun purgeRecentlyDeletedLocked(): Result<Int> {
        wipeBlocked()?.let { return Result.failure(it.exceptionOrNull()!!) }
        var sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        try {
            var jsonStr = Icloudcore.fetchRecentlyDeleted()
            if (isAuthError(jsonStr)) {
                Log.w(TAG, "fetchRecentlyDeleted returned auth error, forcing session refresh...")
                sessionCheck = ensureGoSession(force = true)
                if (sessionCheck.isFailure) {
                    return Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话重建失败"))
                }
                jsonStr = Icloudcore.fetchRecentlyDeleted()
            }

            val error = classifyCoreResponse(jsonStr)
            if (error.kind != CoreErrorKind.NONE) {
                return Result.failure(Exception(error.message))
            }

            val json = JSONObject(jsonStr)
            val deletedIds = parseStringArray(json.optJSONArray("deletedIds")).distinct()
            Log.d(TAG, "purgeRecentlyDeleted - cloud reported ${deletedIds.size} recently-deleted ids")
            if (deletedIds.isEmpty()) {
                return Result.success(0)
            }

            val affectedMonths = photoDao.getYearMonthsForIds(deletedIds)
            if (affectedMonths.isEmpty()) {
                // None of the deleted ids match a cached row, but they must still be remembered:
                // a stale syncToken replay would otherwise re-insert them.
                rememberPurged(deletedIds, source = "expunge")
                return Result.success(0)
            }

            Log.d(
                TAG,
                "Purging recently deleted assets: ${deletedIds.size} cloud ids, " +
                    "months=$affectedMonths"
            )
            photoDao.deleteByIds(deletedIds)
            rebuildMonthIndexFor(affectedMonths)
            ICloudImageLoaderFactory.evictAssets(deletedIds)
            rememberPurged(deletedIds, source = "expunge")

            return Result.success(deletedIds.size)
        } catch (e: Exception) {
            Log.e(TAG, "purgeRecentlyDeleted error", e)
            return Result.failure(e)
        }
    }

    /**
     * Single entry point for URL refresh used by every consumer (Coil interceptor, image/video
     * detail viewer, MediaStore downloader).
     *
     * De-duplicates aggressively (P2-008): when a session rebuild invalidates every cached
     * signature at once, dozens of grid items fail simultaneously. Without de-duplication each
     * failure would fire its own `/records/lookup` and get the account rate-limited.
     *
     * Protection layers:
     *  1. If a refresh for this master is already in flight, await it instead of firing a new one.
     *  2. If this master was successfully refreshed within [REFRESH_DEDUP_WINDOW_MS], reuse the DB
     *     values without any network call.
     *  3. If the whole session was just rebuilt within [REFRESH_DEDUP_WINDOW_MS], collapse the
     *     resulting thundering herd into a short randomized delay so lookups are spread out.
     *
     * [force] bypasses layer 2 only (P2-011). It exists for explicit, user-driven, single-asset
     * recovery — a tap on "retry" / opening the detail viewer. Those are one-shot actions for one
     * master, so they can never form a burst, but they *must* be able to override the window: a
     * grid thumbnail that refreshed 30s ago would otherwise silently veto the detail page's
     * attempt to re-mint a dead URL, leaving it permanently broken. Layer 1 still applies, so a
     * forced call joins any genuinely concurrent refresh instead of duplicating it.
     */
    suspend fun refreshUrlsForMaster(
        masterId: String,
        force: Boolean = false
    ): Result<RefreshedUrls> {
        // A logout is tearing the DB down; minting URLs for rows that are about to disappear only
        // burns a `/records/lookup` and can race the wipe.
        wipeBlocked()?.let { return Result.failure(it.exceptionOrNull()!!) }

        // Layer 2: recent successful refresh -> serve from DB (unless the caller forces a re-mint).
        if (!force) {
            refreshMutex.withLock {
                val last = recentRefreshes[masterId] ?: 0L
                if (System.currentTimeMillis() - last < REFRESH_DEDUP_WINDOW_MS) {
                    Log.d(TAG, "refreshUrlsForMaster skipped (deduped within window) master=$masterId")
                    return withContext(Dispatchers.IO) { readUrlsFromDb(masterId) }
                }
            }
        }

        // Layer 1: join an in-flight refresh for the same master.
        val existing = refreshMutex.withLock { inFlightRefreshes[masterId] }
        if (existing != null) {
            Log.d(TAG, "refreshUrlsForMaster joining in-flight request master=$masterId")
            return existing.await()
        }

        val deferred = refreshScope.async {
            try {
                // Layer 3: spread out the burst that follows a session rebuild.
                val sinceSession = System.currentTimeMillis() - sessionUrlRefreshedAt
                if (sinceSession in 0 until REFRESH_DEDUP_WINDOW_MS) {
                    delay((20L..120L).random())
                }

                val result = doRefreshUrlsForMaster(masterId)
                if (result.isSuccess) {
                    refreshMutex.withLock {
                        recentRefreshes[masterId] = System.currentTimeMillis()
                    }
                }
                result
            } finally {
                refreshMutex.withLock { inFlightRefreshes.remove(masterId) }
            }
        }

        refreshMutex.withLock { inFlightRefreshes[masterId] = deferred }
        return deferred.await()
    }

    /**
     * Batched variant — one `/records/lookup` for many masters.
     * Go's [Icloudcore.refreshAssetUrls] already accepts a JSON array of record names.
     */
    suspend fun refreshUrlsForMasters(masterIds: List<String>): Result<Map<String, RefreshedUrls>> =
        withContext(Dispatchers.IO) {
            val unique = masterIds.distinct().filter { it.isNotBlank() }
            if (unique.isEmpty()) {
                return@withContext Result.success(emptyMap())
            }

            wipeBlocked()?.let { return@withContext Result.failure(it.exceptionOrNull()!!) }
            ensureGoSession().onFailure { return@withContext Result.failure(it) }

            try {
                val payload = JSONArray().apply { unique.forEach { put(it) } }.toString()
                Log.d(TAG, "refreshUrlsForMasters batch size=${unique.size}")

                var jsonStr = Icloudcore.refreshAssetUrls(payload)
                if (isAuthError(jsonStr)) {
                    ensureGoSession(force = true).onFailure { return@withContext Result.failure(it) }
                    jsonStr = Icloudcore.refreshAssetUrls(payload)
                }

                val json = JSONObject(jsonStr)
                if (!json.optBoolean("success", false)) {
                    val err = json.optString("errorMessage", "批量刷新直链失败")
                    return@withContext Result.failure(Exception(err))
                }

                val urlsObj = json.optJSONObject("urls")
                val now = System.currentTimeMillis()
                // Stamp the refreshed rows with the *current* generation, otherwise they stay
                // permanently "stale" and every subsequent load re-triggers a lookup.
                val generation = sessionManager.getSessionGeneration()
                val out = mutableMapOf<String, RefreshedUrls>()

                database.withTransaction {
                    for (masterId in unique) {
                        val masterUrls = urlsObj?.optJSONObject(masterId) ?: continue
                        val thumb = masterUrls.optString("thumbUrl").ifBlank { null }
                        val med = masterUrls.optString("medUrl").ifBlank { null }
                        val orig = masterUrls.optString("originalUrl").ifBlank { null }
                        val vid = masterUrls.optString("videoComplUrl").ifBlank { null }

                        // `/records/lookup` answers per master, but rows are keyed per asset: several
                        // assets can share one master, so every row pointing at it receives the same
                        // fresh signature (each with its own `urlFetchedAt`, which is what the Coil
                        // interceptor's staleness check reads).
                        val rows = photoDao.getPhotosByMasterId(masterId)
                        for (row in rows) {
                            photoDao.updateUrls(row.id, thumb, med, orig, vid, now, generation)
                        }
                        out[masterId] = RefreshedUrls(thumb, med, orig, vid)
                    }
                }

                refreshMutex.withLock {
                    out.keys.forEach { recentRefreshes[it] = now }
                }

                Result.success(out)
            } catch (e: Exception) {
                Log.e(TAG, "refreshUrlsForMasters error", e)
                Result.failure(e)
            }
        }

    private suspend fun readUrlsFromDb(masterId: String): Result<RefreshedUrls> {
        // Any row of the group will do: `/records/lookup` answered per master, so every asset
        // sharing it gets the same set of URLs.
        val row = photoDao.getPhotosByMasterId(masterId).firstOrNull()
            ?: return Result.failure(Exception("Master $masterId not found in local db"))
        return Result.success(
            RefreshedUrls(row.thumbUrl, row.medUrl, row.originalUrl, row.videoComplUrl)
        )
    }

    // ----------------------------------------------------------------------------------------
    // EXIF / GPS metadata (info panel)
    // ----------------------------------------------------------------------------------------

    /**
     * Resolves EXIF/GPS for one asset, caching the result in Room.
     *
     * Returns the raw JSON emitted by `icloudcore.FetchPhotoMetadata`. An **empty string is a
     * success**, meaning "fetched, and this record has no embedded metadata" (third-party imports,
     * plain screenshots). Callers rely on that distinction to fall back to file-info rendering
     * instead of showing an error.
     *
     * Caching:
     *  - `exifFetchedAt > 0` and inside [METADATA_TTL_MS] returns the stored value without any
     *    network call, including cached negatives.
     *  - [force] re-fetches regardless, for an explicit "retry" affordance.
     *
     * De-duplication mirrors [refreshUrlsForMaster]: opening the info card twice in quick
     * succession, or tapping it while the first request is in flight, must not fire two lookups.
     *
     * Error policy follows AGENTS.md §7.1: only `AUTH_EXPIRED` is worth rebuilding the session for.
     * A timeout or a throttle is surfaced to the caller for a plain retry, because a rebuild costs
     * a `/validate` round trip and re-signs every cached URL.
     */
    suspend fun fetchMetadata(masterId: String, force: Boolean = false): Result<String> {
        wipeBlocked()?.let { return Result.failure(it.exceptionOrNull()!!) }

        if (masterId.isBlank()) {
            return Result.failure(Exception("缺少 masterId，无法获取元数据"))
        }

        if (!force) {
            val cached = readMetadataFromDb(masterId)
            if (cached != null) return Result.success(cached)
        }

        val existing = metadataMutex.withLock { inFlightMetadata[masterId] }
        if (existing != null) {
            Log.d(TAG, "fetchMetadata joining in-flight request master=$masterId")
            return existing.await()
        }

        val deferred = refreshScope.async {
            try {
                doFetchMetadata(masterId)
            } finally {
                metadataMutex.withLock { inFlightMetadata.remove(masterId) }
            }
        }

        metadataMutex.withLock { inFlightMetadata[masterId] = deferred }
        return deferred.await()
    }

    /**
     * Returns the cached metadata JSON, or null when it has never been fetched or has aged out.
     *
     * Empty string is returned as-is: it is the cached negative result.
     */
    private suspend fun readMetadataFromDb(masterId: String): String? = withContext(Dispatchers.IO) {
        // EXIF describes the master (the file), so any asset of the group shares the cached answer.
        val row = runCatching { photoDao.getPhotosByMasterId(masterId).firstOrNull() }
            .getOrNull()
            ?: return@withContext null
        val json = row.exifJson ?: return@withContext null
        val age = System.currentTimeMillis() - row.exifFetchedAt
        // A cached *negative* is kept forever: a record that has no EXIF today will not grow one.
        // A cached *positive* expires, because metadata can legitimately be re-written server-side.
        if (json.isEmpty()) return@withContext json
        if (row.exifFetchedAt <= 0 || age > METADATA_TTL_MS) return@withContext null
        json
    }

    private suspend fun doFetchMetadata(masterId: String): Result<String> = withContext(Dispatchers.IO) {
        var sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(
                sessionCheck.exceptionOrNull() ?: Exception("会话建立失败")
            )
        }

        try {
            val payload = JSONArray().apply { put(masterId) }.toString()
            var jsonStr = Icloudcore.fetchPhotoMetadata(payload)
            var classified = classifyCoreResponse(jsonStr)

            if (classified.kind == CoreErrorKind.AUTH_EXPIRED) {
                Log.w(TAG, "fetchMetadata auth expired, forcing session refresh master=$masterId")
                sessionCheck = ensureGoSession(force = true)
                if (sessionCheck.isFailure) {
                    return@withContext Result.failure(
                        sessionCheck.exceptionOrNull() ?: Exception("会话重建失败")
                    )
                }
                jsonStr = Icloudcore.fetchPhotoMetadata(payload)
                classified = classifyCoreResponse(jsonStr)
            }

            if (classified.kind != CoreErrorKind.NONE) {
                Log.w(TAG, "fetchMetadata failed (${classified.kind}): ${classified.message}")
                return@withContext Result.failure(
                    Exception(
                        when (classified.kind) {
                            CoreErrorKind.TIMEOUT, CoreErrorKind.NETWORK ->
                                "网络请求失败，请检查网络后重试"
                            CoreErrorKind.RATE_LIMITED -> "请求过于频繁，请稍后重试"
                            else -> classified.message.ifBlank { "获取照片信息失败" }
                        }
                    )
                )
            }

            val json = JSONObject(jsonStr)
            val items = json.optJSONObject("items")
            val entry = items?.optJSONObject(masterId)

            // No entry at all: the record vanished server-side. Treat as a cached negative rather
            // than an error, so the panel degrades to file info instead of showing a failure.
            val metadataJson = entry?.toString() ?: ""

            val now = System.currentTimeMillis()
            photoDao.setExif(masterId, metadataJson, now)
            Log.d(
                TAG,
                "fetchMetadata ok master=$masterId bytes=${metadataJson.length} " +
                    "hasMetadata=${entry?.optBoolean("hasMetadata", false) == true}"
            )

            Result.success(metadataJson)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMetadata error", e)
            Result.failure(e)
        }
    }

    private suspend fun doRefreshUrlsForMaster(masterId: String): Result<RefreshedUrls> = withContext(Dispatchers.IO) {
        var sessionCheck = ensureGoSession()
        if (sessionCheck.isFailure) {
            return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话建立失败"))
        }

        try {
            val payload = JSONArray().apply { put(masterId) }.toString()
            var jsonStr = Icloudcore.refreshAssetUrls(payload)
            if (isAuthError(jsonStr)) {
                Log.w(
                    TAG,
                    "refreshUrlsForMaster auth error (status=${parseHttpStatus(jsonStr)}), forcing session refresh"
                )
                sessionCheck = ensureGoSession(force = true)
                if (sessionCheck.isFailure) {
                    return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话重建失败"))
                }
                jsonStr = Icloudcore.refreshAssetUrls(payload)
            }

            val json = JSONObject(jsonStr)
            val success = json.optBoolean("success", false)

            if (!success) {
                val err = json.optString("errorMessage", "刷新直链失败")
                return@withContext Result.failure(Exception(err))
            }

            val urlsObj = json.optJSONObject("urls")
            val masterUrls = urlsObj?.optJSONObject(masterId)
            if (masterUrls != null) {
                val thumb = masterUrls.optString("thumbUrl").ifBlank { null }
                val med = masterUrls.optString("medUrl").ifBlank { null }
                val orig = masterUrls.optString("originalUrl").ifBlank { null }
                val vid = masterUrls.optString("videoComplUrl").ifBlank { null }

                val fetchedAt = System.currentTimeMillis()
                val generation = sessionManager.getSessionGeneration()
                // One master can back several assets; give each row the fresh signature so its own
                // `urlFetchedAt` (and therefore its staleness verdict) is correct.
                val rows = photoDao.getPhotosByMasterId(masterId)
                for (row in rows) {
                    photoDao.updateUrls(
                        assetId = row.id,
                        thumbUrl = thumb,
                        medUrl = med,
                        originalUrl = orig,
                        videoComplUrl = vid,
                        fetchedAt = fetchedAt,
                        sessionGeneration = generation
                    )
                }

                Result.success(RefreshedUrls(thumb, med, orig, vid))
            } else {
                Result.failure(Exception("Master $masterId not found in refresh response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshUrlsForMaster error", e)
            Result.failure(e)
        }
    }

    private fun parsePhotoItems(arr: JSONArray?): List<PhotoAssetEntity> =
        parsePhotoItems(arr, mutableSetOf())

    /**
     * Parses Go photo items into rows, dropping anything flagged `isDeleted`.
     *
     * [trashedIds] receives the identities of the skipped rows (asset id and master id), so the
     * caller can delete any stale copy already sitting in Room. That is what stops a photo the
     * user deleted on another device from surviving here: CloudKit keeps the record (marked
     * deleted) for 30 days, so it arrives in every query just like a live one.
     */
    private fun parsePhotoItems(
        arr: JSONArray?,
        trashedIds: MutableSet<String>
    ): List<PhotoAssetEntity> {
        if (arr == null) return emptyList()
        val list = mutableListOf<PhotoAssetEntity>()
        val now = System.currentTimeMillis()
        // Stamp rows with the generation that actually minted these URLs.
        val generation = sessionManager.getSessionGeneration()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isBlank()) continue

            val masterId = obj.optString("masterId", id)
            val filename = obj.optString("filename", id)
            val rawAssetDate = obj.optLong("assetDate", 0L)
            // P2-016: never fall back to `now` for a missing assetDate. Doing so gives the same
            // row a different sort key on every page fetch, which re-pins it to the top of the
            // `ORDER BY assetDate DESC` list and makes the grid jump on each APPEND. A stable 0
            // sorts to the bottom and stays put.
            val assetDate = if (rawAssetDate > 0L) rawAssetDate else 0L
            val addedDate = obj.optLong("addedDate", 0L).takeIf { it > 0L } ?: assetDate
            val isFavorite = obj.optBoolean("isFavorite", false)
            val isHidden = obj.optBoolean("isHidden", false)
            // `isDeleted` means the asset sits in "Recently Deleted". It is not a tombstone: the
            // record still exists and would otherwise be written into the gallery as a normal
            // photo. Rows flagged here are dropped before they ever reach Room.
            val isDeleted = obj.optBoolean("isDeleted", false)
            val duration = obj.optLong("duration", 0)
            val mediaType = obj.optString("mediaType", "IMAGE")
            val itemType = obj.optString("itemType", "")
            val width = obj.optInt("width", 0)
            val height = obj.optInt("height", 0)
            val size = obj.optLong("size", 0)
            val thumbUrl = obj.optString("thumbUrl").ifBlank { null }
            val medUrl = obj.optString("medUrl").ifBlank { null }
            val originalUrl = obj.optString("originalUrl").ifBlank { null }
            val videoComplUrl = obj.optString("videoComplUrl").ifBlank { null }
            val changeTag = obj.optString("recordChangeTag").ifBlank { null }

            if (isDeleted) {
                // Counted by the caller so the sweep can delete whatever stale copy still exists.
                trashedIds.add(id)
                if (masterId != id) trashedIds.add(masterId)
                continue
            }

            list.add(
                PhotoAssetEntity(
                    id = id,
                    masterId = masterId,
                    filename = filename,
                    assetDate = assetDate,
                    addedDate = addedDate,
                    isFavorite = isFavorite,
                    isHidden = isHidden,
                    duration = duration,
                    mediaType = mediaType,
                    itemType = itemType,
                    width = width,
                    height = height,
                    size = size,
                    thumbUrl = thumbUrl,
                    medUrl = medUrl,
                    originalUrl = originalUrl,
                    videoComplUrl = videoComplUrl,
                    recordChangeTag = changeTag,
                    urlFetchedAt = now,
                    sessionGeneration = generation,
                    // Bucket on the way in. Without this every row lands at `yearMonth = 0`, which
                    // the gallery's month queries never select — the photos exist and are counted,
                    // but are invisible. The scan used to fix them up afterwards; `syncIncremental`
                    // did not, so incrementally-synced photos silently disappeared from the grid.
                    yearMonth = MonthBucketing.yearMonthOf(assetDate)
                )
            )
        }
        return list
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) {
                list.add(s)
            }
        }
        return list
    }

    /**
     * Merges incoming photo entities with existing DB rows before upserting.
     *
     * Protects master-level metadata (such as `mediaType == "LIVE_PHOTO"`, `videoComplUrl`, URLs,
     * filename, dimensions) from being overwritten by incremental change feeds (`/records/changes`)
     * that only deliver updated `CPLAsset` fields (e.g. `isFavorite`) without a `CPLMaster` payload.
     */
    private suspend fun mergeWithExistingAssets(incoming: List<PhotoAssetEntity>): List<PhotoAssetEntity> {
        if (incoming.isEmpty()) return emptyList()
        return incoming.map { item ->
            val existing = photoDao.getPhotoById(item.id) ?: return@map item
            val effectiveMediaType = if (item.mediaType.isBlank() || (item.mediaType == "IMAGE" && (existing.mediaType == "LIVE_PHOTO" || existing.mediaType == "VIDEO"))) {
                existing.mediaType
            } else {
                item.mediaType
            }
            item.copy(
                mediaType = effectiveMediaType,
                videoComplUrl = item.videoComplUrl ?: existing.videoComplUrl,
                thumbUrl = item.thumbUrl ?: existing.thumbUrl,
                medUrl = item.medUrl ?: existing.medUrl,
                originalUrl = item.originalUrl ?: existing.originalUrl,
                filename = if (item.filename == item.id && existing.filename != existing.id) existing.filename else item.filename,
                itemType = if (item.itemType.isBlank()) existing.itemType else item.itemType,
                width = if (item.width == 0) existing.width else item.width,
                height = if (item.height == 0) existing.height else item.height,
                size = if (item.size == 0L) existing.size else item.size
            )
        }
    }

    private suspend fun upsertPhotosWithMerge(photos: List<PhotoAssetEntity>) {
        if (photos.isEmpty()) return
        val merged = mergeWithExistingAssets(photos)
        photoDao.upsertAll(merged)
    }

    /**
     * Current Go core session generation. Rows stamped with a different value carry dead URLs.
     */
    fun currentSessionGeneration(): Int = sessionManager.getSessionGeneration()

    // ----------------------------------------------------------------------------------------
    // Logout / account-switch teardown (P2-018)
    // ----------------------------------------------------------------------------------------

    /**
     * True while a logout wipe is running.
     *
     * Every network entry point in this class checks this and bails out immediately. Without it,
     * a [PhotoRemoteMediator] APPEND that is already mid-flight can complete its `upsertAll()`
     * *after* the tables were cleared, leaving the previous account's rows behind — the exact
     * "list shows the old account's photos" symptom of P2-018.
     *
     * The flag is deliberately a plain [Volatile] boolean rather than a suspending/flow-based
     * guard: it is only ever set from `logout()` on the main thread, and the entry points read it
     * synchronously before doing any work, so there is no window where a stale value could leak
     * through. See docs/PITFALLS.md §7 ("synchronous sentinel > async state machine").
     */
    /**
     * Enqueues local photo for background upload (Phase 3).
     */
    suspend fun enqueuePhotoForUpload(
        contentUri: android.net.Uri,
        filename: String,
        mimeType: String,
        fileSize: Long,
        localSha256: String
    ): Result<Long> = dataMutationMutex.withLock {
        withContext(Dispatchers.IO) {
            wipeBlocked()?.let { return@withContext Result.failure(it.exceptionOrNull()!!) }

            val existingInQueue = database.uploadQueueDao().findBySha256(localSha256)
            if (existingInQueue != null) {
                Log.d(TAG, "Photo already in upload queue: $filename ($localSha256)")
                return@withContext Result.success(existingInQueue.id)
            }

            val motionInfo = if (mimeType.startsWith("image/")) {
                MotionPhotoDetector.detect(appContext, contentUri, fileSize)
            } else {
                MotionPhotoInfo()
            }

            val entity = app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity(
                mediaStoreUri = contentUri.toString(),
                filename = filename,
                mimeType = mimeType,
                fileSize = fileSize,
                localSha256 = localSha256,
                status = "PENDING",
                isMotionPhoto = motionInfo.isMotionPhoto,
                videoOffset = motionInfo.videoOffset,
                videoSize = motionInfo.videoSize,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val queueId = database.uploadQueueDao().insert(entity)
            Log.d(TAG, "Enqueued photo for upload: $filename (queueId: $queueId, motionPhoto=${motionInfo.isMotionPhoto})")
            Result.success(queueId)
        }
    }

    /**
     * Executes single photo upload using file descriptor (FD) via Go core.
     */
    suspend fun uploadPhotoFromQueue(queueItem: app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity, context: Context): Result<LocalUploadResult> =
        withContext(Dispatchers.IO) {
            wipeBlocked()?.let { return@withContext Result.failure(it.exceptionOrNull()!!) }

            val sessionCheck = ensureGoSession()
            if (sessionCheck.isFailure) {
                return@withContext Result.failure(sessionCheck.exceptionOrNull() ?: Exception("会话无法建立"))
            }

            val cookieValidation = app.acloudroid.qwq.ui.auth.CookieValidationResult.validate(sessionManager.getCookieString())
            if (!cookieValidation.hasPcsPhotos) {
                return@withContext Result.failure(Exception("缺少 X-APPLE-WEBAUTH-PCS-Photos Cookie，拒绝上传"))
            }

            val uri = android.net.Uri.parse(queueItem.mediaStoreUri)

            // Whether we ended up reading the untouched original stream. When this is false the
            // platform has redacted location, so the record will carry no GPS - worth a log line,
            // because the upload otherwise succeeds and the loss is invisible.
            var openedOriginal = false

            // The URI to read *content* from. `requireOriginal` is what un-redacts GPS, so the
            // EXIF reader has to be handed this one too - not the plain [uri]. Passing the plain
            // URI here was a second, independent reason GPS never made it into a photo record,
            // even on a device that already had the permission.
            var contentUri = uri

            val pfd = try {
                val requireOriginalUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        android.provider.MediaStore.setRequireOriginal(uri)
                    } catch (e: Exception) {
                        uri
                    }
                } else {
                    uri
                }

                try {
                    val fd = context.contentResolver.openFileDescriptor(requireOriginalUri, "r")
                    if (fd != null) {
                        openedOriginal = true
                        contentUri = requireOriginalUri
                    }
                    fd
                } catch (e: Exception) {
                    // `UnsupportedOperationException: Caller must hold ACCESS_MEDIA_LOCATION
                    // permission to access original` lands here. The retry below keeps the upload
                    // working; only GPS is lost.
                    Log.w(
                        TAG,
                        "Original stream unavailable for $uri (${e.javaClass.simpleName}: ${e.message}); " +
                            "falling back to the redacted stream - GPS will be dropped"
                    )
                    context.contentResolver.openFileDescriptor(uri, "r")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open FileDescriptor for $uri", e)
                null
            }

            if (pfd == null) {
                val nextRetry = queueItem.retryCount + 1
                val nextStatus = if (nextRetry <= MAX_AUTO_RETRIES) "PENDING" else "FAILED"
                val errorMsg = "Unable to open FileDescriptor" + if (nextStatus == "PENDING") " (自动重试 $nextRetry/$MAX_AUTO_RETRIES)" else ""

                database.uploadQueueDao().update(
                    queueItem.copy(
                        status = nextStatus,
                        retryCount = nextRetry,
                        lastError = errorMsg,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                return@withContext Result.failure(Exception("无法打开文件描述符"))
            }

            val itemType = when {
                // `.mov` must be reported as the QuickTime UTI, not folded into MPEG-4. A genuine
                // iPhone clip carries `com.apple.quicktime-movie`, and the client keys its
                // playability and its `codec` rendering off this value.
                queueItem.mimeType == "video/quicktime" -> "com.apple.quicktime-movie"
                queueItem.mimeType.startsWith("video/") -> "public.mpeg-4"
                queueItem.mimeType == "image/png" -> "public.png"
                queueItem.mimeType == "image/gif" -> "com.compuserve.gif"
                queueItem.mimeType == "image/heic" || queueItem.mimeType == "image/heif" -> "public.heic"
                else -> "public.jpeg"
            }

            val isVideo = queueItem.mimeType.startsWith("video/")

            val exifData = if (!isVideo) {
                // [contentUri] carries `requireOriginal`, without which MediaProvider strips the
                // GPS tags out of the EXIF before we ever see them.
                LocalExifExtractor.extract(context, contentUri)
            } else {
                null
            }
            val videoInfo = if (isVideo) {
                LocalExifExtractor.extractVideoInfo(context, contentUri)
            } else {
                null
            }

            // Videos need a JPEG poster frame too. Without one the asset has no thumbnail
            // anywhere: an exclamation mark on Apple's side and the grey Coil error tile in our
            // own grid (ISS-040).
            val derivatives = if (isVideo) {
                LocalExifExtractor.generateVideoDerivatives(
                    context, contentUri, videoInfo?.rotation ?: 0
                )
            } else {
                // Pass the orientation already parsed above so the derivatives cannot disagree
                // with the `originalOrientation` this same upload writes into the record.
                LocalExifExtractor.generateDerivatives(
                    context, contentUri, exifData?.orientation ?: 1
                )
            }

            val assetDate = exifData?.assetDate
                ?: videoInfo?.creationDateMillis?.takeIf { it > 0 }
                ?: System.currentTimeMillis()

            var isMotionPhoto = queueItem.isMotionPhoto
            var videoOffset = queueItem.videoOffset
            var videoSize = queueItem.videoSize

            if (!isMotionPhoto && !isVideo && queueItem.mimeType.startsWith("image/")) {
                val motionInfo = MotionPhotoDetector.detect(context, contentUri, queueItem.fileSize)
                if (motionInfo.isMotionPhoto) {
                    isMotionPhoto = true
                    videoOffset = motionInfo.videoOffset
                    videoSize = motionInfo.videoSize
                    Log.i(TAG, "On-the-fly MotionPhoto detected for ${queueItem.filename}: offset=$videoOffset, size=$videoSize")
                    database.uploadQueueDao().update(
                        queueItem.copy(
                            isMotionPhoto = true,
                            videoOffset = videoOffset,
                            videoSize = videoSize,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            var motionComponents: LocalExifExtractor.MotionPhotoComponents? = null
            if (isMotionPhoto && videoOffset > 0) {
                try {
                    motionComponents = LocalExifExtractor.extractMotionPhotoComponents(
                        context, contentUri, videoOffset
                    )
                    Log.i(
                        TAG,
                        "Extracted Motion Photo components for ${queueItem.filename}: still=${motionComponents.stillFile.length()}B, vid=${motionComponents.videoFile.length()}B, dur=${motionComponents.videoInfo.durationMs}ms"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract Motion Photo components for ${queueItem.mediaStoreUri}: ${e.message}")
                }
            }

            val metadataJson = JSONObject().apply {
                put("assetDate", assetDate)
                put("originalCreationDate", assetDate)
                put("width", videoInfo?.width ?: exifData?.width ?: 0)
                put("height", videoInfo?.height ?: exifData?.height ?: 0)
                put("orientation", exifData?.orientation ?: 1)
                put("itemType", itemType)
                // NOTE: `fileType` is intentionally overwritten in the Go core with `itemType`.
                // Apple stores a UTI there, never a MIME type (ISS-040).
                put("fileType", queueItem.mimeType)

                // Detailed Camera EXIF (photos only)
                exifData?.make?.let { put("make", it) }
                exifData?.model?.let { put("model", it) }
                exifData?.software?.let { put("software", it) }
                exifData?.lensModel?.let { put("lensModel", it) }
                exifData?.fNumber?.let { put("fNumber", it) }
                exifData?.exposureTime?.let { put("exposureTime", it) }
                exifData?.iso?.let { put("iso", it) }
                exifData?.focalLength?.let { put("focalLength", it) }
                exifData?.focalLength35?.let { put("focalLength35", it) }
                exifData?.altitude?.let { put("altitude", it) }
                exifData?.dateTimeOriginal?.let { put("dateTimeOriginal", it) }
                exifData?.lat?.let { put("lat", it) }
                exifData?.lon?.let { put("lon", it) }

                // Motion Photo (Live Photo) attributes
                motionComponents?.let { mc ->
                    val mediaGroupId = java.util.UUID.randomUUID().toString()
                    val vidInfo = mc.videoInfo
                    val durNs = vidInfo.durationMs * 1_000_000L
                    val dispNs = (vidInfo.durationMs / 2) * 1_000_000L

                    put("isLivePhoto", true)
                    put("livePhotoVideoPath", mc.videoFile.absolutePath)
                    put("mediaGroupId", mediaGroupId)
                    put("vidComplDurValue", durNs)
                    put("vidComplDurScale", 1_000_000_000L)
                    put("vidComplDispValue", dispNs)
                    put("vidComplDispScale", 1_000_000_000L)
                    put("vidComplWidth", vidInfo.width)
                    put("vidComplHeight", vidInfo.height)
                }

                // Videos carry no EXIF, so their coordinates come from MediaStore instead.
                // `lat`/`lon` are shared with the photo path: the Go core turns either into the
                // QuickTime ISO-6709 field for video and into the `{GPS}` plist for photos.
                if (isVideo) {
                    videoInfo?.latitude?.let { put("lat", it) }
                    videoInfo?.longitude?.let { put("lon", it) }
                }

                // Video attributes (videos only).
                //
                // `videoMake`/`videoModel`/`videoSoftware`/`lat`/`lon` are what the Go core writes
                // into the QuickTime `moov/meta` block. Apple renders a video's camera-info panel
                // from those atoms, not from EXIF, so without them the upload shows a duration and
                // no camera information (ISS-040).
                videoInfo?.codec?.let { put("codec", it) }
                videoInfo?.frameRate?.let { put("videoFrameRate", it) }
                if ((videoInfo?.durationMs ?: 0L) > 0) put("durationMs", videoInfo!!.durationMs)
                videoInfo?.make?.let { put("videoMake", it) }
                videoInfo?.model?.let { put("videoModel", it) }
                videoInfo?.software?.let { put("videoSoftware", it) }

                // Derivatives
                derivatives.thumbPath?.let { put("thumbPath", it) }
                if (derivatives.thumbWidth > 0) put("thumbWidth", derivatives.thumbWidth)
                if (derivatives.thumbHeight > 0) put("thumbHeight", derivatives.thumbHeight)
                derivatives.medPath?.let { put("medPath", it) }
                if (derivatives.medWidth > 0) put("medWidth", derivatives.medWidth)
                if (derivatives.medHeight > 0) put("medHeight", derivatives.medHeight)
            }.toString()

            val uploadPfd = if (motionComponents != null) {
                try {
                    pfd.close()
                    android.os.ParcelFileDescriptor.open(motionComponents.stillFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e: Exception) {
                    pfd
                }
            } else {
                pfd
            }

            val rawFd = uploadPfd.detachFd()
            database.uploadQueueDao().update(queueItem.copy(status = "UPLOADING", updatedAt = System.currentTimeMillis()))

            val jsonResultStr = try {
                Icloudcore.uploadPhotoAsset(
                    rawFd.toLong(),
                    queueItem.filename,
                    queueItem.mimeType,
                    metadataJson
                )
            } finally {
                derivatives.thumbPath?.let { java.io.File(it).delete() }
                derivatives.medPath?.let { java.io.File(it).delete() }
                motionComponents?.stillFile?.delete()
                motionComponents?.videoFile?.delete()
            }

            val jsonResult = JSONObject(jsonResultStr)
            val success = jsonResult.optBoolean("success", false)

            if (success) {
                val masterId = jsonResult.optString("masterId", "")
                val assetId = jsonResult.optString("assetId", "")
                // True when the Go core found an existing `CPLMaster` for these exact bytes and
                // skipped the record creation. Still a success - just nothing new to fetch.
                val duplicate = jsonResult.optBoolean("duplicate", false)
                // Persist the SHA-256 the Go core used to sign `/assets/upload`. Without this the
                // first dedupe layer (`findBySha256`) never matched anything, because the column
                // stayed empty on every row.
                val localSha256 = jsonResult.optString("localSha256", "")
                database.uploadQueueDao().update(
                    queueItem.copy(
                        status = "SUCCESS",
                        localSha256 = localSha256.ifBlank { queueItem.localSha256 },
                        updatedAt = System.currentTimeMillis()
                    )
                )

                if (queueItem.mediaStoreUri.contains("/cache/share_uploads/")) {
                    try {
                        val file = java.io.File(android.net.Uri.parse(queueItem.mediaStoreUri).path ?: "")
                        if (file.exists()) {
                            file.delete()
                            Log.d(TAG, "Cleaned up temporary share upload file: ${file.absolutePath}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to clean up temp share upload file: ${e.message}")
                    }
                }

                // Attribution for the next time an upload renders grey / exclamation on Apple's
                // side. Never log signed URLs (AGENTS 8.2).
                Log.i(
                    TAG,
                    "upload ok master=$masterId asset=$assetId duplicate=$duplicate itemType=$itemType " +
                        "thumb=${derivatives.thumbPath != null} med=${derivatives.medPath != null} " +
                        "w=${videoInfo?.width ?: exifData?.width ?: 0} h=${videoInfo?.height ?: exifData?.height ?: 0} " +
                        "gps=${exifData?.lat != null || videoInfo?.latitude != null} original=$openedOriginal"
                )

                if (!duplicate) {
                    // Post-upload idempotency guard: Trigger syncIncremental to pull asset back into local photo_assets
                    dataMutationMutex.withLock {
                        syncIncrementalLocked(100)
                    }
                }

                Result.success(
                    LocalUploadResult(
                        success = true,
                        masterId = masterId,
                        assetId = assetId,
                        fileChecksum = masterId
                    )
                )
            } else {
                val errCode = jsonResult.optString("errorCode", "UPLOAD_FAILED")
                val errMsg = jsonResult.optString("errorMsg", "Upload failed")
                val nextRetry = queueItem.retryCount + 1
                val nextStatus = if (nextRetry <= MAX_AUTO_RETRIES) "PENDING" else "FAILED"
                val errorMsg = "$errCode: $errMsg" + if (nextStatus == "PENDING") " (自动重试 $nextRetry/$MAX_AUTO_RETRIES)" else ""

                database.uploadQueueDao().update(
                    queueItem.copy(
                        status = nextStatus,
                        retryCount = nextRetry,
                        lastError = errorMsg,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                Log.w(
                    TAG,
                    "Upload failed for ${queueItem.filename} ($errCode: $errMsg). Retry $nextRetry/$MAX_AUTO_RETRIES -> status=$nextStatus"
                )
                Result.failure(Exception("$errCode: $errMsg"))
            }
        }

    @Volatile
    var isWiping: Boolean = false
        private set

    /**
     * Blocks all further network work ahead of a wipe. Must be called before [wipeAllData].
     *
     * Setting this first is what makes the teardown race-free: `RemoteMediator` checks it at the
     * top of every `load()`, so once it is true no new page can be requested, and any APPEND
     * already past that check will be awaited by the caller's `stopSync()` before the tables are
     * dropped.
     */
    fun beginWipe() {
        isWiping = true
        Log.i(TAG, "Session teardown started - blocking all photo network entry points")
    }

    /** Re-enables network work once the wipe is finished (including on the failure path). */
    fun endWipe() {
        isWiping = false
        Log.i(TAG, "Session teardown finished - photo network entry points re-enabled")
    }

    /**
     * Rejects network work while [isWiping] is set.
     *
     * Callers return this as a *failure* rather than silently succeeding: a silent success would
     * look like "0 photos" and let the UI render an empty-but-healthy grid.
     */
    private fun wipeBlocked(): Result<Unit>?
        = if (isWiping) Result.failure(Exception("正在退出登录，已暂停照片同步")) else null

    /**
     * Public read-only view of the wipe guard for [MonthScanEngine].
     *
     * The scan must abort mid-walk when a logout teardown starts, otherwise it would keep writing
     * rows for an account that is being wiped from underneath it (P2-018).
     */
    internal fun isWipeInProgress(): Boolean = isWiping

    /**
     * Clears everything this feature owns locally so the next login starts from a clean slate.
     *
     * Photo rows are keyed by CloudKit `recordName` and `sync_state` holds a `syncToken` /
     * `continuationMarker` minted by a specific account — neither is meaningful for a different
     * Apple ID or region. Leaving them behind makes the fresh session look "already synced" and
     * then fail every URL refresh against records it cannot see (P2-018).
     *
     * Only the data layer is touched here; WebView cookies and the credential vault stay with the
     * caller so the teardown order remains explicit. The caller must set [isWiping] *before*
     * calling this.
     *
     * @return true when the wipe completed without an exception.
     */
    suspend fun wipeAllData(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Clear the sync cursor first: if the second statement fails we have at worst lost the
            // cursor (a full re-fetch) rather than kept a token pointing at deleted rows.
            syncStateDao.clearAll()
            scanStateDao.clearAll()
            photoMonthIndexDao.clearAll()
            photoDao.clearAll()
            database.uploadQueueDao().clearAll()

            // In-memory refresh bookkeeping refers to masters that no longer exist.
            refreshMutex.withLock {
                recentRefreshes.clear()
                inFlightRefreshes.clear()
            }

            Log.i(TAG, "Photo database wiped (photo_assets + sync_state + month index + scan state)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "wipeAllData failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "PhotoRepository"
        const val KEY_PRIMARY_SYNC = "PRIMARY_SYNC"

        /**
         * How many ids of a pending purge are printed before the deletion runs.
         *
         * The reconcile pass is the only place a live row can disappear, so the ids are logged
         * beforehand. Printing thousands of 36-character ids would drown the log, and the count is
         * enough to correlate with the database afterwards.
         */
        private const val PURGE_LOG_SAMPLE = 10

        /**
         * Window within which a successful URL refresh for a given master is reused instead of
         * hitting `/records/lookup` again (P2-008).
         */
        private const val REFRESH_DEDUP_WINDOW_MS = 5 * 60 * 1000L

        /**
         * How many months' cover URLs are re-minted per batched `/records/lookup`.
         *
         * Covers are fetched one image at a time (sequentially) within a batch, so this does not
         * control download parallelism — it controls how many signatures are refreshed together.
         * Go's `refreshAssetUrls` already accepts a JSON array, so a batch is one round trip.
         * 50 keeps each lookup's response small while still covering a 200-month library in four
         * calls instead of two hundred (AGENTS §6.2).
         */
        private const val COVER_PREFETCH_BATCH = 50

        /**
         * How long a fetched EXIF payload stays authoritative.
         *
         * Long on purpose: EXIF describes the capture itself, which never changes. A day only
         * exists so that metadata Apple re-writes server-side (e.g. after an edit) eventually
         * refreshes. Cached *negatives* ignore this value entirely — see `readMetadataFromDb`.
         */
        private const val METADATA_TTL_MS: Long = 24L * 60 * 60 * 1000

        private const val MAX_AUTO_RETRIES = 3

        /**
         * Matches an explicit HTTP status token such as `HTTP 401`, `status:421`, `StatusCode=401`.
         * The `\b` word boundary prevents accidental hits inside longer digit/base64 runs.
         */
        private val AUTH_STATUS_REGEX =
            Regex("""(?:HTTP|status|StatusCode)\s*[:=]?\s*(?:401|421)\b""", RegexOption.IGNORE_CASE)
    }
}
