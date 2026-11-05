package app.acloudroid.qwq.feature.photos.data

import android.util.Log
import app.acloudroid.qwq.feature.photos.data.db.PhotoDao
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexDao
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity
import app.acloudroid.qwq.feature.photos.data.db.ScanStateDao
import app.acloudroid.qwq.feature.photos.data.db.ScanStateEntity
import app.acloudroid.qwq.feature.photos.data.db.SyncStateDao
import app.acloudroid.qwq.feature.photos.data.db.SyncStateEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Snapshot of a full-library scan's progress (P2-016).
 *
 * @param scanned how many assets this scan has written so far.
 * @param estimatedTotal best-effort library size, scraped from the iCloud web page at login.
 *   `0` means unknown — CloudKit's cursor API never reports a total, so that is the normal case
 *   for a user who restored a session instead of re-logging in.
 * @param currentYearMonth the month currently being written, or 0 before the first batch.
 */
data class ScanProgress(
    val isScanning: Boolean = false,
    val scanned: Int = 0,
    val estimatedTotal: Int = 0,
    val currentYearMonth: Int = 0,
    val isComplete: Boolean = false,
    /**
     * True while the post-walk cover download pass is running.
     *
     * A separate flag rather than another `scanned` count because this stage has its own unit
     * (months, ~200 of them) and its own denominator. The UI shows "正在缓存月份封面…" off this
     * so the several minutes it can take is attributable to something visible instead of looking
     * like the walk stalled at 100%.
     *
     * Always false when the user has cover previews switched off — that stage does not run at all.
     */
    val isCachingCovers: Boolean = false
) {
    /**
     * Fraction for the progress bar, or null when there is no denominator.
     *
     * Clamped to 0.99 while running: the web page's count and the `CPLAsset` count do not agree
     * exactly (hidden items, orphan masters, Live Photo composition), so the scan can genuinely
     * reach or pass the estimate. Showing "100%" and then continuing to work is worse than
     * stopping at 99% until it really finishes.
     */
    val fraction: Float?
        get() = when {
            isComplete -> 1f
            estimatedTotal <= 0 -> null
            else -> (scanned.toFloat() / estimatedTotal).coerceIn(0f, 0.99f)
        }
}

/**
 * Walks the whole photo library once and buckets it into month sections (P2-016).
 *
 * ## Why this replaces Paging 3
 *
 * The old grid used `RemoteMediator` + Paging 3, and Paging 3 invalidates its `PagingSource` after
 * **every** successful APPEND. The reload window is `initialLoadSize`, so scrolling past it
 * clamped `firstVisibleItemIndex` and the list visibly jumped to the top (ISS-021). That is
 * inherent to `RemoteMediator`, not a tunable.
 *
 * This engine therefore does the fetching itself and writes plain rows to Room. The UI then reads
 * month sections from the database and has nothing to invalidate: the scroll anchor is a month
 * key, which does not change when rows are appended.
 *
 * ## Scan while browsing
 *
 * The cursor walks newest → oldest, so the first batches fill the most recent months. Each batch is
 * committed and its month index rebuilt immediately, which means the user can browse while the
 * rest of the library streams in.
 */
