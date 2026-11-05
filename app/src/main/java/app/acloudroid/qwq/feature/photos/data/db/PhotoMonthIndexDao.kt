package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to the month index that drives the gallery's section list (P2-016).
 */
@Dao
interface PhotoMonthIndexDao {

    /** All months that contain photos, newest first — the top-level scroll order. */
    @Query("SELECT * FROM photo_month_index ORDER BY yearMonth DESC")
    fun observeAll(): Flow<List<PhotoMonthIndexEntity>>

    @Query("SELECT * FROM photo_month_index ORDER BY yearMonth DESC")
    suspend fun getAll(): List<PhotoMonthIndexEntity>

    @Query("SELECT * FROM photo_month_index WHERE yearMonth = :yearMonth LIMIT 1")
    suspend fun get(yearMonth: Int): PhotoMonthIndexEntity?

    @Query("SELECT MAX(yearMonth) FROM photo_month_index")
    suspend fun getNewestYearMonth(): Int?

    /**
     * Month rows for one year, joined with each month's cover asset.
     *
     * A single join rather than "read the index, then point-lookup twelve covers": the jump card
     * opens often and must not fan out into a dozen queries each time.
     *
     * `LEFT JOIN` on purpose — a month row whose `coverAssetId` is null (scanned with covers off,
     * or not yet re-scanned) must still come back so the card can render it as a text cell instead
     * of pretending the month has no photos.
     */
    @Query(
        """
        SELECT m.yearMonth AS yearMonth,
               m.itemCount AS itemCount,
               COALESCE(m.coverAssetId,
                        (SELECT id FROM photo_assets
                          WHERE isHidden = 0 AND yearMonth = m.yearMonth
                          ORDER BY assetDate DESC, id ASC LIMIT 1)) AS coverAssetId,
               a.masterId AS coverMasterId,
               a.thumbUrl AS coverThumbUrl,
               a.mediaType AS coverMediaType
        FROM photo_month_index m
        LEFT JOIN photo_assets a ON a.id = COALESCE(m.coverAssetId,
                                                   (SELECT id FROM photo_assets
                                                     WHERE isHidden = 0 AND yearMonth = m.yearMonth
                                                     ORDER BY assetDate DESC, id ASC LIMIT 1))
        WHERE m.yearMonth BETWEEN :fromYearMonth AND :toYearMonth
        ORDER BY m.yearMonth DESC
        """
    )
    fun observeCoversInRange(fromYearMonth: Int, toYearMonth: Int): Flow<List<MonthCoverRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<PhotoMonthIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PhotoMonthIndexEntity)

    @Query("DELETE FROM photo_month_index")
    suspend fun clearAll()

    @Query("DELETE FROM photo_month_index WHERE yearMonth = :yearMonth")
    suspend fun deleteMonth(yearMonth: Int)
}
