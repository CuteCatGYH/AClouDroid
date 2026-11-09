package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    // P2-016: `assetDate` alone is NOT a stable ordering key — many photos share the same second,
    // so ties resolve non-deterministically and the same row can land in a different position on
    // each query. Adding the unique primary key as a tiebreaker makes the ordering total.
    @Query("SELECT * FROM photo_assets WHERE isHidden = 0 ORDER BY assetDate DESC, id ASC")
    fun getAllPhotos(): Flow<List<PhotoAssetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(photos: List<PhotoAssetEntity>)

    @Query("SELECT DISTINCT yearMonth FROM photo_assets WHERE (id IN (:ids) OR masterId IN (:ids)) AND yearMonth != 0")
    suspend fun getYearMonthsForIds(ids: List<String>): List<Int>

    @Query("DELETE FROM photo_assets WHERE id IN (:ids) OR masterId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM photo_assets WHERE assetDate <= 0 OR id = masterId")
    suspend fun deleteOrphanRows(): Int

    /**
     * Deletes duplicate rows that share one `masterId`, keeping the oldest.
     *
     * A `CPLMaster.recordName` **is** the MMCS content fingerprint, so two rows pointing at the
     * same master are the same photo. They arise from uploading one file twice: each upload minted
     * a fresh `CPLAsset` record name while CloudKit collapsed the masters, so Apple's clients
     * showed one item and our grid (keyed on the asset record name) showed two.
     *
     * Keep the smallest `id`: rows arrive in `assetDate DESC, id ASC` order, so the earliest id is
     * the one the grid has already been rendering.
     */
    @Query(
        """
        DELETE FROM photo_assets
        WHERE masterId IN (
            SELECT masterId FROM photo_assets GROUP BY masterId HAVING COUNT(*) > 1
        )
        AND id NOT IN (
            SELECT MIN(id) FROM photo_assets GROUP BY masterId
        )
        """
    )
    suspend fun deleteDuplicateMasterRows(): Int

    // ------------------------------------------------------------------------------------
    // Reconciliation (ISS-031)
    // ------------------------------------------------------------------------------------

    /**
     * Just the identity columns, for diffing against the ids CloudKit returned in this walk.
     *
     * Deliberately not `SELECT *`: a 16k-row library would materialise every column of every row
     * (including four URLs each) just to compare two strings.
     */
    @Query("SELECT id, masterId FROM photo_assets")
    suspend fun getAllIdentities(): List<PhotoIdentity>

    /**
     * First page for the system photo picker's cloud sync.
     *
     * ## Why keyset and not OFFSET (ISS-035)
     *
     * `photo_assets` is not frozen while the picker is open: incremental sync, the month scanner
     * and the periodic worker all `upsertAll` / `deleteByIds` on it. `LIMIT n OFFSET m` anchors on
     * a *row number*, so a single inserted or deleted row shifts every later page by one — one row
     * gets skipped (a photo that never appears) and one gets duplicated (the picker drops it as a
     * duplicate `ID`). The picker walks the whole library in pages, so the drift accumulates and is
     * worst exactly where the user notices it: the last pages, i.e. the earliest years.
     *
     * Keyset pagination anchors on `(assetDate, id)` *values* instead, so it is immune to any
     * insert or delete. It also still matches the `(isHidden, assetDate, id)` index.
     *
     * Ordered exactly like [observeMonth] so the picker's incremental sync sees a stable sequence.
     */
    @Query(
        """
        SELECT * FROM photo_assets
        WHERE isHidden = 0
        ORDER BY assetDate DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaPageFirst(limit: Int): List<PhotoAssetEntity>

    /**
     * Next page after the `(lastDate, lastId)` cursor handed out by the previous page.
     *
     * The `id > :lastId` arm is the tiebreaker for the many rows that share one second — without
     * it the page boundary would be non-deterministic and rows could repeat or vanish.
     */
    @Query(
        """
        SELECT * FROM photo_assets
        WHERE isHidden = 0
          AND (assetDate < :lastDate OR (assetDate = :lastDate AND id > :lastId))
        ORDER BY assetDate DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaPageAfter(
        limit: Int,
        lastDate: Long,
        lastId: String
    ): List<PhotoAssetEntity>

    /**
     * Legacy OFFSET page, kept only so a picker holding a pre-keyset token keeps working.
     *
     * `ICloudMediaProvider` encodes the cursor as `"<assetDate>:<id>"`; a bare integer token can
     * only have come from the previous (offset-based) release, because the picker persists page
     * tokens across provider restarts. It is subject to the drift described on [getMediaPageFirst],
     * so it is used for at most one page and never emitted again.
     */
    @Query(
        """
        SELECT * FROM photo_assets
        WHERE isHidden = 0
        ORDER BY assetDate DESC, id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getMediaPage(limit: Int, offset: Int): List<PhotoAssetEntity>

    /**
     * Ids of assets whose stored URL is older than [olderThan], newest first.
     *
     * Used by the system photo picker to renew a whole page of URLs up-front, instead of paying
     * one `/records/lookup` per tile on its first scroll.
     */
    @Query(
        """
        SELECT id, masterId FROM photo_assets
        WHERE isHidden = 0 AND urlFetchedAt < :olderThan
        ORDER BY assetDate DESC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getStaleUrlAssetIds(olderThan: Long, limit: Int): List<PhotoIdentity>

    /**
     * Deletes only exact asset ids.
     *
     * Distinct from [deleteByIds], which also matches `masterId` and is used when CloudKit has told
     * us a specific record is gone. Reconciliation must be narrower: it has already applied the
     * conservative "master still alive → keep" rule in memory, so widening the SQL again would
     * undo that decision.
     */
    @Query("DELETE FROM photo_assets WHERE id IN (:ids)")
    suspend fun deleteByAssetIds(ids: List<String>): Int

    @Query("DELETE FROM photo_assets")
    suspend fun clearAll()

    @Query("SELECT * FROM photo_assets WHERE id = :id LIMIT 1")
    suspend fun getPhotoById(id: String): PhotoAssetEntity?

    /**
     * All rows sharing one `CPLMaster`.
     *
     * The same underlying media can be exposed by several `CPLAsset` records (edits, re-imports,
     * server-side remaps) — the measured library had 155 such rows. A single-asset lookup with
     * `LIMIT 1` therefore returns an arbitrary member of the group, which is why the refresh paths
     * below work per asset id instead.
     */
    @Query("SELECT * FROM photo_assets WHERE masterId = :masterId")
    suspend fun getPhotosByMasterId(masterId: String): List<PhotoAssetEntity>

    /**
     * Stores freshly minted URLs on exactly one asset.
     *
     * Keyed on `id`, not `masterId`: several assets can share a master, and writing to all of them
     * from one asset's refresh made three different photos share a single signature (and a single
     * `urlFetchedAt`, so the staleness detector could not tell them apart either).
     */
    @Query(
        """
        UPDATE photo_assets 
        SET thumbUrl = :thumbUrl, medUrl = :medUrl, originalUrl = :originalUrl, 
            videoComplUrl = :videoComplUrl, urlFetchedAt = :fetchedAt,
            sessionGeneration = :sessionGeneration
        WHERE id = :assetId
        """
    )
    suspend fun updateUrls(
        assetId: String,
        thumbUrl: String?,
        medUrl: String?,
        originalUrl: String?,
        videoComplUrl: String?,
        fetchedAt: Long,
        sessionGeneration: Int
    )

    @Query("SELECT COUNT(*) FROM photo_assets WHERE isHidden = 0")
    fun getCount(): Flow<Int>

    // ------------------------------------------------------------------------------------
    // Month sections (P2-016)
    // ------------------------------------------------------------------------------------

    /**
     * Photos belonging to one month, newest first.
     *
     * Each month is subscribed separately by the UI, so only the months actually on screen are
     * ever materialised — subscribing to the whole library instead would pin every entity in
     * memory for a 16k-item account.
     *
     * `id ASC` is the tiebreaker: many photos share an `assetDate` second, and without a total
     * order the same row can land in a different position on each query (ISS-021).
     */
    @Query(
        """
        SELECT * FROM photo_assets
        WHERE isHidden = 0 AND yearMonth = :yearMonth
        ORDER BY assetDate DESC, id ASC
        """
    )
    fun observeMonth(yearMonth: Int): Flow<List<PhotoAssetEntity>>

    @Query("SELECT COUNT(*) FROM photo_assets WHERE isHidden = 0 AND yearMonth = :yearMonth")
    suspend fun countMonth(yearMonth: Int): Int

    /**
     * Aggregate needed to rebuild one month's index row.
     *
     * Room cannot express an `INSERT ... SELECT` with aggregates, so the caller reads this and
     * then upserts the resulting [PhotoMonthIndexEntity].
     */
    @Query(
        """
        SELECT COUNT(*) AS itemCount, MAX(assetDate) AS firstAssetDate, MIN(assetDate) AS lastAssetDate
        FROM photo_assets
        WHERE isHidden = 0 AND yearMonth = :yearMonth
        """
    )
    suspend fun monthAggregate(yearMonth: Int): MonthAggregate?

    /**
     * The single asset that represents a month: newest by `assetDate`, ties broken by id.
     *
     * This is what the gallery's jump card shows as the month's cover. "Newest" rather than
     * "last day of the month" because they are the same thing for any month that has photos on its
     * final day, and this form is one indexed query instead of two — it also matches the first
     * photo the user sees when they scroll into that month, so the cover is recognisable.
     *
     * Uses the `(yearMonth, assetDate, id)` index added in v4.
     */
    @Query(
        """
        SELECT * FROM photo_assets
        WHERE isHidden = 0 AND yearMonth = :yearMonth
        ORDER BY assetDate DESC, id ASC
        LIMIT 1
        """
    )
    suspend fun newestInMonth(yearMonth: Int): PhotoAssetEntity?

    /** Distinct months currently present, so the index can be rebuilt wholesale after a backfill. */
    @Query("SELECT DISTINCT yearMonth FROM photo_assets WHERE isHidden = 0 AND yearMonth != 0")
    suspend fun distinctYearMonths(): List<Int>

    /** Rows written before the v4 migration (and not yet reached by the scanner). */
    @Query("SELECT id, assetDate FROM photo_assets WHERE yearMonth = 0")
    suspend fun getUnbucketed(): List<PhotoIdWithDate>

    @Query("UPDATE photo_assets SET yearMonth = :yearMonth WHERE id = :id")
    suspend fun setYearMonth(id: String, yearMonth: Int)

    @Query("SELECT COUNT(*) FROM photo_assets WHERE yearMonth = 0")
    suspend fun countUnbucketed(): Int

    // ------------------------------------------------------------------------------------
    // EXIF / GPS cache (info panel)
    // ------------------------------------------------------------------------------------

    /**
     * Stores the resolved metadata for one asset.
     *
     * An empty [json] is a legitimate value ("fetched, and the record has none"), which is why
     * the column is nullable and the timestamp, not the payload, is the cache marker.
     */
    @Query(
        "UPDATE photo_assets SET exifJson = :json, exifFetchedAt = :fetchedAt WHERE masterId = :masterId"
    )
    suspend fun setExif(masterId: String, json: String?, fetchedAt: Long)

    /**
     * Reads the cached metadata for one asset without observing it.
     *
     * Returns the entity rather than just the two metadata columns because callers also need
     * `yearMonth`/`assetDate` to render the file-info fallback when metadata is absent.
     */
    @Query("SELECT * FROM photo_assets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PhotoAssetEntity?
}

/** Projection for [PhotoDao.monthAggregate]. */
data class MonthAggregate(
    val itemCount: Int,
    val firstAssetDate: Long?,
    val lastAssetDate: Long?
)

/** Projection for [PhotoDao.getUnbucketed]. */
data class PhotoIdWithDate(
    val id: String,
    val assetDate: Long
)

/** Projection for [PhotoDao.getAllIdentities] — the minimum needed to reconcile. */
data class PhotoIdentity(
    val id: String,
    val masterId: String
)