class MonthScanEngine(
    private val database: PhotoDatabase,
    private val repository: PhotoRepository,
    /**
     * Read only to decide whether the post-walk cover pass should run at all.
     *
     * The repository reads the same flag for its own halves; this one exists so the scan can skip
     * the whole stage — including publishing [ScanProgress.isCachingCovers] — without asking the
     * repository first and parsing a zero back out.
     */
    private val appSettings: app.acloudroid.qwq.data.settings.AppSettings
) {
    private val photoDao: PhotoDao = database.photoDao()
    private val monthIndexDao: PhotoMonthIndexDao = database.photoMonthIndexDao()
    private val scanStateDao: ScanStateDao = database.scanStateDao()
    private val syncStateDao: SyncStateDao = database.syncStateDao()

    /** Serialises concurrent triggers (foreground VM + WorkManager). */
    private val scanMutex = Mutex()

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    /**
     * Runs (or resumes) the scan until the cursor is exhausted.
     *
     * Safe to call repeatedly: a second caller waits for the first to finish and then returns
     * immediately because the scan is already complete. Cancellation leaves the cursor persisted,
     * so a later call resumes where this one stopped.
     *
     * @param batchSize records per `/records/query` call.
     */
    suspend fun scanAll(
        force: Boolean = false,
        batchSize: Int = DEFAULT_BATCH_SIZE
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!scanMutex.tryLock()) {
            // Another trigger owns the scan. Report its current state rather than starting over.
            Log.d(TAG, "scanAll skipped - a scan is already running")
            return@withContext Result.success(0)
        }

        try {
            if (repository.isWipeInProgress()) {
                return@withContext Result.failure(Exception("正在退出登录，已暂停照片同步"))
            }

            // Session guard: the Go core's session pointer is process-global and is nil after a
            // cold start (ISS-012). Every call below needs it.
            val sessionCheck = repository.ensureGoSession()
            if (sessionCheck.isFailure) {
                return@withContext Result.failure(
                    sessionCheck.exceptionOrNull() ?: Exception("会话建立失败")
                )
            }

            var state = loadOrCreateState()

            if (!force && state.isComplete && photoDao.countUnbucketed() == 0 && monthIndexDao.getAll().isNotEmpty()) {
                Log.d(TAG, "scanAll - library already fully scanned")
                _progress.value = ScanProgress(
                    isScanning = false,
                    scanned = state.scannedCount,
                    estimatedTotal = state.estimatedTotal,
                    isComplete = true
                )
                return@withContext Result.success(0)
            }

            val purgedOrphans = photoDao.deleteOrphanRows()
            if (purgedOrphans > 0) {
                Log.i(TAG, "Purged $purgedOrphans legacy orphan rows before scanning")
            }

            // Upload de-duplication used to be missing, so the same file could land twice as two
            // `CPLAsset` records sharing one `CPLMaster`. Apple's clients collapse those (they key
            // on the master) but our grid keys on the asset record name, so the library looked
            // clean there and duplicated here. Cheap to sweep on every scan.
            val purgedDuplicates = photoDao.deleteDuplicateMasterRows()
            if (purgedDuplicates > 0) {
                Log.w(TAG, "Purged $purgedDuplicates duplicate rows sharing one masterId")
            }

            // v4 migration left every pre-existing row at yearMonth = 0, and an interrupted scan
            // may have left the same. Bucket them BEFORE deciding whether there is anything to do:
            // otherwise an install that already fetched photos shows a populated count but an
            // empty gallery, because no month index row exists for those photos.
            backfillUnbucketedRows()
            rebuildMonthIndex()

            // `scan_seen_ids` must only be cleared when a *fresh* walk is about to start.
            //
            // A resumed walk (cursor kept from an interrupted run) covers only the remaining
            // pages, so clearing the table there leaves `seen` holding the tail of the library and
            // makes the reconcile pass delete everything the earlier pages covered. Measured cost
            // of getting this wrong: 700 legitimate photos purged after a mid-scan kill.
            if (force) {
                Log.i(TAG, "Forced full scan requested: resetting cursor and scan_seen_ids")
                state = state.copy(
                    lastMarker = null,
                    isComplete = false,
                    scannedCount = 0,
                    updatedAt = System.currentTimeMillis()
                )
                scanStateDao.set(state)
                repository.resetSeenIds()
            } else if (state.lastMarker == null) {
                repository.resetSeenIds()
            } else {
                Log.d(TAG, "resuming walk - keeping earlier page ids in scan_seen_ids")
            }

            val run = runScanLoop(state, batchSize)

            // Rows can also arrive outside the scan loop — `syncIncremental` upserts them directly,
            // and a run that stopped early may have written some that were never bucketed. Sweep
            // once more here so nothing is left stranded at `yearMonth = 0`: such a row is counted
            // in the header total yet invisible in the gallery, which reads as "my photos are
            // missing" with no obvious cause.
            backfillUnbucketedRows()
            rebuildMonthIndex()

            // Report whether the walk actually finished, not merely that it ran without throwing.
            // `runScanLoop` returns early on: an in-progress wipe, an unrecoverable session, or
            // exhausted transient retries. Treating any of those as "done" produced a "扫描完成"
            // banner over a half-scanned library and left the newest months flagged incomplete.
            val finalState = loadOrCreateState()
            if (run.walkCompleted) {
                // Belt and braces. `runScanLoop` stamps every month complete when it exits normally,
                // but a run that completed on an earlier build (or one whose final sweep was
                // interrupted) leaves months stuck at isComplete = 0 — which the UI renders as
                // "扫描中" forever, on a library that is demonstrably finished.
                markAllMonthsComplete()

                // Only a walk CloudKit declared finished may be used to prove absence (ISS-031).
                // A transient failure must never be allowed to delete anything.
                val reconciled = repository.reconcileWithSeenIds()
                if (reconciled.isFailure) {
                    Log.w(
                        TAG,
                        "reconcile did not run: ${reconciled.exceptionOrNull()?.message}"
                    )
                }

                // Months can still be complete-but-coverless: reconciliation may have just
                // dropped rows, and any month never touched since the v8 migration has a null
                // `coverAssetId`. Sweeping the whole index here is what makes the jump card
                // populated on an upgraded install without waiting for a re-scan.
                refreshAllCoverIds()
                prefetchCovers()
            } else {
                Log.w(
                    TAG,
                    "scanAll stopped early (scanned=${finalState.scannedCount}, cursor kept) - " +
                        "will resume on the next trigger; reconciliation skipped"
                )
            }
            Result.success(if (run.walkCompleted) run.scanned else 0)
        } catch (e: CancellationException) {
            // Cancellation is not a failure: the cursor is already persisted, so the next run
            // resumes. Rethrow so structured concurrency behaves.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "scanAll failed", e)
            Result.failure(e)
        } finally {
            _progress.value = _progress.value.copy(isScanning = false)
            scanMutex.unlock()
        }
    }

    /**
     * How one visit to the cursor ended.
     *
     * [walkCompleted] is the single load-bearing signal for reconciliation (ISS-031): it is true
     * **only** when CloudKit itself declared the end of the result set. Every early exit — wipe in
     * progress, unrecoverable session, exhausted transient retries, coroutine cancellation — reports
     * false, because in those cases the set of ids seen so far is incomplete and using it to delete
     * cached rows would wipe the library.
     *
     * `scan_state.isComplete` deliberately plays no part here: it is written per page and can be
     * left in a half-true state by the very early exits listed above.
     */
    internal data class ScanRunResult(
        val scanned: Int,
        val walkCompleted: Boolean
    )

    // ------------------------------------------------------------------------------------------
    // Core loop
    // ------------------------------------------------------------------------------------------

    private suspend fun runScanLoop(initial: ScanStateEntity, batchSize: Int): ScanRunResult {
        var state = initial
        var marker = state.lastMarker ?: ""
        var scanned = state.scannedCount
        var attempt = 0

        _progress.value = ScanProgress(
            isScanning = true,
            scanned = scanned,
            estimatedTotal = state.estimatedTotal,
            currentYearMonth = _progress.value.currentYearMonth,
            isComplete = false
        )

        while (true) {
            if (repository.isWipeInProgress()) return ScanRunResult(scanned, walkCompleted = false)

            val fetched = repository.fetchScanPage(marker, batchSize)

            if (fetched.isFailure) {
                val rawJson = (fetched.exceptionOrNull() as? CoreFetchFailure)?.rawJson ?: ""
                val classified = classifyCoreResponse(rawJson)

                when (classified.kind) {
                    CoreErrorKind.AUTH_EXPIRED -> {
                        // Confirmed 401/421: the cursor belonged to the dead session. Drop it and
                        // restart from the newest record (ISS-020). Rows are keyed by id, so the
                        // overlap de-duplicates on upsert.
                        Log.w(TAG, "scan auth expired - dropping cursor and restarting")
                        marker = ""
                        attempt = 0
                        val rebuilt = repository.ensureGoSession(force = true)
                        if (rebuilt.isFailure) {
                            Log.e(TAG, "scan aborted - session rebuild failed")
                            return ScanRunResult(scanned, walkCompleted = false)
                        }
                        state = state.copy(lastMarker = null, scannedCount = scanned)
                        persistState(state)
                        continue
                    }

                    CoreErrorKind.RATE_LIMITED, CoreErrorKind.TIMEOUT, CoreErrorKind.NETWORK -> {
                        // Transient. Back off and retry the SAME cursor. Never rebuild the session
                        // here — a rebuild is a /validate round trip plus re-signed URLs, and
                        // doing it on a throttle storm is what turns a hiccup into a loop.
                        attempt += 1
                        if (attempt > MAX_TRANSIENT_RETRIES) {
                            Log.w(TAG, "scan giving up after $attempt transient failures: ${classified.message}")
                            return ScanRunResult(scanned, walkCompleted = false)
                        }
                        val backoff = backoffMs(attempt)
                        Log.w(
                            TAG,
                            "scan transient failure (${classified.kind}), retrying in ${backoff}ms " +
                                "(attempt $attempt/$MAX_TRANSIENT_RETRIES)"
                        )
                        delay(backoff)
                        continue
                    }

                    else -> {
                        Log.e(TAG, "scan failed (${classified.kind}): ${classified.message}")
                        return ScanRunResult(scanned, walkCompleted = false)
                    }
                }
            }

            val page = fetched.getOrThrow()
            attempt = 0

            // Writes the page, records its ids for the reconcile pass, and purges anything CloudKit
            // returned as `isDeleted`. Kept in the repository so the three steps share one lock.
            val written = repository.recordScanPage(page)

            if (written > 0) {
                val entities = page.entities
                val touchedMonths = entities.map { it.yearMonth }.distinct()

                scanned += entities.size
                state = state.copy(
                    lastMarker = page.nextMarker.ifBlank { null },
                    scannedCount = scanned,
                    isComplete = !page.hasMore,
                    updatedAt = System.currentTimeMillis()
                )
                scanStateDao.set(state)

                _progress.value = ScanProgress(
                    isScanning = true,
                    scanned = scanned,
                    estimatedTotal = state.estimatedTotal,
                    currentYearMonth = entities.lastOrNull()?.yearMonth
                        ?: _progress.value.currentYearMonth,
                    isComplete = !page.hasMore
                )

                Log.d(
                    TAG,
                    "scan page +${entities.size} -> total $scanned, months=${touchedMonths.size}, " +
                        "hasMore=${page.hasMore}"
                )
            } else {
                state = state.copy(
                    lastMarker = page.nextMarker.ifBlank { null },
                    scannedCount = scanned,
                    isComplete = !page.hasMore,
                    updatedAt = System.currentTimeMillis()
                )
                scanStateDao.set(state)
            }

            if (page.syncToken.isNotBlank()) {
                syncStateDao.setSyncState(
                    SyncStateEntity(
                        key = PhotoRepository.KEY_PRIMARY_SYNC,
                        syncToken = page.syncToken,
                        continuationMarker = page.nextMarker.ifBlank { null },
                        hasMore = page.hasMore,
                        lastSyncTime = System.currentTimeMillis()
                    )
                )
            }

            if (!page.hasMore || page.nextMarker.isBlank()) {
                // Mark every month complete now that the walk is finished.
                markAllMonthsComplete()
                Log.i(TAG, "scan complete - $scanned assets across ${monthIndexDao.getAll().size} months")
                _progress.value = ScanProgress(
                    isScanning = false,
                    scanned = scanned,
                    estimatedTotal = state.estimatedTotal,
                    currentYearMonth = _progress.value.currentYearMonth,
                    isComplete = true
                )
                // The only place a walk is declared complete: CloudKit stopped handing out a
                // continuationMarker. Everything the reconciler needs is now in `scan_seen_ids`.
                return ScanRunResult(scanned, walkCompleted = true)
            }

            marker = page.nextMarker
        }
    }

    // ------------------------------------------------------------------------------------------
    // Month index maintenance
    // ------------------------------------------------------------------------------------------

    /**
     * Recomputes one month's index row from the photos currently in the database.
     *
     * Runs after every batch so a month becomes visible the moment its first photo lands — that is
     * what makes "scan while browsing" possible instead of showing an empty gallery for a minute.
     *
     * **`isComplete` is derived, not passed in.** It means one thing only: "the walk has finished,
     * so this month will not grow any further". Callers used to guess it per-batch, which meant the
     * last batch's month was stamped complete while every earlier one stayed "扫描中" forever, and a
     * rebuild during a running scan un-stamped months that were already done. Reading it from
     * `scan_state` makes the whole index consistent by construction.
     */
    private suspend fun refreshMonthIndex(yearMonth: Int) {
        if (yearMonth <= 0) return
        val aggregate = photoDao.monthAggregate(yearMonth) ?: return
        if (aggregate.itemCount <= 0) {
            monthIndexDao.deleteMonth(yearMonth)
            return
        }
        val walkFinished = scanStateDao.get(ScanStateEntity.KEY_MONTH_SCAN)?.isComplete == true

        val existingCover = monthIndexDao.get(yearMonth)?.coverAssetId
        val cover = existingCover
            ?: if (appSettings.monthCoverEnabled.value) photoDao.newestInMonth(yearMonth)?.id else null

        monthIndexDao.upsert(
            PhotoMonthIndexEntity(
                yearMonth = yearMonth,
                itemCount = aggregate.itemCount,
                firstAssetDate = aggregate.firstAssetDate ?: 0L,
                lastAssetDate = aggregate.lastAssetDate ?: 0L,
                isComplete = walkFinished,
                coverAssetId = cover
            )
        )
    }

    /**
     * Rebuilds every month row — used after the v3→v4 backfill.
     *
     * @param completeAll forces every month complete regardless of the walk state. Only used to
     *   repair an index written by an older build, where the walk had finished but the per-month
     *   flags never got stamped.
     */
    private suspend fun rebuildMonthIndex(completeAll: Boolean = false) {
        if (completeAll) {
            for (ym in photoDao.distinctYearMonths()) {
                refreshMonthIndex(ym)
            }
            markAllMonthsComplete()
        } else {
            for (ym in photoDao.distinctYearMonths()) {
                refreshMonthIndex(ym)
            }
        }
    }

    private suspend fun markAllMonthsComplete() {
        val months = monthIndexDao.getAll()
        if (months.isEmpty()) return
        monthIndexDao.upsertAll(months.map { it.copy(isComplete = true) })
    }

    /**
     * Backfills `coverAssetId` for every month that does not have one yet.
     *
     * ## Why a separate sweep from [refreshMonthIndex]
     *
     * [refreshMonthIndex] is called per batch during the walk, and runs far too often to also do a
     * "newest asset in this month" lookup. More importantly, most of a library is *not* re-walked
     * on a given run: a resumed walk or an incremental sync only touches recent months, so an
     * upgraded install would keep null covers for years of history. This sweep runs once, after a
     * completed walk, and only pays for months whose cover is genuinely missing.
     *
     * Cheap by construction: one indexed `ORDER BY assetDate DESC LIMIT 1` per coverless month, and
     * after the first run that is zero months.
     */
    private suspend fun refreshAllCoverIds() {
        if (!appSettings.monthCoverEnabled.value) {
            Log.d(TAG, "cover sweep skipped - covers disabled")
            return
        }
        val months = monthIndexDao.getAll()
        val missing = months.filter { it.coverAssetId.isNullOrBlank() }
        if (missing.isEmpty()) return

        Log.i(TAG, "backfilling coverAssetId for ${missing.size}/${months.size} months")
        var updated = 0
        for (month in missing) {
            val newest = runCatching { photoDao.newestInMonth(month.yearMonth) }.getOrNull()
            if (newest == null) continue
            monthIndexDao.upsert(month.copy(coverAssetId = newest.id))
            updated++
        }
        Log.i(TAG, "cover sweep done - $updated months updated")
    }

    /**
     * Downloads the month covers so the jump card opens already populated.
     *
     * Wraps [PhotoRepository.prefetchMonthCovers], which does the concurrency limiting and swallows
     * per-image failures. The only job here is to publish [ScanProgress.isCachingCovers] so the UI
     * can attribute the wait, and to guarantee the flag is cleared even if the pass throws.
     */
    private suspend fun prefetchCovers() {
        if (!appSettings.monthCoverEnabled.value) return

        val months = monthIndexDao.getAll().map { it.yearMonth }
        if (months.isEmpty()) return

        _progress.value = _progress.value.copy(isCachingCovers = true)
        try {
            val cached = repository.prefetchMonthCovers(months)
            Log.i(TAG, "cover prefetch: $cached/${months.size} cached")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never fail a scan over a cosmetic cache warm-up.
            Log.w(TAG, "cover prefetch failed: ${e.message}")
        } finally {
            _progress.value = _progress.value.copy(isCachingCovers = false)
        }
    }

    /**
     * Assigns `yearMonth` to rows the migration left at 0.
     *
     * Purely local work — no network — so it runs before the first page request to make an
     * upgraded install immediately browsable.
     */
    private suspend fun backfillUnbucketedRows() {
        val pending = photoDao.getUnbucketed()
        if (pending.isEmpty()) return
        Log.i(TAG, "Backfilling yearMonth for ${pending.size} pre-v4 rows")
        for (row in pending) {
            photoDao.setYearMonth(row.id, MonthBucketing.yearMonthOf(row.assetDate))
        }
    }

    /**
     * Buckets legacy rows and rebuilds the month index without touching the network.
     *
     * Called on startup so a v3→v4 upgrade shows its existing photos immediately, instead of an
     * empty gallery behind a non-zero counter while a full re-scan happens.
     */
    suspend fun ensureMonthIndexUpToDate() = withContext(Dispatchers.IO) {
        runCatching {
            photoDao.deleteOrphanRows()
            backfillUnbucketedRows()

            // Order matters. `rebuildMonthIndex()` writes every row back with
            // `isComplete = false`, so the "library is finished" stamp has to be applied AFTER it —
            // otherwise a cold start un-finishes the whole index and the UI shows “扫描中” on a
            // library that has been fully scanned for days.
            val state = scanStateDao.get(ScanStateEntity.KEY_MONTH_SCAN)
            val finished = state?.isComplete == true

            rebuildMonthIndex(completeAll = finished)
        }.onFailure {
            Log.w(TAG, "Month index backfill failed: ${it.message}")
        }
    }

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    private suspend fun loadOrCreateState(): ScanStateEntity {
        val existing = scanStateDao.get(ScanStateEntity.KEY_MONTH_SCAN)
        if (existing != null) return existing
        val fresh = ScanStateEntity(
            key = ScanStateEntity.KEY_MONTH_SCAN,
            lastMarker = null,
            isComplete = false,
            scannedCount = 0,
            estimatedTotal = 0,
            updatedAt = System.currentTimeMillis()
        )
        scanStateDao.set(fresh)
        return fresh
    }

    private suspend fun persistState(state: ScanStateEntity) {
        scanStateDao.set(state)
    }

    /**
     * Records the library size scraped from the iCloud web page.
     *
     * Only used as the progress denominator; nothing depends on it being exact, and 0 (unknown)
     * simply means the bar switches to indeterminate mode.
     */
    suspend fun setEstimatedTotal(total: Int) {
        if (total <= 0) return
        val state = loadOrCreateState()
        if (state.estimatedTotal == total) return
        scanStateDao.set(state.copy(estimatedTotal = total, updatedAt = System.currentTimeMillis()))
        _progress.value = _progress.value.copy(estimatedTotal = total)
        Log.i(TAG, "Estimated library size set to $total")
    }

    /** Loads the persisted estimate so the progress bar has a denominator after a cold start. */
    suspend fun restoreProgress() = withContext(Dispatchers.IO) {
        val state = scanStateDao.get(ScanStateEntity.KEY_MONTH_SCAN) ?: return@withContext
        _progress.value = ScanProgress(
            isScanning = false,
            scanned = state.scannedCount,
            estimatedTotal = state.estimatedTotal,
            isComplete = state.isComplete
        )
    }

    companion object {
        private const val TAG = "MonthScanEngine"

        /** Records per page. 200 is the CloudKit maximum this endpoint accepts. */
        const val DEFAULT_BATCH_SIZE = 200

        /** Attempts for transient failures (429 / timeout / network) before giving up for now. */
        private const val MAX_TRANSIENT_RETRIES = 5

        /** 1s, 2s, 4s, 8s, 16s — enough to ride out a throttle without hanging the scan. */
        private fun backoffMs(attempt: Int): Long =
            (1000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(16_000L)

        /** Marker length is irrelevant; this just documents what a "no marker" page looks like. */
        internal fun isTerminalMarker(marker: String): Boolean = marker.isBlank()
    }
}
